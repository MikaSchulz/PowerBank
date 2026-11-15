package com.example.bank.workflow

import com.example.bank.activities.Investigation
import com.example.bank.activities.LedgerActivities
import com.example.bank.activities.PaymentAnswer
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

/**
 * An incoming SEPA Instant payment: the ledger checks the account and records the answer, the workflow sends it to
 * the clearing house before the deadline and waits for the settlement. Credited only on ACSC; on RJCT, or if the bank
 * said no, nothing is booked. Like the transfer workflow, it only mirrors the ledger's statuses (decision 0007).
 */
@WorkflowImpl(taskQueues = [BANK_TASK_QUEUE])
class IncomingPaymentWorkflowImpl : IncomingPaymentWorkflow {

    private val ledger = Workflow.newActivityStub(
        LedgerActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumInterval(Duration.ofSeconds(30)).build())
            .build(),
    )

    private val log = Workflow.getLogger(IncomingPaymentWorkflowImpl::class.java)

    private val clearing = ClearingHouseWait(log)

    private var request: IncomingPaymentRequest? = null

    /** The ledger's last answer (decision 0007); the API recorded the payment before starting the workflow. */
    private var outcome = TransferOutcome(TransferStatus.RECEIVED)

    override fun receive(request: IncomingPaymentRequest): TransferState {
        this.request = request
        val acceptedAt = request.acceptedAt.toEpochMilli()
        // From the start: if the bank is slow, the clearing house may already reject the payment for its deadline.
        clearing.expected = true

        outcome = ledger.answerIncomingPayment(request.transferId)
        val accepted = outcome.status == TransferStatus.ACCEPTED
        answer(PaymentAnswer(request.uetr, accepted, outcome.reasonCode), acceptedAt)

        if (accepted) {
            val final = clearing.await(
                uetr = request.uetr,
                acceptedAt = acceptedAt,
                unknownIsNotReceived = false, // the clearing house forwarded it, so it knows it
                investigating = { outcome.status == TransferStatus.UNDER_INVESTIGATION },
                investigate = {
                    outcome = ledger.startInvestigation(Investigation(request.transferId, "No final status from the clearing house"))
                },
            )
            outcome = if (final is FinalStatus.Settled) {
                ledger.creditIncomingPayment(request.transferId)
            } else {
                ledger.rejectTransfer(
                    TransferRejection(request.transferId, ReasonCodes.describe((final as? FinalStatus.Rejected)?.reasonCode)),
                )
            }
            Workflow.getMetricsScope().tagged(mapOf("direction" to "incoming", "resolved_by" to final.resolvedBy))
                .timer("bank_transfer_clearing_wait")
                .record(com.uber.m3.util.Duration.ofMillis((Workflow.currentTimeMillis() - acceptedAt).toDouble()))
        }
        clearing.expected = false

        val result = status()
        Workflow.getMetricsScope()
            .tagged(mapOf("status" to result.status.name, "kind" to "incoming"))
            .counter("bank_transfers_finished")
            .inc(1)
        log.info("Incoming payment {} finished: {}{}", request.transferId, result.status, result.reason?.let { " ($it)" } ?: "")
        return result
    }

    /** The pacs.002, retried until the deadline. If it doesn't get through, the clearing house rejects the payment. */
    private fun answer(answer: PaymentAnswer, acceptedAt: Long) {
        val taken = try {
            beforeDeadline(acceptedAt).answer(answer)
        } catch (e: ActivityFailure) {
            log.warn("Incoming payment {}: answer not delivered before the deadline", request?.transferId, e)
            false
        }
        if (!taken) log.info("Incoming payment {}: the clearing house didn't take the answer", request?.transferId)
    }

    override fun status(): TransferState {
        val request = checkNotNull(request) { "Payment not received yet" }
        return TransferState(request.transferId, outcome.status, request.amountCents, external = false, reason = outcome.reason)
    }

    override fun validateConfirm(confirmation: Pacs002) = clearing.validate(confirmation, request?.uetr)

    /** Only records the confirmation; the main method acts on it. */
    override fun confirm(confirmation: Pacs002) = clearing.confirm(confirmation)
}
