package com.example.bank.domain.account

import com.example.bank.domain.AccountId
import com.example.bank.domain.CustomerId
import com.example.bank.domain.TransferId
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class AccountDeciderTest {

    private val account = AccountId(UUID.randomUUID())
    private val customer = CustomerId(UUID.randomUUID())
    private val t1 = TransferId(UUID.randomUUID())
    private val t2 = TransferId(UUID.randomUUID())
    private val t3 = TransferId(UUID.randomUUID())

    private val opened = AccountOpened(account, customer, AccountKind.CUSTOMER, overdraftLimitCents = 0)
    private val openedWithOverdraft = AccountOpened(account, customer, AccountKind.CUSTOMER, overdraftLimitCents = 50_00)
    private val systemAccount = AccountOpened(account, null, AccountKind.SYSTEM, overdraftLimitCents = 0)
    private fun credited(cents: Long, transfer: TransferId = t1) = Credited(account, transfer, cents)

    @Nested
    inner class Opening {
        @Test
        fun `a new account is opened`() =
            accountDecider.given()
                .whenCommand(OpenAccount(account, customer, overdraftLimitCents = 100_00))
                .thenEvents(AccountOpened(account, customer, AccountKind.CUSTOMER, 100_00))

        @Test
        fun `account already exists`() =
            accountDecider.given(opened)
                .whenCommand(OpenAccount(account, customer))
                .thenEvents(AccountCommandRejected(account, "Account already exists"))

        @Test
        fun `negative overdraft limit is not allowed`() =
            accountDecider.given()
                .whenCommand(OpenAccount(account, customer, overdraftLimitCents = -1))
                .thenEvents(AccountCommandRejected(account, "Overdraft limit must not be negative"))

        @Test
        fun `customer account without customer is not allowed`() =
            accountDecider.given()
                .whenCommand(OpenAccount(account, customerId = null))
                .thenEvents(AccountCommandRejected(account, "Customer account requires a customer"))
    }

    @Nested
    inner class Debiting {
        @Test
        fun `debit with sufficient funds`() =
            accountDecider.given(opened, credited(100_00))
                .whenCommand(Debit(account, t2, 60_00))
                .thenEvents(Debited(account, t2, 60_00))

        @Test
        fun `debiting exactly the full balance is allowed`() =
            accountDecider.given(opened, credited(100_00))
                .whenCommand(Debit(account, t2, 100_00))
                .thenEvents(Debited(account, t2, 100_00))

        @Test
        fun `debit without sufficient funds is rejected`() =
            accountDecider.given(opened, credited(100_00))
                .whenCommand(Debit(account, t2, 100_01))
                .thenEvents(DebitRejected(account, t2, "Insufficient funds"))

        @Test
        fun `the overdraft counts towards available funds`() =
            accountDecider.given(openedWithOverdraft, credited(10_00))
                .whenCommand(Debit(account, t2, 60_00))
                .thenEvents(Debited(account, t2, 60_00))

        @Test
        fun `debit beyond the overdraft is rejected`() =
            accountDecider.given(openedWithOverdraft, credited(10_00))
                .whenCommand(Debit(account, t2, 60_01))
                .thenEvents(DebitRejected(account, t2, "Insufficient funds"))

        @Test
        fun `earlier debits reduce available funds`() =
            accountDecider.given(opened, credited(100_00), Debited(account, t2, 70_00))
                .whenCommand(Debit(account, t3, 40_00))
                .thenEvents(DebitRejected(account, t3, "Insufficient funds"))

        @Test
        fun `reversals restore available funds`() =
            accountDecider.given(opened, credited(100_00), Debited(account, t2, 70_00), DebitReversed(account, t2, 70_00))
                .whenCommand(Debit(account, t1, 100_00))
                .thenEvents(Debited(account, t1, 100_00))

        @Test
        fun `blocked accounts are not debited`() =
            accountDecider.given(opened, credited(100_00), AccountBlocked(account))
                .whenCommand(Debit(account, t2, 1))
                .thenEvents(DebitRejected(account, t2, "Account is blocked"))

        @Test
        fun `closed accounts are not debited`() =
            accountDecider.given(opened, AccountClosed(account))
                .whenCommand(Debit(account, t2, 1))
                .thenEvents(DebitRejected(account, t2, "Account is closed"))

        @Test
        fun `unknown accounts are not debited`() =
            accountDecider.given()
                .whenCommand(Debit(account, t2, 1))
                .thenEvents(DebitRejected(account, t2, "Account does not exist"))

        @Test
        fun `amount must be positive`() =
            accountDecider.given(opened, credited(100_00))
                .whenCommand(Debit(account, t2, 0))
                .thenEvents(DebitRejected(account, t2, "Amount must be positive"))

        @Test
        fun `system accounts may go negative`() =
            accountDecider.given(systemAccount)
                .whenCommand(Debit(account, t2, 1_000_000_00))
                .thenEvents(Debited(account, t2, 1_000_000_00))
    }

    @Nested
    inner class Crediting {
        @Test
        fun `credit to an open account`() =
            accountDecider.given(opened)
                .whenCommand(Credit(account, t1, 5_00))
                .thenEvents(Credited(account, t1, 5_00))

        @Test
        fun `blocked accounts can still receive money`() =
            accountDecider.given(opened, AccountBlocked(account))
                .whenCommand(Credit(account, t1, 5_00))
                .thenEvents(Credited(account, t1, 5_00))

        @Test
        fun `closed accounts receive nothing`() =
            accountDecider.given(opened, AccountClosed(account))
                .whenCommand(Credit(account, t1, 5_00))
                .thenEvents(CreditRejected(account, t1, "Account is closed"))

        @Test
        fun `unknown accounts receive nothing`() =
            accountDecider.given()
                .whenCommand(Credit(account, t1, 5_00))
                .thenEvents(CreditRejected(account, t1, "Account does not exist"))
    }

    @Nested
    inner class Reversals {
        @Test
        fun `a reversal works on a blocked account too`() =
            accountDecider.given(opened, credited(10_00), Debited(account, t2, 10_00), AccountBlocked(account))
                .whenCommand(ReverseDebit(account, t2, 10_00))
                .thenEvents(DebitReversed(account, t2, 10_00))

        @Test
        fun `reversing a credit on the clearing account`() =
            accountDecider.given(systemAccount, Credited(account, t2, 10_00))
                .whenCommand(ReverseCredit(account, t2, 10_00))
                .thenEvents(CreditReversed(account, t2, 10_00))
    }

    @Nested
    inner class BlockingAndClosing {
        @Test
        fun block() =
            accountDecider.given(opened)
                .whenCommand(BlockAccount(account))
                .thenEvents(AccountBlocked(account))

        @Test
        fun `blocking twice changes nothing`() =
            accountDecider.given(opened, AccountBlocked(account))
                .whenCommand(BlockAccount(account))
                .thenNothing()

        @Test
        fun unblock() =
            accountDecider.given(opened, AccountBlocked(account))
                .whenCommand(UnblockAccount(account))
                .thenEvents(AccountUnblocked(account))

        @Test
        fun `close with zero balance`() =
            accountDecider.given(opened, credited(10_00), Debited(account, t2, 10_00))
                .whenCommand(CloseAccount(account))
                .thenEvents(AccountClosed(account))

        @Test
        fun `closing with a remaining balance is rejected`() =
            accountDecider.given(opened, credited(10_00))
                .whenCommand(CloseAccount(account))
                .thenEvents(AccountCommandRejected(account, "Balance must be zero before closing"))

        @Test
        fun `system accounts cannot be closed`() =
            accountDecider.given(systemAccount)
                .whenCommand(CloseAccount(account))
                .thenEvents(AccountCommandRejected(account, "System accounts cannot be closed"))

        @Test
        fun `a closed account cannot be blocked`() =
            accountDecider.given(opened, AccountClosed(account))
                .whenCommand(BlockAccount(account))
                .thenEvents(AccountCommandRejected(account, "Account is closed"))
    }

    @Test
    fun `evolve computes the balance from the postings`() {
        val events = listOf(
            opened,
            Credited(account, t1, 100_00),
            Debited(account, t2, 30_00),
            DebitReversed(account, t2, 30_00),
            Debited(account, TransferId(UUID.randomUUID()), 45_00),
            DebitRejected(account, TransferId(UUID.randomUUID()), "Insufficient funds"),
        )
        val state = events.fold(accountDecider.initialState, accountDecider.evolve)
        assertEquals(55_00, state.balanceCents)
        assertEquals(AccountStatus.OPEN, state.status)
    }
}
