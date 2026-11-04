package com.example.bank.domain.transfer

import com.example.bank.domain.transfer.TransferStatus.APPROVED
import com.example.bank.domain.transfer.TransferStatus.AWAITING_APPROVAL
import com.example.bank.domain.transfer.TransferStatus.BOOKED
import com.example.bank.domain.transfer.TransferStatus.COMPLETED
import com.example.bank.domain.transfer.TransferStatus.RECEIVED
import com.example.bank.domain.transfer.TransferStatus.REFUNDED
import com.example.bank.domain.transfer.TransferStatus.REJECTED
import com.example.bank.domain.transfer.TransferStatus.SENT_TO_EXTERNAL_BANK
import com.fraktalio.fmodel.domain.Decider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The lifecycle of a transfer as an fmodel decider: which status changes are allowed, and with which reason.
 * The money itself is the account decider's job; the ledger runs both in one database transaction.
 *
 * Repeating a command that already happened changes nothing (empty result): activities run at least once.
 * Runs only in activities, never in workflow code.
 */
val transferDecider: Decider<TransferCommand, Transfer, TransferEvent> = Decider(
    initialState = Transfer(),
    decide = { command, transfer -> decide(command, transfer) },
    evolve = { transfer, event -> evolve(transfer, event) },
)

/** Statuses in which nothing has been booked or decided yet, so the transfer can still go either way. */
private val OPEN = setOf(RECEIVED, APPROVED)

internal fun decide(c: TransferCommand, s: Transfer): Flow<TransferEvent> {
    if (c !is ReceiveTransfer && !s.exists) return reject(c, "Transfer does not exist")
    return when (c) {
        is ReceiveTransfer -> when {
            s.exists -> emptyFlow()
            c.amountCents <= 0 -> reject(c, "Amount must be positive")
            else -> flowOf(TransferReceived(c.transferId, c.kind, c.debtorAccountId, c.creditorAccountId, c.amountCents))
        }

        is RequireApproval -> when (s.status) {
            RECEIVED -> flowOf(ApprovalRequired(c.transferId, c.reason))
            AWAITING_APPROVAL -> emptyFlow()
            else -> reject(c, "Transfer in status ${s.status} cannot wait for an approval")
        }

        is Approve -> when (s.status) {
            AWAITING_APPROVAL -> flowOf(TransferApproved(c.transferId, c.comment))
            APPROVED -> emptyFlow()
            else -> reject(c, "Transfer is not awaiting approval (status ${s.status})")
        }

        is DenyApproval -> when (s.status) {
            AWAITING_APPROVAL -> flowOf(TransferRejected(c.transferId, "Approval denied" + (c.comment?.let { ": $it" } ?: "")))
            REJECTED -> emptyFlow()
            else -> reject(c, "Transfer is not awaiting approval (status ${s.status})")
        }

        is ExpireApproval -> when (s.status) {
            AWAITING_APPROVAL -> flowOf(TransferRejected(c.transferId, "No approval within the deadline"))
            REJECTED -> emptyFlow()
            else -> reject(c, "Transfer is not awaiting approval (status ${s.status})")
        }

        is BookTransfer -> when {
            s.kind == TransferKind.EXTERNAL -> reject(c, "External transfers are sent to the external bank, not booked")
            s.status == BOOKED -> emptyFlow()
            s.status == AWAITING_APPROVAL -> reject(c, "Transfer is awaiting approval")
            s.status in OPEN && s.creditorAccountId == s.debtorAccountId ->
                flowOf(TransferRejected(c.transferId, "Debtor and creditor account are identical"))
            s.status in OPEN -> flowOf(TransferBooked(c.transferId))
            else -> notAllowed(c, s, "booked")
        }

        is SendToExternalBank -> when {
            s.kind == TransferKind.INTERNAL || s.kind == TransferKind.DEPOSIT -> reject(c, "Only external transfers go to an external bank")
            s.status == SENT_TO_EXTERNAL_BANK -> emptyFlow()
            s.status == AWAITING_APPROVAL -> reject(c, "Transfer is awaiting approval")
            s.status in OPEN -> flowOf(TransferSentToExternalBank(c.transferId))
            else -> notAllowed(c, s, "sent to the external bank")
        }

        is CompleteTransfer -> when (s.status) {
            SENT_TO_EXTERNAL_BANK -> flowOf(TransferCompleted(c.transferId))
            COMPLETED -> emptyFlow()
            else -> notAllowed(c, s, "completed")
        }

        // Once the external bank accepted, the money is gone: no refund any more.
        is RefundTransfer -> when (s.status) {
            SENT_TO_EXTERNAL_BANK -> flowOf(TransferRefunded(c.transferId, c.reason))
            REFUNDED -> emptyFlow()
            else -> notAllowed(c, s, "refunded")
        }

        is RejectTransfer -> when (s.status) {
            RECEIVED, AWAITING_APPROVAL, APPROVED -> flowOf(TransferRejected(c.transferId, c.reason))
            REJECTED -> emptyFlow()
            else -> notAllowed(c, s, "rejected")
        }
    }
}

internal fun evolve(s: Transfer, e: TransferEvent): Transfer = when (e) {
    is TransferReceived -> Transfer(
        status = RECEIVED,
        kind = e.kind,
        debtorAccountId = e.debtorAccountId,
        creditorAccountId = e.creditorAccountId,
        amountCents = e.amountCents,
    )
    is TransferStatusChanged -> s.copy(status = e.status, reason = e.reason)
    is TransferCommandRejected -> s
}

private fun notAllowed(c: TransferCommand, s: Transfer, action: String) = reject(c, "Transfer in status ${s.status} cannot be $action")

private fun reject(c: TransferCommand, reason: String): Flow<TransferEvent> = flowOf(TransferCommandRejected(c.transferId, reason))
