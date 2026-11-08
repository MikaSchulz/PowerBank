package com.example.bank.ledger

import com.example.bank.config.BankIdentity
import com.example.bank.domain.AccountId
import com.example.bank.domain.CustomerId
import com.example.bank.domain.Iban
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
import com.example.bank.domain.transfer.AmountReserved
import com.example.bank.domain.transfer.Approve
import com.example.bank.domain.transfer.BookTransfer
import com.example.bank.domain.transfer.CompleteTransfer
import com.example.bank.domain.transfer.DenyApproval
import com.example.bank.domain.transfer.ExpireApproval
import com.example.bank.domain.transfer.ReceiveTransfer
import com.example.bank.domain.transfer.RefundTransfer
import com.example.bank.domain.transfer.RejectTransfer
import com.example.bank.domain.transfer.RequireApproval
import com.example.bank.domain.transfer.ReserveAmount
import com.example.bank.domain.transfer.SendToExternalBank
import com.example.bank.domain.transfer.SettleTransfer
import com.example.bank.domain.transfer.StartInvestigation
import com.example.bank.domain.transfer.Transfer
import com.example.bank.domain.transfer.TransferBooked
import com.example.bank.domain.transfer.TransferCommand
import com.example.bank.domain.transfer.TransferCommandRejected
import com.example.bank.domain.transfer.TransferEvent
import com.example.bank.domain.transfer.TransferKind
import com.example.bank.domain.transfer.TransferRefunded
import com.example.bank.domain.transfer.TransferSettled
import com.example.bank.domain.transfer.TransferStatus
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

/** Result of an account command. A plain data class so it can be serialized as an activity result. */
data class BookingResult(val booked: Boolean, val reason: String? = null) {
    companion object {
        val BOOKED = BookingResult(booked = true)
        fun rejected(reason: String) = BookingResult(booked = false, reason = reason)
    }
}

/** A transfer's business status after a ledger operation, as the transfer decider decided it. */
data class TransferResult(val status: TransferStatus, val reason: String? = null)

/**
 * The ledger: every method is exactly one database transaction.
 *
 * Business decisions are made by the fmodel aggregates: the transfer decider decides which step a transfer may take,
 * the account decider whether the money may move. The ledger runs both in one transaction and takes care of lock
 * ordering (always the transfer first, then the accounts sorted by ID) and idempotency (activities run at least once).
 */
