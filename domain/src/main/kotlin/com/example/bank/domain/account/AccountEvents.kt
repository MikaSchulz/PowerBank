package com.example.bank.domain.account

import com.example.bank.domain.AccountId
import com.example.bank.domain.CustomerId
import com.example.bank.domain.Iban
import com.example.bank.domain.TransferId

/**
 * What happened to an account.
 *
 * The money events ([Debited], [Credited], [DebitReversed], [CreditReversed]) are exactly the postings
 * in the ledger. [Rejection]s are never stored; they are only returned to the caller.
 */
sealed interface AccountEvent {
    val accountId: AccountId
}

/** An event that creates a posting in the ledger. */
sealed interface PostingEvent : AccountEvent {
    val transferId: TransferId
    val amountCents: Long

    /** Signed amount of the posting: debits negative, credits positive. */
    val signedAmountCents: Long
}

/** A business rejection. Never booked. */
sealed interface Rejection : AccountEvent {
    val reason: String
}

data class AccountOpened(
    override val accountId: AccountId,
    val customerId: CustomerId?,
    val kind: AccountKind,
    val overdraftLimitCents: Long,
    /** Null for system accounts, and for customer accounts opened before IBANs existed. */
    val iban: Iban? = null,
) : AccountEvent

data class Debited(
    override val accountId: AccountId,
    override val transferId: TransferId,
    override val amountCents: Long,
) : PostingEvent {
    override val signedAmountCents get() = -amountCents
}

data class Credited(
    override val accountId: AccountId,
    override val transferId: TransferId,
    override val amountCents: Long,
) : PostingEvent {
    override val signedAmountCents get() = amountCents
}

data class DebitReversed(
    override val accountId: AccountId,
    override val transferId: TransferId,
    override val amountCents: Long,
) : PostingEvent {
    override val signedAmountCents get() = amountCents
}

data class CreditReversed(
    override val accountId: AccountId,
    override val transferId: TransferId,
    override val amountCents: Long,
) : PostingEvent {
    override val signedAmountCents get() = -amountCents
}

data class AccountBlocked(override val accountId: AccountId) : AccountEvent

data class AccountUnblocked(override val accountId: AccountId) : AccountEvent

data class AccountClosed(override val accountId: AccountId) : AccountEvent

data class DebitRejected(
    override val accountId: AccountId,
    val transferId: TransferId,
    override val reason: String,
) : Rejection

data class CreditRejected(
    override val accountId: AccountId,
    val transferId: TransferId,
    override val reason: String,
) : Rejection

/** Rejection of commands that don't move money (open, block, close). */
data class AccountCommandRejected(
    override val accountId: AccountId,
    override val reason: String,
) : Rejection
