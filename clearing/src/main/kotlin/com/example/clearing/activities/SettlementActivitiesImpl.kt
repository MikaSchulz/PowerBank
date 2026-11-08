package com.example.clearing.activities

import com.example.clearing.ledger.ClearingLedger
import com.example.clearing.ledger.IllegalPaymentState
import com.example.clearing.ledger.PaymentResult
import com.example.clearing.workflow.CLEARING_TASK_QUEUE
import com.example.clearing.workflow.ClearingStatus
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.springframework.stereotype.Component
import java.util.UUID

/** Thin layer over the [ClearingLedger]; the payment decider inside it makes the decisions. */
@Component
@ActivityImpl(taskQueues = [CLEARING_TASK_QUEUE])
class SettlementActivitiesImpl(private val ledger: ClearingLedger) : SettlementActivities {

    override fun reserve(uetr: UUID) = run { ledger.reserve(uetr) }

    override fun markForwarded(uetr: UUID) = run { ledger.markForwarded(uetr) }

    override fun recordAnswer(answer: CreditorAnswer) = run { ledger.recordAnswer(answer.uetr, answer.accepted, answer.reasonCode) }

    override fun expire(uetr: UUID) = run { ledger.expire(uetr) }

    override fun settle(uetr: UUID) = run { ledger.settle(uetr) }

    /** A step the status doesn't allow doesn't get better by retrying. */
    private fun run(operation: () -> PaymentResult): PaymentOutcome = try {
        operation().let { PaymentOutcome(ClearingStatus.valueOf(it.status.name), it.reasonCode) }
    } catch (e: IllegalPaymentState) {
        throw ApplicationFailure.newNonRetryableFailureWithCause(e.message, "IllegalPaymentState", e)
    }
}
