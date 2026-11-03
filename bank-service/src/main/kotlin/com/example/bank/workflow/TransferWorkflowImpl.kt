package com.example.bank.workflow

import com.example.bank.activities.ExternalBankActivities
import com.example.bank.activities.ExternalBankAnswer
import com.example.bank.activities.ExternalDebit
import com.example.bank.activities.ExternalPayment
import com.example.bank.activities.InternalBooking
import com.example.bank.activities.LedgerActivities
import com.example.bank.activities.Refund
import com.example.bank.activities.RiskActivities
import com.example.bank.activities.RiskCheck
import com.example.bank.activities.TransferRejection
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Orchestrates a transfer. Only Temporal building blocks: no coroutines, no I/O, no clock.
 * Business decisions are made by the activities (fmodel aggregate in the ledger).
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

    private var state: TransferState? = null
    private var decision: ApprovalDecision? = null

    /** Replay-aware: does not log again while Temporal replays the history. Only IDs and amounts, never the IBAN. */
    private val log = Workflow.getLogger(TransferWorkflowImpl::class.java)

    override fun transfer(request: TransferRequest): TransferState {
        val external = request.creditorIban != null
        state = TransferState(request.transferId, TransferStatus.RECEIVED, request.amountCents, external)
        log.info("Transfer {} received, external={}", request.transferId, external) // no amount: logs are for operations

        val result = process(request, external)
        recordOutcome(result)
        log.info("Transfer {} finished: {}{}", request.transferId, result.status, result.reason?.let { " ($it)" } ?: "")
        return result
    }

    private fun process(request: TransferRequest, external: Boolean): TransferState {
        val assessment = risk.assess(RiskCheck(request.transferId, request.debtorAccountId, request.amountCents, external))
        if (assessment.reviewRequired) {
            update(TransferStatus.AWAITING_APPROVAL, assessment.reason)
            log.info("Transfer {} awaits approval: {}", request.transferId, assessment.reason)
            val waitingSince = Workflow.currentTimeMillis()
            val decided = Workflow.await(APPROVAL_TIMEOUT) { decision != null }
            metrics().timer("bank_transfer_approval_wait")
                .record(com.uber.m3.util.Duration.ofMillis((Workflow.currentTimeMillis() - waitingSince).toDouble()))
            val d = decision
            when {
                !decided || d == null -> return reject(request, "No approval within the deadline")
                !d.approved -> return reject(request, "Approval denied" + (d.comment?.let { ": $it" } ?: ""))
                else -> update(TransferStatus.APPROVED)
            }
        }

        return if (external) externalTransfer(request) else internalTransfer(request)
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
    private fun internalTransfer(request: TransferRequest): TransferState {
        val creditor = request.creditorAccountId ?: return reject(request, "Creditor account missing")
        val result = ledger.bookInternalTransfer(InternalBooking(request.transferId, request.debtorAccountId, creditor, request.amountCents))
        return if (result.booked) update(TransferStatus.BOOKED) else update(TransferStatus.REJECTED, result.reason)
    }

    /**
     * Saga: debit the account → external bank → refund on rejection.
     * The debit can't share a transaction with the external bank, so we compensate instead.
     */
    private fun externalTransfer(request: TransferRequest): TransferState {
        val iban = request.creditorIban ?: return reject(request, "Creditor IBAN missing")
        val debit = ledger.debitForExternalTransfer(ExternalDebit(request.transferId, request.debtorAccountId, request.amountCents))
        if (!debit.booked) return update(TransferStatus.REJECTED, debit.reason)

        update(TransferStatus.SENT_TO_EXTERNAL_BANK)
        val answer = try {
            externalBank.submitPayment(ExternalPayment(request.transferId, iban, request.amountCents))
        } catch (e: ActivityFailure) {
            // All retries exhausted. Careful, in real life this is ambiguous: did the external bank book it after all?
            // The simulator is idempotent per transfer ID; a real integration would need a status inquiry.
            ExternalBankAnswer(accepted = false, reason = "External bank unreachable")
        }

        if (answer.accepted) {
            ledger.completeExternalTransfer(request.transferId)
            return update(TransferStatus.COMPLETED)
        }
        // compensation
        ledger.refundExternalTransfer(Refund(request.transferId, answer.reason))
        return update(TransferStatus.REFUNDED, answer.reason)
    }

    private fun reject(request: TransferRequest, reason: String): TransferState {
        ledger.rejectTransfer(TransferRejection(request.transferId, reason))
        return update(TransferStatus.REJECTED, reason)
    }

    private fun update(status: TransferStatus, reason: String? = null): TransferState =
        checkNotNull(state).copy(status = status, reason = reason).also { state = it }

    override fun status(): TransferState = checkNotNull(state) { "Transfer not started yet" }

    override fun validateApprove(decision: ApprovalDecision) {
        check(state?.status == TransferStatus.AWAITING_APPROVAL && this.decision == null) {
            "Transfer is not awaiting approval"
        }
    }

    /** The handler only records the decision; the main method processes it. */
    override fun approve(decision: ApprovalDecision): TransferState {
        this.decision = decision
        Workflow.await { state?.status != TransferStatus.AWAITING_APPROVAL }
        return status()
    }

    companion object {
        val APPROVAL_TIMEOUT: Duration = Duration.ofDays(3)
        val EXTERNAL_BANK_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}
