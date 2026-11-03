package com.example.bank.domain.account

import com.fraktalio.fmodel.domain.Decider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The business logic of an account as an fmodel decider: pure functions, no I/O.
 *
 * - `decide(command, state)`: which events follow from the command?
 * - `evolve(state, event)`: how does an event change the state?
 *
 * Runs only in activities, never in workflow code (fmodel returns a Kotlin `Flow`).
 */
val accountDecider: Decider<AccountCommand, Account, AccountEvent> = Decider(
    initialState = Account(),
    decide = { command, account -> decide(command, account) },
    evolve = { account, event -> evolve(account, event) },
)

// One exhaustive `when` over all commands: the whole business logic of an account, easiest to read in one place.
@Suppress("CyclomaticComplexMethod")
internal fun decide(c: AccountCommand, s: Account): Flow<AccountEvent> = when (c) {
    is OpenAccount -> when {
        s.exists -> reject(c, "Account already exists")
        c.overdraftLimitCents < 0 -> reject(c, "Overdraft limit must not be negative")
        c.kind == AccountKind.CUSTOMER && c.customerId == null -> reject(c, "Customer account requires a customer")
        else -> flowOf(AccountOpened(c.accountId, c.customerId, c.kind, c.overdraftLimitCents))
    }

    is Debit -> when {
        c.amountCents <= 0 -> flowOf(DebitRejected(c.accountId, c.transferId, "Amount must be positive"))
        !s.exists -> flowOf(DebitRejected(c.accountId, c.transferId, "Account does not exist"))
        s.status == AccountStatus.CLOSED -> flowOf(DebitRejected(c.accountId, c.transferId, "Account is closed"))
        s.status == AccountStatus.BLOCKED -> flowOf(DebitRejected(c.accountId, c.transferId, "Account is blocked"))
        s.kind == AccountKind.CUSTOMER && c.amountCents > s.availableCents ->
            flowOf(DebitRejected(c.accountId, c.transferId, "Insufficient funds"))
        else -> flowOf(Debited(c.accountId, c.transferId, c.amountCents))
    }

    // Blocked accounts can still receive money, closed ones cannot.
    is Credit -> when {
        c.amountCents <= 0 -> flowOf(CreditRejected(c.accountId, c.transferId, "Amount must be positive"))
        !s.exists -> flowOf(CreditRejected(c.accountId, c.transferId, "Account does not exist"))
        s.status == AccountStatus.CLOSED -> flowOf(CreditRejected(c.accountId, c.transferId, "Account is closed"))
        else -> flowOf(Credited(c.accountId, c.transferId, c.amountCents))
    }

    // Compensations must not fail for business reasons: a saga always has to be reversible.
    is ReverseDebit -> when {
        c.amountCents <= 0 -> flowOf(CreditRejected(c.accountId, c.transferId, "Amount must be positive"))
        !s.exists -> flowOf(CreditRejected(c.accountId, c.transferId, "Account does not exist"))
        else -> flowOf(DebitReversed(c.accountId, c.transferId, c.amountCents))
    }

    is ReverseCredit -> when {
        c.amountCents <= 0 -> flowOf(DebitRejected(c.accountId, c.transferId, "Amount must be positive"))
        !s.exists -> flowOf(DebitRejected(c.accountId, c.transferId, "Account does not exist"))
        else -> flowOf(CreditReversed(c.accountId, c.transferId, c.amountCents))
    }

    is BlockAccount -> when (s.status) {
        null -> reject(c, "Account does not exist")
        AccountStatus.CLOSED -> reject(c, "Account is closed")
        AccountStatus.BLOCKED -> emptyFlow() // already blocked: idempotent, nothing to do
        AccountStatus.OPEN -> flowOf(AccountBlocked(c.accountId))
    }

    is UnblockAccount -> when (s.status) {
        null -> reject(c, "Account does not exist")
        AccountStatus.CLOSED -> reject(c, "Account is closed")
        AccountStatus.OPEN -> emptyFlow()
        AccountStatus.BLOCKED -> flowOf(AccountUnblocked(c.accountId))
    }

    is CloseAccount -> when {
        !s.exists -> reject(c, "Account does not exist")
        s.status == AccountStatus.CLOSED -> emptyFlow()
        s.kind == AccountKind.SYSTEM -> reject(c, "System accounts cannot be closed")
        s.balanceCents != 0L -> reject(c, "Balance must be zero before closing")
        else -> flowOf(AccountClosed(c.accountId))
    }
}

internal fun evolve(s: Account, e: AccountEvent): Account = when (e) {
    is AccountOpened -> s.copy(
        status = AccountStatus.OPEN,
        customerId = e.customerId,
        kind = e.kind,
        overdraftLimitCents = e.overdraftLimitCents,
    )
    is PostingEvent -> s.copy(balanceCents = s.balanceCents + e.signedAmountCents)
    is AccountBlocked -> s.copy(status = AccountStatus.BLOCKED)
    is AccountUnblocked -> s.copy(status = AccountStatus.OPEN)
    is AccountClosed -> s.copy(status = AccountStatus.CLOSED)
    is Rejection -> s
}

private fun reject(c: AccountCommand, reason: String): Flow<AccountEvent> =
    flowOf(AccountCommandRejected(c.accountId, reason))
