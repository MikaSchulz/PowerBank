package com.example.bank.ledger

import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId
import com.example.bank.domain.transfer.AmountReserved
import com.example.bank.domain.transfer.ApprovalRequired
import com.example.bank.domain.transfer.InvestigationStarted
import com.example.bank.domain.transfer.TransferApproved
import com.example.bank.domain.transfer.TransferBooked
import com.example.bank.domain.transfer.TransferCommand
import com.example.bank.domain.transfer.TransferCommandRejected
import com.example.bank.domain.transfer.TransferCompleted
import com.example.bank.domain.transfer.TransferEvent
import com.example.bank.domain.transfer.TransferKind
import com.example.bank.domain.transfer.TransferReceived
import com.example.bank.domain.transfer.TransferRefunded
import com.example.bank.domain.transfer.TransferRejected
import com.example.bank.domain.transfer.TransferSentToExternalBank
import com.example.bank.domain.transfer.TransferSettled
import com.example.bank.domain.transfer.TransferStatusChanged
import com.fraktalio.fmodel.application.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The events of a transfer, stored like the account's postings:
 *
 * - [TransferReceived] is the `transfers` row itself (with the details the API accepted);
 * - every later event is a row in `transfer_events`, and `transfers.status`/`reason` always show the latest one, so
 *   listings, dashboards and alerts read the current business status with plain SQL;
 * - rejected commands are not stored.
 *
 * Loading locks the transfer row (`SELECT … FOR UPDATE`): two attempts of the same activity are serialized.
 * Must run inside a transaction and on its thread.
 */
@Component
class TransferEventRepository(private val jdbc: JdbcClient) : EventRepository<TransferCommand, TransferEvent> {

    override fun TransferCommand.fetchEvents(): Flow<TransferEvent> = flow {
        val received = lockTransfer(transferId) ?: return@flow
        emit(received)
        history(transferId).forEach { emit(it) }
    }

    override fun Flow<TransferEvent>.save(): Flow<TransferEvent> = onEach(::persist)

    private fun lockTransfer(id: TransferId): TransferReceived? =
        jdbc.sql("SELECT kind, debtor_account_id, creditor_account_id, amount_cents FROM transfers WHERE id = :id FOR UPDATE")
            .param("id", id.value)
            .query { rs, _ ->
                TransferReceived(
                    transferId = id,
                    kind = rs.getString("kind")?.let(TransferKind::valueOf),
                    debtorAccountId = rs.getObject("debtor_account_id", UUID::class.java)?.let(::AccountId),
                    creditorAccountId = rs.getObject("creditor_account_id", UUID::class.java)?.let(::AccountId),
                    amountCents = rs.getLong("amount_cents"),
                )
            }
            .optional()
            .orElse(null)

    private fun history(id: TransferId): List<TransferEvent> =
        jdbc.sql("SELECT type, reason FROM transfer_events WHERE transfer_id = :id ORDER BY id")
            .param("id", id.value)
            .query { rs, _ -> toEvent(id, rs.getString("type"), rs.getString("reason")) }
            .list()

    private fun persist(event: TransferEvent) {
        when (event) {
            is TransferReceived -> jdbc.sql(
                """
                INSERT INTO transfers (id, status, kind, debtor_account_id, creditor_account_id, amount_cents)
                VALUES (:id, 'RECEIVED', :kind, :debtor, :creditor, :amount)
                ON CONFLICT (id) DO NOTHING
                """,
            )
                .param("id", event.transferId.value)
                .param("kind", event.kind?.name)
                .param("debtor", event.debtorAccountId?.value)
                .param("creditor", event.creditorAccountId?.value)
                .param("amount", event.amountCents)
                .update()

            is TransferStatusChanged -> {
                jdbc.sql("INSERT INTO transfer_events (transfer_id, type, status, reason) VALUES (:id, :type, :status, :reason)")
                    .param("id", event.transferId.value)
                    .param("type", event::class.simpleName)
                    .param("status", event.status.name)
                    .param("reason", event.reason)
                    .update()
                jdbc.sql("UPDATE transfers SET status = :status, reason = :reason, updated_at = now() WHERE id = :id")
                    .param("status", event.status.name)
                    .param("reason", event.reason)
                    .param("id", event.transferId.value)
                    .update()
            }

            is TransferCommandRejected -> Unit
        }
    }

    companion object {
        fun toEvent(id: TransferId, type: String, reason: String?): TransferEvent = when (type) {
            "ApprovalRequired" -> ApprovalRequired(id, reason.orEmpty())
            "TransferApproved" -> TransferApproved(id, reason)
            "TransferBooked" -> TransferBooked(id)
            "AmountReserved" -> AmountReserved(id)
            "TransferSentToExternalBank" -> TransferSentToExternalBank(id)
            "InvestigationStarted" -> InvestigationStarted(id, reason.orEmpty())
            "TransferSettled" -> TransferSettled(id)
            "TransferCompleted" -> TransferCompleted(id)
            "TransferRefunded" -> TransferRefunded(id, reason.orEmpty())
            "TransferRejected" -> TransferRejected(id, reason.orEmpty())
            else -> error("Unknown transfer event: $type")
        }
    }
}
