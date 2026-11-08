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
}

/**
 * The business status of a transfer. Its lifecycle:
 *
 * ```
 * RECEIVED ─┬───────────────────────────────┬─→ BOOKED                                          (internal, deposit)
 *           └→ AWAITING_APPROVAL → APPROVED ┴─→ RESERVED → SENT_TO_EXTERNAL_BANK ─┬→ COMPLETED   (external)
 *                                                   │                │             └→ REFUNDED
 *                                                   └────────────────┴─→ UNDER_INVESTIGATION ─→ COMPLETED or REFUNDED
 * RECEIVED, AWAITING_APPROVAL, APPROVED ─→ REJECTED (nothing booked)
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

    /** External transfer: the customer is debited, the amount is reserved on the clearing account, not sent yet. */
    RESERVED,

    /** External transfer: the clearing house has the payment, the creditor bank decides; the amount stays reserved. */
    SENT_TO_EXTERNAL_BANK,

    /**
     * External transfer without a final status: neither a confirmation nor a status inquiry gave a definitive answer.
     * The amount stays reserved; the transfer keeps asking the clearing house and waits for its answer.
     */
    UNDER_INVESTIGATION,

    /** Internal transfer or deposit: debit and credit are booked. */
    BOOKED(finished = true),

    /** The external bank accepted; with SEPA Instant, the payment is settled through the clearing house. */
    COMPLETED(finished = true),

    /** The external bank rejected, or the payment never reached the clearing house; the debit was booked back. */
    REFUNDED(finished = true),

    /** Rejected before anything was booked: insufficient funds, approval denied or not given in time, … */
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