@Component
class Ledger(
    private val accounts: EventSourcingAggregate<AccountCommand, Account, AccountEvent>,
    private val transferAggregate: EventSourcingAggregate<TransferCommand, Transfer, TransferEvent>,
    private val transfers: TransferRecords,
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val observations: ObservationRegistry,
    private val identity: BankIdentity,
) {

    /** Opens a customer account with the next IBAN of this bank. */
    fun openAccount(accountId: AccountId, customerId: CustomerId, overdraftLimitCents: Long = 0): BookingResult = inTransaction {
        val iban = Iban.german(identity.bankCode, nextAccountNumber())
        accountCommand(OpenAccount(accountId, customerId, AccountKind.CUSTOMER, overdraftLimitCents, iban))
    }

    // From a sequence: unique without locks. Numbers of a rolled-back opening are skipped, as in a real bank.
    private fun nextAccountNumber(): Long = jdbc.sql("SELECT nextval('account_number_seq')").query(Long::class.java).single()

    fun blockAccount(accountId: AccountId) = accountCommand(BlockAccount(accountId))

    fun unblockAccount(accountId: AccountId) = accountCommand(UnblockAccount(accountId))

    fun closeAccount(accountId: AccountId) = accountCommand(CloseAccount(accountId))

    /** The bank accepts a transfer: status RECEIVED. A second call with the same transfer changes nothing. */
    fun receiveTransfer(details: TransferDetails): TransferResult = inTransaction {
        val id = TransferId(details.id)
        handleTransfer(
            ReceiveTransfer(
                id,
                details.kind,
                AccountId(details.debtorAccountId),
                details.creditorAccountId?.let(::AccountId),
                details.amountCents,
            ),
        ).failIfRejected(id)
        transfers.attachTraceId(id, details.traceId)
        current(id)
    }

    fun requireApproval(transferId: TransferId, reason: String) = changeTransfer(RequireApproval(transferId, reason))

    fun approve(transferId: TransferId, comment: String?) = changeTransfer(Approve(transferId, comment))

    fun denyApproval(transferId: TransferId, comment: String?) = changeTransfer(DenyApproval(transferId, comment))

    fun expireApproval(transferId: TransferId) = changeTransfer(ExpireApproval(transferId))

    /** Rejects a transfer that has not been booked yet. */
    fun rejectTransfer(transferId: TransferId, reason: String) = changeTransfer(RejectTransfer(transferId, reason))

    /** Internal transfer or deposit: debit and credit in one transaction. No saga needed. */
    fun bookInternalTransfer(transferId: TransferId, debtor: AccountId, creditor: AccountId, amountCents: Long): TransferResult {
        val kind = if (debtor == SystemAccounts.CASH) TransferKind.DEPOSIT else TransferKind.INTERNAL
        return book(
            BookTransfer(transferId),
            ReceiveTransfer(transferId, kind, debtor, creditor, amountCents),
            debtor,
            creditor,
            amountCents,
        )
    }

    /**
     * SEPA Instant, first step: the customer is debited, the amount is reserved on the clearing account until the
     * payment's final status is known. Insufficient funds reject the transfer instead.
     */
    fun reserveForExternalTransfer(transferId: TransferId, debtor: AccountId, amountCents: Long): TransferResult = book(
        ReserveAmount(transferId),
        ReceiveTransfer(transferId, TransferKind.EXTERNAL, debtor, null, amountCents),
        debtor,
        SystemAccounts.EXTERNAL_CLEARING,
        amountCents,
    )

    /** The clearing house has the payment (it acknowledged the pacs.008). No money moves. Idempotent. */
    fun markSentToExternalBank(transferId: TransferId) = changeTransfer(SendToExternalBank(transferId))

    /**
     * The clearing house confirmed the settlement (ACSC): the reserved amount goes from the clearing account to the
     * settlement account, which mirrors the bank's account at the clearing house. Idempotent.
     */
    fun settleExternalTransfer(transferId: TransferId): TransferResult = inTransaction {
        val events = handleTransfer(SettleTransfer(transferId)).failIfRejected(transferId)
        if (events.any { it is TransferSettled }) {
            val (_, amountCents) = debitOf(transferId)
            lockInOrder(SystemAccounts.EXTERNAL_CLEARING, SystemAccounts.SETTLEMENT)
            handle(Debit(SystemAccounts.EXTERNAL_CLEARING, transferId, amountCents)).failIfRejected()
            handle(Credit(SystemAccounts.SETTLEMENT, transferId, amountCents)).failIfRejected()
        }
        current(transferId)
    }

    /** No final status from the clearing house: the amount stays reserved, the back office sees it. Idempotent. */
    fun startInvestigation(transferId: TransferId, reason: String) = changeTransfer(StartInvestigation(transferId, reason))

    /**
     * Transfers started before SEPA Instant (simulated external bank): reserve and send in one step. Each part is
     * idempotent, so a retry after either of them is safe.
     */
    fun debitForExternalTransfer(transferId: TransferId, debtor: AccountId, amountCents: Long): TransferResult {
        val reserved = reserveForExternalTransfer(transferId, debtor, amountCents)
        return if (reserved.status == TransferStatus.RESERVED) markSentToExternalBank(transferId) else reserved
    }

    /** The simulated external bank accepted (transfers started before SEPA Instant). Idempotent. */
    fun completeExternalTransfer(transferId: TransferId) = changeTransfer(CompleteTransfer(transferId))

    /**
     * The payment was rejected, or never reached the clearing house: refund the reserved amount. Amount and account
     * come from the ledger, not from the workflow, because the ledger is the source of truth. Idempotent.
     */
    fun refundExternalTransfer(transferId: TransferId, reason: String): TransferResult = inTransaction {
        val events = handleTransfer(RefundTransfer(transferId, reason)).failIfRejected(transferId)
        if (events.any { it is TransferRefunded }) {
            val (debtor, amountCents) = debitOf(transferId)
            lockInOrder(debtor, SystemAccounts.EXTERNAL_CLEARING)
            handle(ReverseDebit(debtor, transferId, amountCents)).failIfRejected()
            handle(ReverseCredit(SystemAccounts.EXTERNAL_CLEARING, transferId, amountCents)).failIfRejected()
        }
        current(transferId)
    }

    /**
     * A booking step and its money in one transaction: first the transfer decider decides whether the step is allowed
     * (or already happened), then the account decider books debit and credit. If an account rejects, e.g. for
     * insufficient funds, everything is rolled back and the transfer is rejected instead.
     */
    private fun book(
        step: TransferCommand,
        receive: ReceiveTransfer,
        debtor: AccountId,
        creditor: AccountId,
        amountCents: Long,
    ): TransferResult = try {
        inTransaction {
            val id = step.transferId
            handleTransfer(receive).failIfRejected(id) // normally received by the API already; then this changes nothing
            // An earlier attempt was rejected (e.g. insufficient funds): that decision stands, also on a retry.
            current(id).takeIf { it.status == TransferStatus.REJECTED }?.let { return@inTransaction it }

            val events = handleTransfer(step).failIfRejected(id)
            if (events.any { it is TransferBooked || it is AmountReserved }) {
                lockInOrder(debtor, creditor)
                handle(Debit(debtor, id, amountCents)).rejection()?.let { throw BookingRejected(it.reason) }
                handle(Credit(creditor, id, amountCents)).rejection()?.let { throw BookingRejected(it.reason) }
            }
            current(id) // booked now, booked before (retry), or rejected by the transfer decider
        }
    } catch (e: BookingRejected) {
        // The rollback discarded the step and the debit, and the receipt too if it happened in that transaction.
        inTransaction {
            handleTransfer(receive).failIfRejected(step.transferId)
            handleTransfer(RejectTransfer(step.transferId, e.reason)).failIfRejected(step.transferId)
            current(step.transferId)
        }
    }

    private fun changeTransfer(command: TransferCommand): TransferResult = inTransaction {
        handleTransfer(command).failIfRejected(command.transferId)
        current(command.transferId)
    }

    private fun current(id: TransferId): TransferResult {
        val record = transfers.find(id) ?: error("Transfer $id does not exist")
        return TransferResult(record.status, record.reason)
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

    /** The debtor's debit. (Once settled, the clearing account has a debit for the transfer too.) */
    private fun debitOf(transferId: TransferId): Pair<AccountId, Long> =
        jdbc.sql("SELECT account_id, amount_cents FROM postings WHERE transfer_id = :id AND leg = 'debit' AND account_id <> :clearing")
            .param("id", transferId.value)
            .param("clearing", SystemAccounts.EXTERNAL_CLEARING.value)
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
                .observe(Supplier { runBlocking { accounts.handle(command).toList() } }),
        )

    /** Same as [handle] for the transfer's lifecycle: span and timer "bank.transfer.command". */
    private fun handleTransfer(command: TransferCommand): List<TransferEvent> =
        checkNotNull(
            Observation.createNotStarted("bank.transfer.command", observations)
                .lowCardinalityKeyValue("command", command::class.simpleName ?: "unknown")
                .highCardinalityKeyValue(BankSpans.TRANSFER_ID, command.transferId.toString())
                .observe(Supplier { runBlocking { transferAggregate.handle(command).toList() } }),
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

    /** For postings that must not fail for business reasons: refunds and the settlement of a reserved amount. */
    private fun List<AccountEvent>.failIfRejected() {
        rejection()?.let { error("Posting rejected: ${it.reason}") }
    }

    /** A step the transfer's status doesn't allow is a bug in the caller, not a business outcome. */
    private fun List<TransferEvent>.failIfRejected(id: TransferId): List<TransferEvent> {
        filterIsInstance<TransferCommandRejected>().firstOrNull()?.let { throw IllegalTransferState(id, it.reason) }
        return this
    }

    private class BookingRejected(val reason: String) : RuntimeException(reason, null, false, false)
}

class IllegalTransferState(transferId: TransferId, reason: String) : IllegalStateException("Transfer $transferId: $reason")
