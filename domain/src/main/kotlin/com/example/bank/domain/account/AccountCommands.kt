package com.example.bank.domain.account

import com.example.bank.domain.AccountId
import com.example.bank.domain.CustomerId
import com.example.bank.domain.Iban
import com.example.bank.domain.TransferId

/** Everything you can ask of an account. Amounts are always in cents. */
sealed interface AccountCommand {
    val accountId: AccountId
}

data class OpenAccount(
    override val accountId: AccountId,
    val customerId: CustomerId?,
    val kind: AccountKind = AccountKind.CUSTOMER,
    val overdraftLimitCents: Long = 0,
    /** Every customer account has one; system accounts have none. */
    val iban: Iban? = null,
) : AccountCommand

/** Debit: money leaves the account. */
data class Debit(
    override val accountId: AccountId,
    val transferId: TransferId,
    val amountCents: Long,
) : AccountCommand

/** Credit: money arrives on the account. */
data class Credit(
    override val accountId: AccountId,
    val transferId: TransferId,
    val amountCents: Long,
) : AccountCommand

/** Reverses a debit, e.g. when the external bank rejects the payment (saga compensation). */
data class ReverseDebit(
    override val accountId: AccountId,
    val transferId: TransferId,
    val amountCents: Long,
) : AccountCommand

/** Reverses a credit; the counterpart of [ReverseDebit] on the clearing account. */
data class ReverseCredit(
    override val accountId: AccountId,
    val transferId: TransferId,
    val amountCents: Long,
) : AccountCommand

data class BlockAccount(override val accountId: AccountId) : AccountCommand

data class UnblockAccount(override val accountId: AccountId) : AccountCommand

data class CloseAccount(override val accountId: AccountId) : AccountCommand
