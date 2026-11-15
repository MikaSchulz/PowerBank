package com.example.bank.domain.transfer

import com.example.bank.domain.AccountId

enum class TransferKind {
    /** Between two accounts of this bank: debit and credit in one transaction. */
    INTERNAL,

    /**
     * To another bank, as a SEPA Instant payment through the clearing house: the customer is debited, the amount is
     * reserved on the clearing account until the payment's final status is known.
     */
    EXTERNAL,

    /** Cash deposit: an internal transfer from the cash account. */
    DEPOSIT,

    /**
     * From another bank, as a SEPA Instant payment through the clearing house: checked and answered, credited from the
     * settlement account only once the clearing house confirms the settlement.
     */
    INCOMING,
}

/**
 * The business status of a transfer. Its lifecycle:
 *
 * ```
 * RECEIVED ─┬───────────────────────────────┬─→ BOOKED                                          (internal, deposit)
 *           └→ AWAITING_APPROVAL → APPROVED ┴─→ RESERVED → SENT_TO_EXTERNAL_BANK ─┬→ COMPLETED   (external)
 *                                                   │                │             └→ REFUNDED
 *                                                   └────────────────┴─→ UNDER_INVESTIGATION ─→ COMPLETED or REFUNDED
 * RECEIVED → ACCEPTED ─┬→ BOOKED                                                                  (incoming)
 *                      └→ UNDER_INVESTIGATION ─→ BOOKED or REJECTED
 * RECEIVED, AWAITING_APPROVAL, APPROVED ─→ REJECTED (nothing booked; incoming also from ACCEPTED)
 * ```
 *
 * An external transfer is completed or refunded only on a final status from the clearing house (or, before SEPA
 * Instant, from the simulated external bank); without one it waits under investigation.
 */
enum class TransferStatus(val finished: Boolean = false) {
    /** Accepted, nothing decided or booked yet. */
    RECEIVED,

    /** Suspected fraud: a person has to approve it. Nothing is booked. */
    AWAITING_APPROVAL,

    /** Approved, about to be booked. */
    APPROVED,

    /** Incoming payment: the account can receive it, the bank said yes; it is credited once the clearing house settles. */
    ACCEPTED,

    /** External transfer: the customer is debited, the amount is reserved on the clearing account, not sent yet. */
    RESERVED,

    /** External transfer: the clearing house has the payment, the creditor bank decides; the amount stays reserved. */
    SENT_TO_EXTERNAL_BANK,

    /**
     * External transfer or incoming payment without a final status: neither a confirmation nor a status inquiry gave a
     * definitive answer, or an incoming payment could not be credited. Nothing is guessed: an outgoing amount stays
     * reserved, an incoming one is not credited, until the final status is known.
     */
    UNDER_INVESTIGATION,

    /** Internal transfer or deposit: debit and credit are booked. Incoming payment: credited to the customer. */
    BOOKED(finished = true),

    /** The external bank accepted; with SEPA Instant, the payment is settled through the clearing house. */
    COMPLETED(finished = true),

    /** The external bank rejected, or the payment never reached the clearing house; the debit was booked back. */
    REFUNDED(finished = true),

    /**
     * Rejected before anything was booked: insufficient funds, approval denied or not given in time, an incoming
     * payment the account can't receive or the clearing house didn't settle, …
     */
    REJECTED(finished = true),
}

/** State of a transfer, computed from its events. `status == null` means the transfer does not exist. */
data class Transfer(
    val status: TransferStatus? = null,
    /** Null only for transfers recorded before the ledger stored their details. */
    val kind: TransferKind? = null,
    val debtorAccountId: AccountId? = null,
    /** Null for external transfers: the other bank's account is not known to the ledger. */
    val creditorAccountId: AccountId? = null,
    val amountCents: Long = 0,
    /** Why it is waiting, rejected or refunded; null otherwise. */
    val reason: String? = null,
) {
    val exists: Boolean get() = status != null
}
