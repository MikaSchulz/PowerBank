package com.example.payments.messages

import java.time.Instant
import java.util.UUID

/*
 * SEPA Instant messages between banks and the clearing house, simplified: the ISO 20022 field names as JSON, no XML.
 * IBANs and amounts are in them because the business needs them; they never go into logs, spans, metric labels or
 * workflow IDs. Every message has its own MsgId; the UETR identifies the payment across all banks and messages.
 */

/** pacs.008, FIToFICustomerCreditTransfer: a payment from the debtor bank, forwarded to the creditor bank. */
data class Pacs008(
    val msgId: UUID,
    /** Unique end-to-end transaction reference: the payment's identity in every message about it. */
    val uetr: UUID,
    /** The debtor's own reference; here the transfer ID of the debtor bank. */
    val endToEndId: String,
    /**
     * When the debtor bank accepted the payment (AccptncDtTm). The scheme's deadline runs from here: the clearing
     * house refuses a payment that reaches it after the deadline, so a late retry can't revive it.
     */
    val acceptanceDateTime: Instant,
    val amountCents: Long,
    val currency: String = "EUR",
    val debtorIban: String,
    val debtorAgentBic: String,
    val creditorIban: String,
) {
    init {
        require(amountCents > 0) { "Amount must be positive" }
        require(currency == "EUR") { "SEPA payments are in EUR" }
    }
}

/** pacs.002, FIToFIPaymentStatusReport: the creditor bank's answer, and the clearing house's confirmation to both. */
data class Pacs002(
    val msgId: UUID,
    val uetr: UUID,
    val txSts: TxSts,
    /** Why it was rejected (ISO 20022 reason code); null unless [txSts] is [TxSts.RJCT]. */
    val reasonCode: String? = null,
)

/** pacs.028, FIToFIPaymentStatusRequest: "what happened to this payment?", answered with a [Pacs002]. */
data class Pacs028(val msgId: UUID, val uetr: UUID)

/** Transaction status. */
enum class TxSts {
    /** Accepted by the creditor bank, not settled yet. */
    ACCP,

    /** Settled: the money moved between the banks' settlement accounts. Final. */
    ACSC,

    /** Rejected, see the reason code. Final. */
    RJCT,

    /** Still in progress (answer to a status request). */
    PDNG,
}

/** The reason codes this lab uses (ISO 20022 ExternalStatusReason1Code). */
object ReasonCodes {
    /** Incorrect account number: the IBAN is not known at the creditor bank. */
    const val INCORRECT_ACCOUNT = "AC01"

    /** Closed account. */
    const val CLOSED_ACCOUNT = "AC04"

    /** Blocked account. */
    const val BLOCKED_ACCOUNT = "AC06"

    /** Insufficient funds: here, the debtor bank's liquidity at the clearing house. */
    const val INSUFFICIENT_FUNDS = "AM04"

    /** The creditor bank did not answer in time. */
    const val TIMEOUT_CREDITOR_AGENT = "AB05"

    /** The creditor bank is not a participant of the clearing house. */
    const val UNKNOWN_CREDITOR_AGENT = "RC01"

    /** A readable reason for customers and the back office, with the code: "Account closed (AC04)". */
    fun describe(code: String?): String = when (code) {
        null -> "No reason given"
        INCORRECT_ACCOUNT -> "Incorrect account number ($code)"
        CLOSED_ACCOUNT -> "Account closed ($code)"
        BLOCKED_ACCOUNT -> "Account blocked ($code)"
        INSUFFICIENT_FUNDS -> "Insufficient liquidity at the clearing house ($code)"
        TIMEOUT_CREDITOR_AGENT -> "The creditor bank did not answer in time ($code)"
        UNKNOWN_CREDITOR_AGENT -> "The creditor bank can't be reached through the clearing house ($code)"
        else -> "Rejected with reason $code"
    }
}

/** The German bank code in an IBAN (`DE` + 2 check digits + 8-digit bank code + …): how the clearing house routes. */
fun germanBankCode(iban: String): String = iban.substring(GERMAN_BANK_CODE_START, GERMAN_BANK_CODE_END)

private const val GERMAN_BANK_CODE_START = 4
private const val GERMAN_BANK_CODE_END = 12
