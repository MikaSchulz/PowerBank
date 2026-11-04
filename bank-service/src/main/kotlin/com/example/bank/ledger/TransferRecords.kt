package com.example.bank.ledger

import com.example.bank.domain.TransferId
import com.example.bank.domain.transfer.TransferKind
import com.example.bank.domain.transfer.TransferStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** What the API knows when it accepts a transfer. Pseudonymous: the external IBAN is not stored here. */
data class TransferDetails(
    val id: UUID,
    val kind: TransferKind,
    val debtorAccountId: UUID,
    val creditorAccountId: UUID?,
    val amountCents: Long,
    /** W3C trace ID of the accepting request, if it was sampled. */
    val traceId: String? = null,
)

/** A transfer as the ledger knows it: its business status and, if the API accepted it, its details. */
data class TransferRecord(
    val id: UUID,
    val status: TransferStatus,
    val reason: String?,
    val details: TransferDetails?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** One step in a transfer's business history. */
data class TransferHistoryEntry(val status: TransferStatus, val reason: String?, val at: Instant)

/** Read access to the transfers; every change goes through the transfer decider ([Ledger]). */
@Component
class TransferRecords(private val jdbc: JdbcClient) {

    /** Remembers the trace of the request that accepted the transfer. Not business data, so not an event. */
    fun attachTraceId(id: TransferId, traceId: String?) {
        if (traceId == null) return
        jdbc.sql("UPDATE transfers SET trace_id = :traceId WHERE id = :id AND trace_id IS NULL")
            .param("traceId", traceId)
            .param("id", id.value)
            .update()
    }

    /** Removes a received transfer whose workflow never started. Only possible while nothing else has happened to it. */
    fun discardIfReceived(id: TransferId) {
        jdbc.sql(
            """
            DELETE FROM transfers t
            WHERE t.id = :id AND t.status = 'RECEIVED'
              AND NOT EXISTS (SELECT 1 FROM postings p WHERE p.transfer_id = t.id)
              AND NOT EXISTS (SELECT 1 FROM transfer_events e WHERE e.transfer_id = t.id)
            """,
        )
            .param("id", id.value)
            .update()
    }

    /** Newest first; optionally only transfers where the account is debtor or creditor. */
    fun list(accountId: UUID?, limit: Int): List<TransferRecord> =
        jdbc.sql(
            """
            SELECT $COLUMNS
            FROM transfers
            WHERE CAST(:account AS UUID) IS NULL OR debtor_account_id = :account OR creditor_account_id = :account
            ORDER BY created_at DESC, id
            LIMIT :limit
            """,
        )
            .param("account", accountId)
            .param("limit", limit)
            .query { rs, _ -> toRecord(rs) }
            .list()

    fun find(id: TransferId): TransferRecord? =
        jdbc.sql("SELECT $COLUMNS FROM transfers WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> toRecord(rs) }
            .optional()
            .orElse(null)

    /** The business history: received, then every status change, oldest first. Empty if the transfer doesn't exist. */
    fun history(id: TransferId): List<TransferHistoryEntry> =
        jdbc.sql(
            """
            SELECT 'RECEIVED' AS status, NULL AS reason, created_at AS at, 0 AS seq FROM transfers WHERE id = :id
            UNION ALL
            SELECT status, reason, occurred_at, id FROM transfer_events WHERE transfer_id = :id
            ORDER BY seq
            """,
        )
            .param("id", id.value)
            .query { rs, _ ->
                TransferHistoryEntry(
                    status = TransferStatus.valueOf(rs.getString("status")),
                    reason = rs.getString("reason"),
                    at = rs.getTimestamp("at").toInstant(),
                )
            }
            .list()

    private fun toRecord(rs: ResultSet): TransferRecord {
        val id = rs.getObject("id", UUID::class.java)
        return TransferRecord(
            id = id,
            status = TransferStatus.valueOf(rs.getString("status")),
            reason = rs.getString("reason"),
            details = rs.getString("kind")?.let {
                TransferDetails(
                    id = id,
                    kind = TransferKind.valueOf(it),
                    debtorAccountId = rs.getObject("debtor_account_id", UUID::class.java),
                    creditorAccountId = rs.getObject("creditor_account_id", UUID::class.java),
                    amountCents = rs.getLong("amount_cents"),
                    traceId = rs.getString("trace_id"),
                )
            },
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private companion object {
        const val COLUMNS =
            "id, status, reason, kind, debtor_account_id, creditor_account_id, amount_cents, trace_id, created_at, updated_at"
    }
}
