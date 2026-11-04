package com.example.bank.domain.transfer

import com.example.bank.domain.AccountId

enum class TransferKind {
    /** Between two accounts of this bank: debit and credit in one transaction. */
    INTERNAL,

    /** To another bank: the customer is debited, the money waits on the clearing account until the other bank answers. */
    EXTERNAL,

    /** Cash deposit: an internal transfer from the cash account. */
    DEPOSIT,
}

/**
 * The business status of a transfer. Its lifecycle:
 *
 * ```
 * RECEIVED ─┬───────────────────────────────┬─→ BOOKED                      (internal, deposit)
 *           └→ AWAITING_APPROVAL → APPROVED ┴─→ SENT_TO_EXTERNAL_BANK ─┬→ COMPLETED
 *                                                                       └→ REFUNDED
 * RECEIVED, AWAITING_APPROVAL, APPROVED ─→ REJECTED (nothing booked)
 * ```
 */
enum class TransferStatus(val finished: Boolean = false) {
    /** Accepted, nothing decided or booked yet. */
    RECEIVED,

    /** Suspected fraud: a person has to approve it. Nothing is booked. */
    AWAITING_APPROVAL,

    /** Approved, about to be booked. */
    APPROVED,

    /** External transfer: the customer is debited, the money sits on the clearing account, the external bank decides. */
    SENT_TO_EXTERNAL_BANK,

    /** Internal transfer or deposit: debit and credit are booked. */
    BOOKED(finished = true),

    /** The external bank accepted. */
    COMPLETED(finished = true),

    /** The external bank rejected or stayed unreachable; the debit was booked back. */
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
