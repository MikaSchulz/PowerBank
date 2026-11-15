package com.example.bank.workflow

import com.example.bank.activities.ApprovalRequest
import com.example.bank.activities.ApprovalVerdict
import com.example.bank.activities.ClearingHouseActivities
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
import com.example.bank.activities.StatusReport
import com.example.bank.activities.Submission
import com.example.bank.activities.TransferOutcome
import com.example.bank.activities.TransferRejection
import com.example.payments.messages.Pacs002
import com.example.payments.messages.ReasonCodes
import com.example.payments.messages.TxSts
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import java.time.Duration
import java.time.Instant
import java.util.UUID

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

    /** Status inquiries: retried with backoff for a minute, then the workflow asks again later. */
    private val inquiries = Workflow.newActivityStub(
        ClearingHouseActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(5))
            .setScheduleToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(20))
                    .build(),
            )
            .build(),
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

    /** The clearing house's confirmation (pacs.002 ACSC or RJCT); null until it arrives. */
    private var confirmation: Pacs002? = null

    /**
     * True from just before the payment is sent until the transfer has its final status. Set before sending: the
     * clearing house may confirm before the submit activity has returned.
     */
    private var confirmationExpected = false

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

        confirmationExpected = true
        val acceptedAt = Workflow.currentTimeMillis()
        val submission =
            submit(InstantPayment(request.transferId, request.debtorAccountId, iban, request.amountCents, Instant.ofEpochMilli(acceptedAt)))
        if (submission?.received == false) { // refused for good: the clearing house recorded nothing
            outcome = ledger.refundExternalTransfer(Refund(request.transferId, submission.reason))
            confirmationExpected = false
            return
        }
        val received = submission?.received == true // null: unknown, the clearing house could not be reached in time
        if (received) outcome = ledger.markSentToExternalBank(request.transferId)

        val final = awaitFinalStatus(request.transferId, acceptedAt, received)
        outcome = when (final) {
            is FinalStatus.Settled -> ledger.settleExternalTransfer(request.transferId)
            is FinalStatus.Rejected -> ledger.refundExternalTransfer(Refund(request.transferId, ReasonCodes.describe(final.reasonCode)))
            FinalStatus.NotReceived -> ledger.refundExternalTransfer(Refund(request.transferId, "Not received by the clearing house"))
        }
        confirmationExpected = false
        metrics().tagged(mapOf("resolved_by" to final.resolvedBy)).timer("bank_transfer_clearing_wait")
            .record(com.uber.m3.util.Duration.ofMillis((Workflow.currentTimeMillis() - acceptedAt).toDouble()))
    }

    /**
     * Sends the pacs.008, retrying until the scheme's deadline: after it the clearing house refuses the payment anyway.
     * Null if it could not be reached in time; then nobody knows yet whether it got the payment.
     */
    private fun submit(payment: InstantPayment): Submission? {
        val deadline = payment.acceptedAt.toEpochMilli() + SCHEME_DEADLINE.toMillis()
        val clearingHouse = Workflow.newActivityStub(
            ClearingHouseActivities::class.java,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .setScheduleToCloseTimeout(until(deadline).coerceAtLeast(Duration.ofSeconds(1)))
                .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofMillis(500)).build())
                .build(),
        )
        return try {
            clearingHouse.submit(payment)
        } catch (e: ActivityFailure) {
            log.warn("Transfer {}: clearing house not reachable before the deadline", payment.transferId, e)
            null
        }
    }

    /**
     * Waits for the confirmation (update [confirm]) until [CONFIRMATION_TIMEOUT] after the acceptance, then asks with
     * status inquiries (pacs.028) every [INQUIRY_INTERVAL]. After [INQUIRY_PERIOD] without a final status the transfer
     * goes under investigation and the workflow keeps asking every [INVESTIGATION_INTERVAL]; a late confirmation ends
     * the wait at any time.
     */
    private fun awaitFinalStatus(transferId: UUID, acceptedAt: Long, received: Boolean): FinalStatus {
        Workflow.await(until(acceptedAt + CONFIRMATION_TIMEOUT.toMillis())) { confirmation != null }
        val inquiringSince = Workflow.currentTimeMillis()
        while (true) {
            confirmation?.let { return it.finalStatus(resolvedBy = "confirmation") }
            val report = inquire(transferId)
            val status = report?.status
            when {
                status != null && status.isFinal -> return status.finalStatus(resolvedBy = "inquiry")
                // Unknown after the deadline: the clearing house will refuse the payment if it still arrives.
                report != null && status == null && !received -> return FinalStatus.NotReceived
                // Otherwise not final yet (PDNG), not reachable, or unknown although it confirmed receipt: ask again.
            }
            if (!investigating && Workflow.currentTimeMillis() - inquiringSince >= INQUIRY_PERIOD.toMillis()) {
                outcome = ledger.startInvestigation(Investigation(transferId, "No final status from the clearing house"))
                log.warn("Transfer {}: no final status from the clearing house, under investigation", transferId)
            }
            Workflow.await(if (investigating) INVESTIGATION_INTERVAL else INQUIRY_INTERVAL) { confirmation != null }
        }
    }

    private val investigating get() = outcome.status == TransferStatus.UNDER_INVESTIGATION

    private fun inquire(transferId: UUID): StatusReport? = try {
        inquiries.inquire(transferId)
    } catch (e: ActivityFailure) {
        log.warn("Transfer {}: status inquiry failed", transferId, e)
        null
    }

    private val Pacs002.isFinal get() = txSts == TxSts.ACSC || txSts == TxSts.RJCT

    private fun Pacs002.finalStatus(resolvedBy: String) =
        if (txSts == TxSts.ACSC) FinalStatus.Settled(resolvedBy) else FinalStatus.Rejected(reasonCode, resolvedBy)

    /** What the clearing house's books say in the end; not serialized, only for the branching here. */
    private sealed interface FinalStatus {
        val resolvedBy: String

        data class Settled(override val resolvedBy: String) : FinalStatus

        data class Rejected(val reasonCode: String?, override val resolvedBy: String) : FinalStatus

        data object NotReceived : FinalStatus {
            override val resolvedBy = "inquiry"
        }
    }

    private fun until(epochMillis: Long): Duration = Duration.ofMillis((epochMillis - Workflow.currentTimeMillis()).coerceAtLeast(0))

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

    override fun validateConfirm(confirmation: Pacs002) {
        val request = checkNotNull(request) { "Transfer not started yet" }
        check(confirmationExpected && this.confirmation == null) { "No confirmation expected for this transfer" }
        check(confirmation.uetr == request.transferId) { "Confirmation for another payment" }
        check(confirmation.isFinal) { "A confirmation is ACSC or RJCT" }
    }

    /** Only records the confirmation; the main method acts on it. Accepted means it is safely in the history. */
    override fun confirm(confirmation: Pacs002) {
        this.confirmation = confirmation
    }

    companion object {
        val APPROVAL_TIMEOUT: Duration = Duration.ofDays(3)
        val EXTERNAL_BANK_TIMEOUT: Duration = Duration.ofMinutes(10)

        /** Change ID for `Workflow.getVersion`: external transfers as SEPA Instant payments (#59). */
        const val SEPA_INSTANT = "sepa-instant"

        /** SCT Inst: the payment must be through within 10 seconds of its acceptance. */
        val SCHEME_DEADLINE: Duration = Duration.ofSeconds(10)

        /** After this, counted from the acceptance, the bank stops waiting for the confirmation and asks. */
        val CONFIRMATION_TIMEOUT: Duration = Duration.ofSeconds(20)
        val INQUIRY_INTERVAL: Duration = Duration.ofSeconds(30)

        /** Without a final status for this long, the transfer goes under investigation. */
        val INQUIRY_PERIOD: Duration = Duration.ofMinutes(5)
        val INVESTIGATION_INTERVAL: Duration = Duration.ofMinutes(10)
    }
}
