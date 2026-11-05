package com.example.clearing.activities

import com.example.clearing.workflow.ClearingStatus
import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
import io.temporal.activity.ActivityInterface
import java.util.UUID

/** The payment's status after a ledger activity, as the payment decider decided it. */
data class PaymentOutcome(val status: ClearingStatus, val reasonCode: String? = null)

data class CreditorAnswer(val uetr: UUID, val accepted: Boolean, val reasonCode: String?)

/** Every change of a payment and its money goes through these activities. Idempotent (at-least-once). */
@ActivityInterface(namePrefix = "Settlement")
interface SettlementActivities {
    fun reserve(uetr: UUID): PaymentOutcome

    fun markForwarded(uetr: UUID): PaymentOutcome

    fun recordAnswer(answer: CreditorAnswer): PaymentOutcome

    fun expire(uetr: UUID): PaymentOutcome

    fun settle(uetr: UUID): PaymentOutcome
}

data class Forward(val creditorBank: String, val pacs008: Pacs008)

data class Confirmation(val bank: String, val pacs002: Pacs002)

/** Messages to the participants, over HTTP. Banks deduplicate by MsgId and UETR, so a repeated delivery is harmless. */
@ActivityInterface(namePrefix = "Bank")
interface BankMessagingActivities {
    /** Sends the pacs.008 to the creditor bank. */
    fun forward(forward: Forward)

    /** Sends the final pacs.002 (settled or rejected) to a bank. */
    fun confirm(confirmation: Confirmation)
}
