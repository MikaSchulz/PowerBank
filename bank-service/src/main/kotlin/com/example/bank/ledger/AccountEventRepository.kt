package com.example.bank.ledger

import com.example.bank.domain.AccountId
import com.example.bank.domain.CustomerId
import com.example.bank.domain.Iban
import com.example.bank.domain.TransferId
import com.example.bank.domain.account.AccountBlocked
import com.example.bank.domain.account.AccountClosed
import com.example.bank.domain.account.AccountCommand
import com.example.bank.domain.account.AccountEvent
import com.example.bank.domain.account.AccountKind
import com.example.bank.domain.account.AccountOpened
import com.example.bank.domain.account.AccountStatus
import com.example.bank.domain.account.AccountUnblocked
import com.example.bank.domain.account.CreditReversed
import com.example.bank.domain.account.Credited
import com.example.bank.domain.account.DebitReversed
import com.example.bank.domain.account.Debited
import com.example.bank.domain.account.PostingEvent
import com.example.bank.domain.account.Rejection
import com.fraktalio.fmodel.application.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The postings in the ledger *are* the events of the account.
 *
 * - Loading: the account row is locked with `SELECT … FOR UPDATE` and returned as [AccountOpened] plus
 *   the current status, followed by all postings in order as money events.
 * - Saving: money events become postings, status events change `accounts.status`,
 *   rejections are not stored.
 *
 * Must run inside a transaction and on that transaction's thread (no dispatcher switch).
 */
@Component
class AccountEventRepository(private val jdbc: JdbcClient) : EventRepository<AccountCommand, AccountEvent> {

    override fun AccountCommand.fetchEvents(): Flow<AccountEvent> = flow {
        val row = lockAccount(accountId) ?: return@flow
        emit(AccountOpened(accountId, row.customerId, row.kind, row.overdraftLimitCents, row.iban))
        when (row.status) {
            AccountStatus.BLOCKED -> emit(AccountBlocked(accountId))
            AccountStatus.CLOSED -> emit(AccountClosed(accountId))
            AccountStatus.OPEN -> Unit
        }
        postingsOf(accountId).forEach { emit(it) }
    }

    override fun Flow<AccountEvent>.save(): Flow<AccountEvent> = onEach(::persist)

    private fun lockAccount(id: AccountId): AccountRow? =
        jdbc.sql("SELECT customer_id, status, kind, overdraft_limit_cents, iban FROM accounts WHERE id = :id FOR UPDATE")
            .param("id", id.value)
            .query { rs, _ ->
                AccountRow(
                    customerId = rs.getObject("customer_id", UUID::class.java)?.let(::CustomerId),
                    status = AccountStatus.valueOf(rs.getString("status")),
                    kind = AccountKind.valueOf(rs.getString("kind")),
                    overdraftLimitCents = rs.getLong("overdraft_limit_cents"),
                    iban = rs.getString("iban")?.let(Iban::parse),
                )
            }
            .optional()
            .orElse(null)

    private fun postingsOf(id: AccountId): List<PostingEvent> =
        jdbc.sql("SELECT transfer_id, leg, amount_cents FROM postings WHERE account_id = :id ORDER BY id")
            .param("id", id.value)
            .query { rs, _ ->
                toEvent(id, TransferId(rs.getObject("transfer_id", UUID::class.java)), rs.getString("leg"), rs.getLong("amount_cents"))
            }
            .list()

    private fun persist(event: AccountEvent) {
        when (event) {
            is AccountOpened -> jdbc.sql(
                """
                INSERT INTO accounts (id, customer_id, status, kind, overdraft_limit_cents, iban)
                VALUES (:id, :customerId, 'OPEN', :kind, :overdraft, :iban)
                """,
            )
                .param("id", event.accountId.value)
                .param("customerId", event.customerId?.value)
                .param("kind", event.kind.name)
                .param("overdraft", event.overdraftLimitCents)
                .param("iban", event.iban?.value)
                .update()

            is PostingEvent -> jdbc.sql(
                """
                INSERT INTO postings (transfer_id, account_id, leg, amount_cents, booked_at)
                VALUES (:transferId, :accountId, :leg, :amount, now())
                ON CONFLICT (transfer_id, account_id, leg) DO NOTHING
                """,
            )
                .param("transferId", event.transferId.value)
                .param("accountId", event.accountId.value)
                .param("leg", legOf(event))
                .param("amount", event.signedAmountCents)
                .update()

            is AccountBlocked -> updateStatus(event.accountId, AccountStatus.BLOCKED)
            is AccountUnblocked -> updateStatus(event.accountId, AccountStatus.OPEN)
            is AccountClosed -> updateStatus(event.accountId, AccountStatus.CLOSED)
            is Rejection -> Unit
        }
    }

    private fun updateStatus(id: AccountId, status: AccountStatus) {
        jdbc.sql("UPDATE accounts SET status = :status WHERE id = :id")
            .param("status", status.name)
            .param("id", id.value)
            .update()
    }

    private data class AccountRow(
        val customerId: CustomerId?,
        val status: AccountStatus,
        val kind: AccountKind,
        val overdraftLimitCents: Long,
        val iban: Iban?,
    )

    companion object {
        fun legOf(event: PostingEvent): String = when (event) {
            is Debited -> "debit"
            is Credited -> "credit"
            is DebitReversed, is CreditReversed -> "refund"
        }

        fun toEvent(account: AccountId, transfer: TransferId, leg: String, signedAmountCents: Long): PostingEvent =
            when (leg) {
                "debit" -> Debited(account, transfer, -signedAmountCents)
                "credit" -> Credited(account, transfer, signedAmountCents)
                "refund" ->
                    if (signedAmountCents > 0) {
                        DebitReversed(account, transfer, signedAmountCents)
                    } else {
                        CreditReversed(account, transfer, -signedAmountCents)
                    }
                else -> error("Unknown posting leg: $leg")
            }
    }
}
