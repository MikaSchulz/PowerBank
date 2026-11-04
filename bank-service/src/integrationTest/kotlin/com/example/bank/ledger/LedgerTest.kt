package com.example.bank.ledger

import com.example.bank.IntegrationTest
import com.example.bank.customer.CustomerRecords
import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId
import com.example.bank.domain.transfer.TransferKind
import com.example.bank.domain.transfer.TransferStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@IntegrationTest
class LedgerTest @Autowired constructor(
    private val ledger: Ledger,
    private val queries: LedgerQueries,
    private val customers: CustomerRecords,
    private val transfers: TransferRecords,
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
) {

    @AfterEach
    fun `double-entry bookkeeping holds`() {
        assertEquals(0, queries.sumOfAllPostings(), "sum of all postings must be zero")
    }

    @Test
    fun `internal transfer books two postings that sum up to zero`() {
        val a = fundedAccount(100_00)
        val b = newAccount()
        val t = newTransfer()

        val result = ledger.bookInternalTransfer(t, a, b, 30_00)

        assertTrue(result.booked)
        assertEquals(70_00, queries.balanceOf(a))
        assertEquals(30_00, queries.balanceOf(b))
        val postings = queries.postingsOfTransfer(t)
        assertEquals(listOf("debit" to -30_00L, "credit" to 30_00L), postings.map { it.leg to it.amountCents })
        assertEquals(0, postings.sumOf { it.amountCents })
        assertEquals(TransferStatus.BOOKED, status(t))
    }

    @Test
    fun `booking twice with the same transfer ID changes nothing`() {
        val a = fundedAccount(100_00)
        val b = newAccount()
        val t = newTransfer()

        repeat(3) { assertTrue(ledger.bookInternalTransfer(t, a, b, 30_00).booked) }

        assertEquals(2, queries.postingsOfTransfer(t).size)
        assertEquals(70_00, queries.balanceOf(a))
        assertEquals(30_00, queries.balanceOf(b))
    }

    @Test
    fun `insufficient funds are rejected and the decision is stable on retry`() {
        val a = fundedAccount(10_00)
        val b = newAccount()
        val t = newTransfer()

        val first = ledger.bookInternalTransfer(t, a, b, 50_00)
        assertFalse(first.booked)
        assertEquals("Insufficient funds", first.reason)

        // Activity retry after a deposit: the earlier decision still stands.
        ledger.bookInternalTransfer(newTransfer(), SystemAccounts.CASH, a, 100_00)
        assertEquals(first, ledger.bookInternalTransfer(t, a, b, 50_00))
        assertTrue(queries.postingsOfTransfer(t).isEmpty())
        assertEquals(TransferStatus.REJECTED, status(t))
    }

    @Test
    fun `if the credit fails, the debit is discarded too`() {
        val a = fundedAccount(100_00)
        val unknown = AccountId(UUID.randomUUID())
        val t = newTransfer()

        val result = ledger.bookInternalTransfer(t, a, unknown, 30_00)

        assertFalse(result.booked)
        assertEquals("Account does not exist", result.reason)
        assertEquals(100_00, queries.balanceOf(a))
        assertTrue(queries.postingsOfTransfer(t).isEmpty())
        assertEquals(TransferStatus.REJECTED, status(t))
    }

    @Test
    fun `blocked accounts are not debited`() {
        val a = fundedAccount(100_00)
        ledger.blockAccount(a)

        val result = ledger.bookInternalTransfer(newTransfer(), a, newAccount(), 1_00)

        assertEquals(TransferResult(TransferStatus.REJECTED, "Account is blocked"), result)
    }

    @Test
    fun `concurrent transfers never overdraw an account`() {
        val a = fundedAccount(100_00)
        val b = newAccount()
        val pool = Executors.newFixedThreadPool(16)

        val results = pool.invokeAll((1..40).map { Callable { ledger.bookInternalTransfer(newTransfer(), a, b, 10_00) } })
            .map { it.get() }
        pool.shutdown()

        assertEquals(10, results.count { it.booked })
        assertEquals(30, results.count { it.reason == "Insufficient funds" })
        assertEquals(0, queries.balanceOf(a))
        assertEquals(100_00, queries.balanceOf(b))
    }

    @Test
    fun `opposing transfers do not deadlock`() {
        val a = fundedAccount(1_000_00)
        val b = fundedAccount(1_000_00)
        val pool = Executors.newFixedThreadPool(16)

        val results = pool.invokeAll(
            (1..60).map { i ->
                Callable {
                    if (i % 2 == 0) {
                        ledger.bookInternalTransfer(newTransfer(), a, b, 1_00)
                    } else {
                        ledger.bookInternalTransfer(newTransfer(), b, a, 1_00)
                    }
                }
            },
        ).map { it.get() }
        pool.shutdown()

        assertTrue(results.all { it.booked })
        assertEquals(2_000_00, queries.balanceOf(a) + queries.balanceOf(b))
    }

    @Test
    fun `external debit and refund restore the original balance`() {
        val a = fundedAccount(100_00)
        val t = newTransfer()

        assertTrue(ledger.debitForExternalTransfer(t, a, 40_00).booked)
        assertEquals(60_00, queries.balanceOf(a))
        assertEquals(TransferStatus.SENT_TO_EXTERNAL_BANK, status(t))

        repeat(2) { assertEquals(TransferStatus.REFUNDED, ledger.refundExternalTransfer(t, "rejected").status) }

        assertEquals(100_00, queries.balanceOf(a))
        assertEquals(TransferStatus.REFUNDED, status(t))
        val legs = queries.postingsOfTransfer(t).map { Triple(it.accountId, it.leg, it.amountCents) }
        assertEquals(
            listOf(
                Triple(a.value, "debit", -40_00L),
                Triple(SystemAccounts.EXTERNAL_CLEARING.value, "credit", 40_00L),
                Triple(a.value, "refund", 40_00L),
                Triple(SystemAccounts.EXTERNAL_CLEARING.value, "refund", -40_00L),
            ),
            legs,
        )
    }

    @Test
    fun `an accepted external transfer can no longer be refunded`() {
        val a = fundedAccount(100_00)
        val t = newTransfer()
        ledger.debitForExternalTransfer(t, a, 40_00)
        ledger.completeExternalTransfer(t)
        ledger.completeExternalTransfer(t) // idempotent

        assertThrows<IllegalTransferState> { ledger.refundExternalTransfer(t, "too late") }
        assertEquals(TransferStatus.COMPLETED, status(t))
    }

    @Test
    fun `an account with a remaining balance cannot be closed`() {
        val a = fundedAccount(5_00)
        assertEquals(BookingResult.rejected("Balance must be zero before closing"), ledger.closeAccount(a))

        ledger.bookInternalTransfer(newTransfer(), a, SystemAccounts.CASH, 5_00)
        assertTrue(ledger.closeAccount(a).booked)
        assertEquals("CLOSED", queries.account(a)?.status)
    }

    @Test
    fun `postings are immutable`() {
        val t = newTransfer()
        ledger.bookInternalTransfer(t, SystemAccounts.CASH, newAccount(), 1_00)

        assertThrows<Exception> {
            jdbc.sql("UPDATE postings SET amount_cents = 999 WHERE transfer_id = :t").param("t", t.value).update()
        }
        assertThrows<Exception> {
            jdbc.sql("DELETE FROM postings WHERE transfer_id = :t").param("t", t.value).update()
        }
    }

    @Test
    fun `the database rejects unbalanced transfers`() {
        val t = newTransfer()
        val a = newAccount()

        val error = assertThrows<Exception> {
            tx.executeWithoutResult {
                jdbc.sql("INSERT INTO transfers (id, status) VALUES (:t, 'RECEIVED')").param("t", t.value).update()
                jdbc.sql(
                    "INSERT INTO postings (transfer_id, account_id, leg, amount_cents, booked_at) VALUES (:t, :a, 'credit', 12345, now())",
                )
                    .param("t", t.value).param("a", a.value).update()
            }
        }
        assertTrue(queries.postingsOfTransfer(t).isEmpty())
        // The message ends up in logs: the transfer ID, but no amount.
        val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("transfer ${t.value} is unbalanced" in messages, messages)
        assertTrue("12345" !in messages, messages)
    }

    @Test
    fun `a transfer waiting for an approval is not booked, and its history shows every step`() {
        val a = fundedAccount(100_00)
        val b = newAccount()
        val t = newTransfer()
        ledger.receiveTransfer(TransferDetails(t.value, TransferKind.INTERNAL, a.value, b.value, 30_00))

        ledger.requireApproval(t, "Amount exceeds approval limit")
        assertEquals(TransferStatus.AWAITING_APPROVAL, status(t), "the waiting is visible in the ledger")
        assertThrows<IllegalTransferState> { ledger.bookInternalTransfer(t, a, b, 30_00) }
        assertTrue(queries.postingsOfTransfer(t).isEmpty())

        ledger.approve(t, "checked with the customer")
        assertEquals(TransferStatus.BOOKED, ledger.bookInternalTransfer(t, a, b, 30_00).status)

        assertEquals(
            listOf(
                TransferStatus.RECEIVED to null,
                TransferStatus.AWAITING_APPROVAL to "Amount exceeds approval limit",
                TransferStatus.APPROVED to "checked with the customer",
                TransferStatus.BOOKED to null,
            ),
            transfers.history(t).map { it.status to it.reason },
        )
    }

    @Test
    fun `a denied approval rejects the transfer with the comment`() {
        val t = newTransfer()
        ledger.receiveTransfer(TransferDetails(t.value, TransferKind.INTERNAL, newAccount().value, newAccount().value, 1_00))
        ledger.requireApproval(t, "Amount exceeds approval limit")

        assertEquals(TransferResult(TransferStatus.REJECTED, "Approval denied: phishing"), ledger.denyApproval(t, "phishing"))
        assertEquals(TransferResult(TransferStatus.REJECTED, "Approval denied: phishing"), ledger.denyApproval(t, "phishing"))
        assertEquals(
            listOf(TransferStatus.RECEIVED, TransferStatus.AWAITING_APPROVAL, TransferStatus.REJECTED),
            transfers.history(t).map { it.status },
            "denying twice changes nothing",
        )
    }

    @Test
    fun `the history is immutable`() {
        val t = newTransfer()
        ledger.bookInternalTransfer(t, SystemAccounts.CASH, newAccount(), 1_00)

        assertThrows<Exception> {
            jdbc.sql("UPDATE transfer_events SET status = 'REJECTED' WHERE transfer_id = :t").param("t", t.value).update()
        }
        assertThrows<Exception> { jdbc.sql("DELETE FROM transfer_events WHERE transfer_id = :t").param("t", t.value).update() }
    }

    /** Money moved: booked, sent to the external bank or refunded. */
    private val TransferResult.booked get() = status != TransferStatus.REJECTED

    private fun newTransfer() = TransferId(UUID.randomUUID())

    private fun newAccount(): AccountId {
        val id = AccountId(UUID.randomUUID())
        assertTrue(ledger.openAccount(id, customers.createPlaceholder()).booked)
        return id
    }

    private fun fundedAccount(cents: Long): AccountId {
        val id = newAccount()
        assertTrue(ledger.bookInternalTransfer(newTransfer(), SystemAccounts.CASH, id, cents).booked)
        return id
    }

    private fun status(t: TransferId) = jdbc.sql("SELECT status FROM transfers WHERE id = :t")
        .param("t", t.value)
        .query { rs, _ -> TransferStatus.valueOf(rs.getString(1)) }
        .single()
}
