package com.example.bank.workflow

import com.example.bank.activities.ApprovalRequest
import com.example.bank.activities.ApprovalVerdict
import com.example.bank.activities.ExternalBankActivities
import com.example.bank.activities.ExternalBankAnswer
import com.example.bank.activities.ExternalDebit
import com.example.bank.activities.ExternalPayment
import com.example.bank.activities.InternalBooking
import com.example.bank.activities.LedgerActivities
import com.example.bank.activities.Refund
import com.example.bank.activities.RiskActivities
import com.example.bank.activities.RiskCheck
import com.example.bank.activities.TransferOutcome
import com.example.bank.activities.TransferRejection
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Orchestrates a transfer: the order of the steps, waiting for an approval, retries and the saga's compensation.
 * Only Temporal building blocks: no coroutines, no I/O, no clock. Business decisions, including every status
 * change, are made by the activities (fmodel aggregates in the ledger).
 *
 * Not a Spring bean: Temporal creates one instance per execution via the no-arg constructor.
 */
@WorkflowImpl(taskQueues = [BANK_TASK_QUEUE])
class TransferWorkflowImpl : TransferWorkflow {

    private val ledger = Workflow.newActivityStub(
        LedgerActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumInterval(Duration.ofSeconds(30)).build())
            .build(),
    )

    private val risk = Workflow.newActivityStub(
        RiskActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build(),
    )

    /** The external bank is slow and unreliable: generous timeouts, retries with backoff. */
    private val externalBank = Workflow.newActivityStub(
        ExternalBankActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(EXTERNAL_BANK_TIMEOUT)
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(1))
                    .build(),
            )
            .build(),
    )

    /** The workflow's input; null until the workflow method runs. */
    private var request: TransferRequest? = null

    /** The ledger's last answer: the transfer's business status as the transfer decider decided it (decision 0007). */
    private var outcome = TransferOutcome(TransferStatus.RECEIVED) // the API received it before starting the workflow

    private var decision: ApprovalDecision? = null

    /**
     * True from the moment the risk check asks for a review until the ledger has recorded the decision. Set before the
     * ledger shows AWAITING_APPROVAL, so an approval sent right after the UI saw that status is never refused.
     */
    private var approvalPending = false

    /** Replay-aware: does not log again while Temporal replays the history. Only IDs and statuses, never the IBAN. */
    private val log = Workflow.getLogger(TransferWorkflowImpl::class.java)

    override fun transfer(request: TransferRequest): TransferState {
        this.request = request
        val external = request.creditorIban != null
        log.info("Transfer {} received, external={}", request.transferId, external) // no amount: logs are for operations

        process(request, external)
        val result = status()
        recordOutcome(result)
        log.info("Transfer {} finished: {}{}", request.transferId, result.status, result.reason?.let { " ($it)" } ?: "")
        return result
    }

    /**
     * The order of the steps, the waiting and the retries. Which step is allowed and what the transfer's status becomes
     * is decided by the ledger (transfer decider); the workflow only keeps its last answer in [outcome].
     */
    private fun process(request: TransferRequest, external: Boolean) {
        val assessment = risk.assess(RiskCheck(request.transferId, request.debtorAccountId, request.amountCents, external))
        if (assessment.reviewRequired) {
            approvalPending = true
            outcome = ledger.requireApproval(ApprovalRequest(request.transferId, assessment.reason))
            log.info("Transfer {} awaits approval", request.transferId)
            awaitApproval(request)
            if (outcome.rejected) return
        }
        if (external) externalTransfer(request) else internalTransfer(request)
    }

    /**
     * Waits for the approve update, at most [APPROVAL_TIMEOUT]. Nothing runs meanwhile: Temporal keeps the open
     * workflow and a timer, and the ledger shows the transfer as awaiting approval.
     */
    private fun awaitApproval(request: TransferRequest) {
        val waitingSince = Workflow.currentTimeMillis()
        val decided = Workflow.await(APPROVAL_TIMEOUT) { decision != null }
        metrics().timer("bank_transfer_approval_wait")
            .record(com.uber.m3.util.Duration.ofMillis((Workflow.currentTimeMillis() - waitingSince).toDouble()))
        val d = decision
        outcome = if (decided && d != null) {
            ledger.decideApproval(ApprovalVerdict(request.transferId, d.approved, d.comment))
        } else {
            ledger.expireApproval(request.transferId)
        }
        approvalPending = false
    }

    /**
     * Business metric through Temporal's metrics scope: replay-safe (no double counting during replay) and
     * tagged with namespace, task queue and workflow type. The Spring Boot starter forwards it to Micrometer.
     */
    private fun recordOutcome(result: TransferState) {
        metrics()
            .tagged(mapOf("status" to result.status.name, "kind" to if (result.external) "external" else "internal"))
            .counter("bank_transfers_finished")
            .inc(1)
    }

    private fun metrics() = Workflow.getMetricsScope()

    /** No saga where a transaction suffices: debit and credit in one activity. */
    private fun internalTransfer(request: TransferRequest) {
        val creditor = request.creditorAccountId ?: return reject(request, "Creditor account missing")
        outcome = ledger.bookInternalTransfer(InternalBooking(request.transferId, request.debtorAccountId, creditor, request.amountCents))
    }

    /**
     * Saga: debit the account → external bank → refund on rejection.
     * The debit can't share a transaction with the external bank, so we compensate instead.
     */
    private fun externalTransfer(request: TransferRequest) {
        val iban = request.creditorIban ?: return reject(request, "Creditor IBAN missing")
        outcome = ledger.debitForExternalTransfer(ExternalDebit(request.transferId, request.debtorAccountId, request.amountCents))
        if (outcome.rejected) return

        val answer = try {
            externalBank.submitPayment(ExternalPayment(request.transferId, iban, request.amountCents))
        } catch (e: ActivityFailure) {
            // All retries exhausted. Careful, in real life this is ambiguous: did the external bank book it after all?
            // The simulator is idempotent per transfer ID; a real integration needs a status inquiry (#59).
            log.warn("Transfer {}: external bank unreachable after all retries, refunding", request.transferId, e)
            ExternalBankAnswer(accepted = false, reason = "External bank unreachable")
        }

        outcome = if (answer.accepted) {
            ledger.completeExternalTransfer(request.transferId)
        } else {
            ledger.refundExternalTransfer(Refund(request.transferId, answer.reason)) // compensation
        }
    }

    private fun reject(request: TransferRequest, reason: String) {
        outcome = ledger.rejectTransfer(TransferRejection(request.transferId, reason))
    }

    // An extension, not a property of the DTO: Jackson would serialize a property into the history.
    private val TransferOutcome.rejected get() = status == TransferStatus.REJECTED

    /** Built on demand from the input and the ledger's last answer. */
    override fun status(): TransferState {
        val request = checkNotNull(request) { "Transfer not started yet" }
        return TransferState(request.transferId, outcome.status, request.amountCents, request.creditorIban != null, outcome.reason)
    }

    override fun validateApprove(decision: ApprovalDecision) {
        check(approvalPending && this.decision == null) { "Transfer is not awaiting approval" }
    }

    /** The handler only records the decision; the main method processes it. Returns once the ledger has recorded it. */
    override fun approve(decision: ApprovalDecision): TransferState {
        this.decision = decision
        Workflow.await { !approvalPending }
        return status()
    }

    companion object {
        val APPROVAL_TIMEOUT: Duration = Duration.ofDays(3)
        val EXTERNAL_BANK_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}
