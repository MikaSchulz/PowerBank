package com.example.bank.workflow

import com.example.bank.activities.ApprovalRequest
import com.example.bank.activities.ApprovalVerdict
import com.example.bank.activities.ExternalBankActivities
import com.example.bank.activities.ExternalBankAnswer
import com.example.bank.activities.ExternalDebit
import com.example.bank.activities.ExternalPayment
import com.example.bank.activities.InstantPayment
import com.example.bank.activities.InternalBooking
import com.example.bank.activities.Investigation
import com.example.bank.activities.LedgerActivities
import com.example.bank.activities.Refund
import com.example.bank.activities.RiskActivities
import com.example.bank.activities.RiskCheck
import com.example.bank.activities.Submission
import com.example.bank.activities.TransferOutcome
import com.example.bank.activities.TransferRejection
import com.example.payments.messages.Pacs002
import com.example.payments.messages.ReasonCodes
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import java.time.Duration
import java.time.Instant

/**
 * Orchestrates a transfer: the order of the steps, waiting for an approval or the clearing house, retries and the
 * refund. Only Temporal building blocks: no coroutines, no I/O, no clock. Business decisions, including every status
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

    /** Transfers started before SEPA Instant: the simulated external bank, slow and unreliable. */
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

    /** The confirmation of an external transfer, or status inquiries; expected from just before the payment is sent. */
    private val clearing = ClearingHouseWait(log)

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
     * SEPA Instant through the clearing house since #59. Executions that started their external part before keep the
     * simulated external bank: Temporal replays their history, which has no version marker, with the old steps.
     */
    private fun externalTransfer(request: TransferRequest) {
        val version = Workflow.getVersion(SEPA_INSTANT, Workflow.DEFAULT_VERSION, 1)
        if (version == Workflow.DEFAULT_VERSION) simulatedExternalBank(request) else instantPayment(request)
    }

    /**
     * Reserve → send the pacs.008 → wait for the final status → settle or refund. The money is refunded or settled
     * only on a final status: a confirmation, an answer to a status inquiry, or the certainty that the clearing
     * house never got the payment. Without one the transfer goes under investigation and the amount stays reserved.
     */
    private fun instantPayment(request: TransferRequest) {
        val iban = request.creditorIban ?: return reject(request, "Creditor IBAN missing")
        outcome = ledger.reserveForExternalTransfer(ExternalDebit(request.transferId, request.debtorAccountId, request.amountCents))
        if (outcome.rejected) return

        clearing.expected = true
        val acceptedAt = Workflow.currentTimeMillis()
        val submission =
            submit(InstantPayment(request.transferId, request.debtorAccountId, iban, request.amountCents, Instant.ofEpochMilli(acceptedAt)))
        if (submission?.received == false) { // refused for good: the clearing house recorded nothing
            outcome = ledger.refundExternalTransfer(Refund(request.transferId, submission.reason))
            clearing.expected = false
            return
        }
        val received = submission?.received == true // null: unknown, the clearing house could not be reached in time
        if (received) outcome = ledger.markSentToExternalBank(request.transferId)

        val final = clearing.await(
            uetr = request.transferId,
            acceptedAt = acceptedAt,
            unknownIsNotReceived = !received,
            investigating = { outcome.status == TransferStatus.UNDER_INVESTIGATION },
            investigate = {
                outcome = ledger.startInvestigation(Investigation(request.transferId, "No final status from the clearing house"))
            },
        )
        outcome = when (final) {
            is FinalStatus.Settled -> ledger.settleExternalTransfer(request.transferId)
            is FinalStatus.Rejected -> ledger.refundExternalTransfer(Refund(request.transferId, ReasonCodes.describe(final.reasonCode)))
            FinalStatus.NotReceived -> ledger.refundExternalTransfer(Refund(request.transferId, "Not received by the clearing house"))
        }
        clearing.expected = false
        metrics().tagged(mapOf("direction" to "outgoing", "resolved_by" to final.resolvedBy)).timer("bank_transfer_clearing_wait")
            .record(com.uber.m3.util.Duration.ofMillis((Workflow.currentTimeMillis() - acceptedAt).toDouble()))
    }

    /**
     * Sends the pacs.008, retrying until the scheme's deadline: after it the clearing house refuses the payment anyway.
     * Null if it could not be reached in time; then nobody knows yet whether it got the payment.
     */
    private fun submit(payment: InstantPayment): Submission? = try {
        beforeDeadline(payment.acceptedAt.toEpochMilli()).submit(payment)
    } catch (e: ActivityFailure) {
        log.warn("Transfer {}: clearing house not reachable before the deadline", payment.transferId, e)
        null
    }

    /**
     * Before SEPA Instant: debit the account → simulated external bank → refund on rejection. Only for executions that
     * reached this point before #59; removed once none of them is open any more.
     */
    private fun simulatedExternalBank(request: TransferRequest) {
        val iban = request.creditorIban ?: return reject(request, "Creditor IBAN missing")
        outcome = ledger.debitForExternalTransfer(ExternalDebit(request.transferId, request.debtorAccountId, request.amountCents))
        if (outcome.rejected) return

        val answer = try {
            externalBank.submitPayment(ExternalPayment(request.transferId, iban, request.amountCents))
        } catch (e: ActivityFailure) {
            // All retries exhausted. Ambiguous in real life, which is why SEPA Instant asks with a status inquiry.
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

    override fun validateConfirm(confirmation: Pacs002) = clearing.validate(confirmation, request?.transferId)

    /** Only records the confirmation; the main method acts on it. Accepted means it is safely in the history. */
    override fun confirm(confirmation: Pacs002) = clearing.confirm(confirmation)

    companion object {
        val APPROVAL_TIMEOUT: Duration = Duration.ofDays(3)
        val EXTERNAL_BANK_TIMEOUT: Duration = Duration.ofMinutes(10)

        /** Change ID for `Workflow.getVersion`: external transfers as SEPA Instant payments (#59). */
        const val SEPA_INSTANT = "sepa-instant"
    }
}
