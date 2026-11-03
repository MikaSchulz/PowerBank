package com.example.bank.ledger

import com.example.bank.domain.AccountId
import com.example.bank.domain.CustomerId
import com.example.bank.domain.TransferId
import com.example.bank.domain.account.Account
import com.example.bank.domain.account.AccountCommand
import com.example.bank.domain.account.AccountEvent
import com.example.bank.domain.account.AccountKind
import com.example.bank.domain.account.BlockAccount
import com.example.bank.domain.account.CloseAccount
import com.example.bank.domain.account.Credit
import com.example.bank.domain.account.Debit
import com.example.bank.domain.account.OpenAccount
import com.example.bank.domain.account.Rejection
import com.example.bank.domain.account.ReverseCredit
import com.example.bank.domain.account.ReverseDebit
import com.example.bank.domain.account.UnblockAccount
import com.example.bank.ledger.TransferRecordStatus.BOOKED
import com.example.bank.ledger.TransferRecordStatus.COMPLETED
import com.example.bank.ledger.TransferRecordStatus.DEBITED
import com.example.bank.ledger.TransferRecordStatus.PENDING
import com.example.bank.ledger.TransferRecordStatus.REFUNDED
import com.example.bank.ledger.TransferRecordStatus.REJECTED
import com.example.bank.observability.BankSpans
import com.fraktalio.fmodel.application.EventSourcingAggregate
import com.fraktalio.fmodel.application.handle
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.function.Supplier

/** Result of a booking. A plain data class so it can be serialized as an activity result. */
data class BookingResult(val booked: Boolean, val reason: String? = null) {
    companion object {
        val BOOKED = BookingResult(booked = true)
        fun rejected(reason: String) = BookingResult(booked = false, reason = reason)
    }
}

/**
 * The ledger: every method is exactly one database transaction.
 *
 * Business decisions are made by the fmodel aggregate. The ledger takes care of transactions,
 * lock ordering and idempotency (activities run at least once).
 */
