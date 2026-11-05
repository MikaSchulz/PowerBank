package com.example.clearing.workflow

import com.example.clearing.activities.BankMessagingActivities
import com.example.clearing.activities.Confirmation
import com.example.clearing.activities.CreditorAnswer
import com.example.clearing.activities.Forward
import com.example.clearing.activities.PaymentOutcome
import com.example.clearing.activities.SettlementActivities
import com.example.payments.messages.Pacs002
import com.example.payments.messages.TxSts
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * One payment through the clearing house (decision 0007): the workflow is the process with its deadline; the payment
 * decider in the ledger decides every status, and the workflow keeps only its last answer.
 *
 * 1. reserve the amount on the debtor bank's settlement account;
 * 2. forward the pacs.008 to the creditor bank and wait for its pacs.002 (update) until the deadline;
 * 3. settle on acceptance, otherwise reject and release the reservation;
 * 4. confirm the result to both banks.
 */
@WorkflowImpl(taskQueues = [CLEARING_TASK_QUEUE])
class PaymentWorkflowImpl : PaymentWorkflow {

    private val settlement = Workflow.newActivityStub(
        SettlementActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumInterval(Duration.ofSeconds(30)).build())
            .build(),
    )

    private val log = Workflow.getLogger(PaymentWorkflowImpl::class.java)

    private var request: ClearingRequest? = null
    private var outcome = PaymentOutcome(ClearingStatus.RECEIVED) // the API received it before starting the workflow
    private var answer: Pacs002? = null

    /** Set before forwarding: the creditor bank may answer before the forward activity has returned. */
    private var answerExpected = false

    override fun clear(request: ClearingRequest): PaymentState {
        this.request = request
        val uetr = request.pacs008.uetr
        val deadline = Workflow.currentTimeMillis() + request.deadlineMillis

        outcome = settlement.reserve(uetr)
        if (outcome.status == ClearingStatus.REJECTED) {
            confirm(request.debtorBank)
            return finish()
        }

        answerExpected = true
        val forwarded = forward(request, deadline) || answer != null
        if (forwarded) {
            outcome = settlement.markForwarded(uetr)
            Workflow.await(remaining(deadline)) { answer != null }
        }
        answerExpected = false

        val a = answer
        outcome =
            if (a != null) settlement.recordAnswer(CreditorAnswer(uetr, a.txSts == TxSts.ACCP, a.reasonCode)) else settlement.expire(uetr)
        if (outcome.status == ClearingStatus.ACCEPTED) outcome = settlement.settle(uetr)

        confirm(request.debtorBank)
        if (forwarded) confirm(checkNotNull(request.creditorBank))
        return finish()
    }

    /** Delivers the payment to the creditor bank, retrying until the deadline. False if it couldn't be delivered. */
    private fun forward(request: ClearingRequest, deadline: Long): Boolean {
        val messaging = Workflow.newActivityStub(
            BankMessagingActivities::class.java,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .setScheduleToCloseTimeout(remaining(deadline).coerceAtLeast(Duration.ofSeconds(1)))
                .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofMillis(500)).build())
                .build(),
        )
        return try {
            messaging.forward(Forward(checkNotNull(request.creditorBank), request.pacs008))
            true
        } catch (e: ActivityFailure) {
            log.warn("Payment {}: creditor bank not reachable before the deadline", request.pacs008.uetr, e)
            false
        }
    }

    /** The final result to a bank; retried for a long time, banks may be down for a while. */
    private fun confirm(bank: String) {
        val status = if (outcome.status == ClearingStatus.SETTLED) TxSts.ACSC else TxSts.RJCT
        val pacs002 = Pacs002(Workflow.randomUUID(), checkNotNull(request).pacs008.uetr, status, outcome.reasonCode)
        try {
            confirmations.confirm(Confirmation(bank, pacs002))
        } catch (e: ActivityFailure) {
            // A bank that refuses the message for good (4xx) gets the result with a status inquiry (pacs.028).
            log.warn("Payment {}: confirmation to bank {} not delivered", pacs002.uetr, bank, e)
        }
    }

    private val confirmations = Workflow.newActivityStub(
        BankMessagingActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(5))
            .setScheduleToCloseTimeout(Duration.ofHours(1))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumInterval(Duration.ofMinutes(1)).build())
            .build(),
    )

    private fun finish(): PaymentState {
        val state = status()
        Workflow.getMetricsScope()
            .tagged(mapOf("status" to state.status.name, "reason" to (state.reasonCode ?: "none")))
            .counter("clearing_payments_finished")
            .inc(1)
        log.info("Payment {} finished: {}{}", state.uetr, state.status, state.reasonCode?.let { " ($it)" } ?: "")
        return state
    }

    private fun remaining(deadline: Long): Duration = Duration.ofMillis((deadline - Workflow.currentTimeMillis()).coerceAtLeast(0))

    override fun creditorAnswer(answer: Pacs002) {
        this.answer = answer
    }

    override fun validateCreditorAnswer(answer: Pacs002) {
        check(answerExpected && this.answer == null) { "No answer expected for this payment" }
        check(answer.uetr == request?.pacs008?.uetr) { "Answer for another payment" }
        check(answer.txSts == TxSts.ACCP || answer.txSts == TxSts.RJCT) { "The creditor bank answers with ACCP or RJCT" }
    }

    override fun status(): PaymentState {
        val request = checkNotNull(request) { "Payment not started yet" }
        return PaymentState(request.pacs008.uetr, outcome.status, outcome.reasonCode)
    }
}
