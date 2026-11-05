package com.example.bank.ledger

import com.example.bank.domain.AccountId
import com.example.bank.domain.Iban
import com.example.bank.domain.TransferId
import com.example.bank.domain.transfer.TransferStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class AccountView(
    val id: UUID,
    val status: String,
    val kind: String,
    val overdraftLimitCents: Long,
    val balanceCents: Long,
    /** Customer accounts only. Personal data: shown to people, never in telemetry. */
    val iban: String?,
)

/** Health of the ledger: with double-entry bookkeeping, the sum of all postings must be zero. */
data class LedgerSummary(
    val sumOfAllPostingsCents: Long,
    val postingCount: Long,
    val customerAccountCount: Long,
    val transferCount: Long,
)

data class PostingView(
    val id: Long,
    val transferId: UUID,
    val accountId: UUID,
    val leg: String,
    val amountCents: Long,
    val bookedAt: Instant,
)

/** Read-only queries. The balance is always the sum of the postings, never a stored value. */
@Component
class LedgerQueries(private val jdbc: JdbcClient) {

    /** All accounts with their balances: customer accounts first, then system accounts. */
    fun accounts(): List<AccountView> =
        jdbc.sql(
            """
            SELECT a.id, a.status, a.kind, a.overdraft_limit_cents, a.iban, COALESCE(SUM(p.amount_cents), 0) AS balance
            FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
            GROUP BY a.id
            ORDER BY a.kind, a.id
            """,
        )
            .query { rs, _ -> toAccountView(rs) }
            .list()

    fun summary(): LedgerSummary =
        jdbc.sql(
            """
            SELECT (SELECT COALESCE(SUM(amount_cents), 0) FROM postings) AS total,
                   (SELECT COUNT(*) FROM postings) AS postings,
                   (SELECT COUNT(*) FROM accounts WHERE kind = 'CUSTOMER') AS accounts,
                   (SELECT COUNT(*) FROM transfers) AS transfers
            """,
        )
            .query { rs, _ ->
                LedgerSummary(
                    sumOfAllPostingsCents = rs.getLong("total"),
                    postingCount = rs.getLong("postings"),
                    customerAccountCount = rs.getLong("accounts"),
                    transferCount = rs.getLong("transfers"),
                )
            }
            .single()

    fun account(id: AccountId): AccountView? = accountWhere("a.id = :key", id.value)

    /** The account an IBAN of this bank belongs to, e.g. for an incoming payment (#60). */
    fun accountByIban(iban: Iban): AccountView? = accountWhere("a.iban = :key", iban.value)

    private fun accountWhere(condition: String, key: Any): AccountView? =
        jdbc.sql(
            """
            SELECT a.id, a.status, a.kind, a.overdraft_limit_cents, a.iban,
                   COALESCE((SELECT SUM(p.amount_cents) FROM postings p WHERE p.account_id = a.id), 0) AS balance
            FROM accounts a WHERE $condition
            """,
        )
            .param("key", key)
            .query { rs, _ -> toAccountView(rs) }
            .optional()
            .orElse(null)

    fun balanceOf(id: AccountId): Long =
        jdbc.sql("SELECT COALESCE(SUM(amount_cents), 0) FROM postings WHERE account_id = :id")
            .param("id", id.value)
            .query(Long::class.java)
            .single()

    fun postingsOfAccount(id: AccountId): List<PostingView> = postings("account_id", id.value)

    fun postingsOfTransfer(id: TransferId): List<PostingView> = postings("transfer_id", id.value)

    /** Double-entry: the sum of all postings in the ledger is always zero. */
    fun sumOfAllPostings(): Long =
        jdbc.sql("SELECT COALESCE(SUM(amount_cents), 0) FROM postings")
            .query(Long::class.java)
            .single()

    /** Transfers in this status. */
    fun countTransfers(status: TransferStatus): Long =
        jdbc.sql("SELECT COUNT(*) FROM transfers WHERE status = :status")
            .param("status", status.name)
            .query(Long::class.java)
            .single()

    /** How long the transfer that has been in this status the longest has been in it, in seconds; 0 if there is none. */
    fun oldestTransferAgeSeconds(status: TransferStatus): Double =
        jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM now() - MIN(updated_at)), 0) FROM transfers WHERE status = :status")
            .param("status", status.name)
            .query(Double::class.java)
            .single()

    private fun toAccountView(rs: ResultSet) = AccountView(
        id = rs.getObject("id", UUID::class.java),
        status = rs.getString("status"),
        kind = rs.getString("kind"),
        overdraftLimitCents = rs.getLong("overdraft_limit_cents"),
        balanceCents = rs.getLong("balance"),
        iban = rs.getString("iban"),
    )

    private fun postings(column: String, id: UUID): List<PostingView> =
        jdbc.sql("SELECT id, transfer_id, account_id, leg, amount_cents, booked_at FROM postings WHERE $column = :id ORDER BY id")
            .param("id", id)
            .query { rs, _ ->
                PostingView(
                    id = rs.getLong("id"),
                    transferId = rs.getObject("transfer_id", UUID::class.java),
                    accountId = rs.getObject("account_id", UUID::class.java),
                    leg = rs.getString("leg"),
                    amountCents = rs.getLong("amount_cents"),
                    bookedAt = rs.getTimestamp("booked_at").toInstant(),
                )
            }
            .list()
}