@Component
class Ledger(
    private val aggregate: EventSourcingAggregate<AccountCommand, Account, AccountEvent>,
    private val transfers: TransferRecords,
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val observations: ObservationRegistry,
) {

    fun openAccount(accountId: AccountId, customerId: CustomerId, overdraftLimitCents: Long = 0): BookingResult =
        accountCommand(OpenAccount(accountId, customerId, AccountKind.CUSTOMER, overdraftLimitCents))

    fun blockAccount(accountId: AccountId) = accountCommand(BlockAccount(accountId))

    fun unblockAccount(accountId: AccountId) = accountCommand(UnblockAccount(accountId))

    fun closeAccount(accountId: AccountId) = accountCommand(CloseAccount(accountId))

    /** Internal transfer: debit and credit in one transaction. No saga needed. */
    fun bookInternalTransfer(
        transferId: TransferId,
        debtor: AccountId,
        creditor: AccountId,
        amountCents: Long,
    ): BookingResult {
        if (debtor == creditor) return reject(transferId, "Debtor and creditor account are identical")
        return bookPair(transferId, debtor, creditor, amountCents, onSuccess = BOOKED)
    }

    /** First step of the external bank saga: the customer is debited, the money sits on the clearing account. */
    fun debitForExternalTransfer(transferId: TransferId, debtor: AccountId, amountCents: Long): BookingResult =
        bookPair(transferId, debtor, SystemAccounts.EXTERNAL_CLEARING, amountCents, onSuccess = DEBITED)

    /** The external bank accepted: saga completed successfully. Idempotent. */
    fun completeExternalTransfer(transferId: TransferId) {
        tx.executeWithoutResult {
            val record = transfers.find(transferId) ?: error("Transfer $transferId does not exist")
            when (record.status) {
                COMPLETED -> Unit
                DEBITED -> transfers.transition(transferId, setOf(DEBITED), COMPLETED)
                else -> throw IllegalTransferState(transferId, record.status, "completed")
            }
        }
    }

    /**
     * Compensation of the external bank saga: refund the debit. Amount and account come from the ledger,
     * not from the workflow, because the ledger is the source of truth. Idempotent.
     */
    fun refundExternalTransfer(transferId: TransferId, reason: String?): BookingResult = inTransaction {
        val record = transfers.find(transferId) ?: error("Transfer $transferId does not exist")
        when (record.status) {
            REFUNDED -> return@inTransaction BookingResult.BOOKED
            DEBITED -> Unit
            else -> throw IllegalTransferState(transferId, record.status, "refunded")
        }
        val (debtor, amountCents) = debitOf(transferId)
        lockInOrder(debtor, SystemAccounts.EXTERNAL_CLEARING)
        handle(ReverseDebit(debtor, transferId, amountCents)).failIfRejected()
        handle(ReverseCredit(SystemAccounts.EXTERNAL_CLEARING, transferId, amountCents)).failIfRejected()
        transfers.transition(transferId, setOf(DEBITED), REFUNDED, reason)
        BookingResult.BOOKED
    }

    /** Marks a transfer that has not been booked yet as rejected, e.g. after a denied approval. */
    fun rejectTransfer(transferId: TransferId, reason: String) {
        reject(transferId, reason)
    }

    private fun bookPair(
        transferId: TransferId,
        debtor: AccountId,
        creditor: AccountId,
        amountCents: Long,
        onSuccess: TransferRecordStatus,
    ): BookingResult = try {
        inTransaction {
            transfers.createIfAbsent(transferId)
            // Idempotency: if a decision was already made, the earlier decision stands.
            earlierOutcome(transferId)?.let { return@inTransaction it }

            lockInOrder(debtor, creditor)
            handle(Debit(debtor, transferId, amountCents)).rejection()?.let {
                transfers.transition(transferId, setOf(PENDING), REJECTED, it.reason)
                return@inTransaction BookingResult.rejected(it.reason)
            }
            handle(Credit(creditor, transferId, amountCents)).rejection()?.let {
                throw BookingRejected(it.reason) // rollback: the debit is discarded too
            }
            transfers.transition(transferId, setOf(PENDING), onSuccess)
            BookingResult.BOOKED
        }
    } catch (e: BookingRejected) {
        reject(transferId, e.reason)
    }

    private fun reject(transferId: TransferId, reason: String): BookingResult = inTransaction {
        transfers.createIfAbsent(transferId)
        earlierOutcome(transferId)?.let { return@inTransaction it }
        transfers.transition(transferId, setOf(PENDING), REJECTED, reason)
        BookingResult.rejected(reason)
    }

    private fun earlierOutcome(transferId: TransferId): BookingResult? {
        val record = transfers.find(transferId) ?: return null
        return when (record.status) {
            PENDING -> null
            REJECTED -> BookingResult.rejected(record.reason ?: "rejected")
            else -> BookingResult.BOOKED
        }
    }

    private fun accountCommand(command: AccountCommand): BookingResult = inTransaction {
        handle(command).rejection()?.let { BookingResult.rejected(it.reason) } ?: BookingResult.BOOKED
    }

    /**
     * Always locks accounts in the same order (by ID), so that two concurrent
     * transfers A→B and B→A cannot block each other (deadlock).
     */
    private fun lockInOrder(first: AccountId, second: AccountId) {
        // Observed as span and timer "bank.ledger.lock": under contention (e.g. the burst scenario) the wait is visible.
        Observation.createNotStarted("bank.ledger.lock", observations)
            .highCardinalityKeyValue(BankSpans.ACCOUNT_IDS, listOf(first, second).sortedBy { it.value }.joinToString(","))
            .observe(
                Runnable {
                    jdbc.sql("SELECT id FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                        .param("ids", listOf(first.value, second.value))
                        .query()
                        .listOfRows()
                },
            )
    }

    private fun debitOf(transferId: TransferId): Pair<AccountId, Long> =
        jdbc.sql("SELECT account_id, amount_cents FROM postings WHERE transfer_id = :id AND leg = 'debit'")
            .param("id", transferId.value)
            .query { rs, _ -> AccountId(rs.getObject("account_id", UUID::class.java)) to -rs.getLong("amount_cents") }
            .single()

    /**
     * fmodel returns a `Flow`. `runBlocking` runs on the current thread, so the JDBC transaction
     * (bound to the thread) is preserved. Never use `withContext(Dispatchers.IO)` here.
     */
    private fun handle(command: AccountCommand): List<AccountEvent> =
        checkNotNull(
            Observation.createNotStarted("bank.account.command", observations)
                .lowCardinalityKeyValue("command", command::class.simpleName ?: "unknown")
                .highCardinalityKeyValue(BankSpans.ACCOUNT_ID, command.accountId.toString())
                .highCardinalityKeyValue(BankSpans.TRANSFER_ID, transferIdOf(command))
                .observe(Supplier { runBlocking { aggregate.handle(command).toList() } }),
        )

    private fun transferIdOf(command: AccountCommand): String = when (command) {
        is Debit -> command.transferId.toString()
        is Credit -> command.transferId.toString()
        is ReverseDebit -> command.transferId.toString()
        is ReverseCredit -> command.transferId.toString()
        else -> "none"
    }

    private fun <T> inTransaction(block: () -> T): T = tx.execute { block() }

    private fun List<AccountEvent>.rejection(): Rejection? = filterIsInstance<Rejection>().firstOrNull()

    private fun List<AccountEvent>.failIfRejected() {
        rejection()?.let { error("Compensation rejected: ${it.reason}") }
    }

    private class BookingRejected(val reason: String) : RuntimeException(reason, null, false, false)
}

class IllegalTransferState(transferId: TransferId, status: TransferRecordStatus, action: String) :
    IllegalStateException("Transfer $transferId in status $status cannot be $action")
