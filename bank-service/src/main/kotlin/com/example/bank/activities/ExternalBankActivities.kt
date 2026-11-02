package com.example.bank.activities

import io.temporal.activity.ActivityInterface
import java.util.UUID

/** Payment to an external bank. From stage 9 on, the creditor's IBAN is encrypted by the payload codec. */
data class ExternalPayment(val transferId: UUID, val creditorIban: String, val amountCents: Long)

/** Business answer of the external bank. Technical errors are exceptions and get retried by Temporal. */
data class ExternalBankAnswer(val accepted: Boolean, val reason: String? = null)

/** Moves to the contracts module in stage 12; it will then be implemented by the Quarkus worker. */
@ActivityInterface(namePrefix = "ExternalBank")
interface ExternalBankActivities {
    fun submitPayment(payment: ExternalPayment): ExternalBankAnswer
}
