package com.example.bank.ledger

import com.example.bank.domain.TransferId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Status of a transfer in the ledger. Workflow-internal states (e.g. "awaiting approval") live only in the workflow. */
enum class TransferRecordStatus {
    /** Created, not booked yet. */
    PENDING,

    /** Internal transfer booked. */
    BOOKED,

    /** External transfer: account debited, the money sits on the clearing account. */
    DEBITED,

    /** The external bank accepted. */
    COMPLETED,

    /** The external bank rejected, the debit was refunded. */
    REFUNDED,

    /** Rejected, nothing booked. */
    REJECTED,
}

data class TransferRecord(val id: UUID, val status: TransferRecordStatus, val reason: String?)

enum class TransferKind { INTERNAL, EXTERNAL, DEPOSIT }

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

data class TransferListItem(
    val details: TransferDetails?,
    val id: UUID,
    val status: TransferRecordStatus,
    val reason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Component
class TransferRecords(private val jdbc: JdbcClient) {

    /** Creates the transfer if it does not exist yet. Waits for a concurrent transaction if necessary. */
    fun createIfAbsent(id: TransferId) {
        jdbc.sql("INSERT INTO transfers (id, status) VALUES (:id, 'PENDING') ON CONFLICT (id) DO NOTHING")
            .param("id", id.value)
            .update()
    }

    /** Registers an accepted transfer with its details. A second registration with the same ID changes nothing. */
    fun register(details: TransferDetails) {
        jdbc.sql(
            """
            INSERT INTO transfers (id, status, kind, debtor_account_id, creditor_account_id, amount_cents, trace_id)
            VALUES (:id, 'PENDING', :kind, :debtor, :creditor, :amount, :traceId)
            ON CONFLICT (id) DO NOTHING
            """,
        )
            .param("id", details.id)
            .param("kind", details.kind.name)
            .param("debtor", details.debtorAccountId)
            .param("creditor", details.creditorAccountId)
            .param("amount", details.amountCents)
            .param("traceId", details.traceId)
            .update()
    }

    /** Removes a registration whose workflow never started. Only possible while nothing has been booked. */
    fun discardIfPending(id: TransferId) {
        jdbc.sql(
            """
            DELETE FROM transfers t
            WHERE t.id = :id AND t.status = 'PENDING' AND NOT EXISTS (SELECT 1 FROM postings p WHERE p.transfer_id = t.id)
            """,
        )
            .param("id", id.value)
            .update()
    }

    /** Newest first; optionally only transfers where the account is debtor or creditor. */
    fun list(accountId: UUID?, limit: Int): List<TransferListItem> =
        jdbc.sql(
            """
            SELECT id, status, reason, kind, debtor_account_id, creditor_account_id, amount_cents, trace_id, created_at, updated_at
            FROM transfers
            WHERE CAST(:account AS UUID) IS NULL OR debtor_account_id = :account OR creditor_account_id = :account
            ORDER BY created_at DESC, id
            LIMIT :limit
            """,
        )
            .param("account", accountId)
            .param("limit", limit)
            .query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val kind = rs.getString("kind")
                TransferListItem(
                    details = kind?.let {
                        TransferDetails(
                            id = id,
                            kind = TransferKind.valueOf(it),
                            debtorAccountId = rs.getObject("debtor_account_id", UUID::class.java),
                            creditorAccountId = rs.getObject("creditor_account_id", UUID::class.java),
                            amountCents = rs.getLong("amount_cents"),
                            traceId = rs.getString("trace_id"),
                        )
                    },
                    id = id,
                    status = TransferRecordStatus.valueOf(rs.getString("status")),
                    reason = rs.getString("reason"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }
            .list()

    fun find(id: TransferId): TransferRecord? =
        jdbc.sql("SELECT id, status, reason FROM transfers WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ ->
                TransferRecord(
                    id = rs.getObject("id", UUID::class.java),
                    status = TransferRecordStatus.valueOf(rs.getString("status")),
                    reason = rs.getString("reason"),
                )
            }
            .optional()
            .orElse(null)

    /** Sets the status only if the transfer is currently in one of the expected states. */
    fun transition(id: TransferId, from: Set<TransferRecordStatus>, to: TransferRecordStatus, reason: String? = null): Boolean =
        jdbc.sql(
            """
            UPDATE transfers SET status = :to, reason = :reason, updated_at = now()
            WHERE id = :id AND status IN (:from)
            """,
        )
            .param("to", to.name)
            .param("reason", reason)
            .param("id", id.value)
            .param("from", from.map { it.name })
            .update() == 1
}
