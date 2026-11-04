package com.example.bank.activities

import com.example.bank.workflow.TransferStatus
import io.temporal.activity.ActivityInterface
import java.util.UUID

/**
 * The transfer's business status after a ledger activity, as the ledger's transfer decider decided it.
 * The workflow only mirrors it (for its `status` query) and branches on it; it never sets a status itself.
 */
data class TransferOutcome(val status: TransferStatus, val reason: String? = null)

data class InternalBooking(val transferId: UUID, val debtorAccountId: UUID, val creditorAccountId: UUID, val amountCents: Long)

data class ExternalDebit(val transferId: UUID, val debtorAccountId: UUID, val amountCents: Long)

data class Refund(val transferId: UUID, val reason: String?)

data class TransferRejection(val transferId: UUID, val reason: String)

data class ApprovalRequest(val transferId: UUID, val reason: String?)

/** What a person decided; the ledger turns a denial into the rejection reason. */
data class ApprovalVerdict(val transferId: UUID, val approved: Boolean, val comment: String?)

/** Every change of a transfer goes through these activities. Every method is idempotent (at-least-once). */
@ActivityInterface(namePrefix = "Ledger")
interface LedgerActivities {
    fun requireApproval(request: ApprovalRequest): TransferOutcome

    fun decideApproval(verdict: ApprovalVerdict): TransferOutcome

    fun expireApproval(transferId: UUID): TransferOutcome

    fun bookInternalTransfer(booking: InternalBooking): TransferOutcome

    fun debitForExternalTransfer(debit: ExternalDebit): TransferOutcome

    fun completeExternalTransfer(transferId: UUID): TransferOutcome

    fun refundExternalTransfer(refund: Refund): TransferOutcome

    fun rejectTransfer(rejection: TransferRejection): TransferOutcome
}
