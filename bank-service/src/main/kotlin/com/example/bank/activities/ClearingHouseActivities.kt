package com.example.bank.activities

import com.example.payments.messages.Pacs002
import io.temporal.activity.ActivityInterface
import java.time.Instant
import java.util.UUID

/**
 * An external transfer as a SEPA Instant payment. The debtor's IBAN is not in it: the activity looks it up, so it
 * doesn't go through the workflow's history. The creditor's IBAN is the exception (encrypted with stage 9).
 */
data class InstantPayment(
    val transferId: UUID,
    val debtorAccountId: UUID,
    val creditorIban: String,
    val amountCents: Long,
    /** When the bank accepted the payment: the scheme's deadline runs from here. */
    val acceptedAt: Instant,
)

/**
 * The clearing house's answer to the pacs.008: it has the payment ([received]), or it refused it for good and recorded
 * nothing, e.g. because the deadline had passed ([reason]).
 */
data class Submission(val received: Boolean, val reason: String? = null)

/** The answer to a status inquiry (pacs.028): the clearing house's pacs.002, or null if it doesn't know the payment. */
data class StatusReport(val status: Pacs002? = null)

/** The bank's answer to an incoming payment: accepted (ACCP), or rejected with an ISO 20022 reason code (RJCT). */
data class PaymentAnswer(val uetr: UUID, val accepted: Boolean, val reasonCode: String? = null)

/**
 * The bank's side of the clearing house (#58): it sends payments there, answers incoming ones and asks for their
 * status. The clearing house's messages come in through the bank's inbound endpoints (`/api/clearing/inbound/…`).
 * Technical errors (clearing house down, 5xx, timeouts) are exceptions and get retried by Temporal.
 */
@ActivityInterface(namePrefix = "ClearingHouse")
interface ClearingHouseActivities {
    /** Sends the pacs.008. Idempotent: the UETR is the transfer ID, a repeated message is recognized as received. */
    fun submit(payment: InstantPayment): Submission

    /** Sends a pacs.028: what happened to the payment? Answered from the clearing house's books. */
    fun inquire(uetr: UUID): StatusReport

    /**
     * Sends the bank's pacs.002 for an incoming payment. True: the clearing house took it. False: it doesn't expect it
     * (any more, e.g. after its deadline); its confirmation then tells what became of the payment.
     */
    fun answer(answer: PaymentAnswer): Boolean
}
