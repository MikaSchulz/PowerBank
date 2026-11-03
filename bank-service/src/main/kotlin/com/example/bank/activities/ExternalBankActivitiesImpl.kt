package com.example.bank.activities

import com.example.bank.externalbank.ExternalBankSimulator
import com.example.bank.observability.BankSpans
import com.example.bank.workflow.BANK_TASK_QUEUE
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.temporal.activity.Activity
import io.temporal.spring.boot.ActivityImpl
import org.springframework.stereotype.Component

@Component
@ActivityImpl(taskQueues = [BANK_TASK_QUEUE])
class ExternalBankActivitiesImpl(
    private val externalBank: ExternalBankSimulator,
    private val observations: ObservationRegistry,
) : ExternalBankActivities {

    /**
     * The call to the external bank is its own span and timer `bank.external-bank.call`: its duration is the bank's
     * delay, `outcome` is accepted, rejected or failure (technical error, retried by Temporal), and the attempt
     * number shows the retries.
     */
    override fun submitPayment(payment: ExternalPayment): ExternalBankAnswer {
        BankSpans.tag(BankSpans.TRANSFER_ID to payment.transferId)
        val observation = Observation.createNotStarted("bank.external-bank.call", observations)
            .highCardinalityKeyValue(BankSpans.TRANSFER_ID, payment.transferId.toString())
            .highCardinalityKeyValue("temporal.activity.attempt", Activity.getExecutionContext().info.attempt.toString())
            .start()
        return try {
            observation.openScope().use {
                externalBank.receive(payment.transferId, payment.creditorIban, payment.amountCents)
            }.also { observation.lowCardinalityKeyValue("outcome", if (it.accepted) "accepted" else "rejected") }
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) { // records any failure, then rethrows it
            observation.lowCardinalityKeyValue("outcome", "failure")
            observation.error(e)
            throw e
        } finally {
            observation.stop()
        }
    }
}
