package com.example.bank.activities

import com.example.bank.workflow.TransferStatus
import io.temporal.activity.ActivityInterface
import java.util.UUID

/**
 * The transfer's business status after a ledger activity, as the ledger's transfer decider decided it.
 * The workflow only mirrors it (for its `status` query) and branches on it; it never sets a status itself.
 */
data class TransferOutcome(
    val status: TransferStatus,
    val reason: String? = null,
    /** The ISO 20022 code a rejected incoming payment is answered with; null otherwise. */
    val reasonCode: String? = null,
)

data class InternalBooking(val transferId: UUID, val debtorAccountId: UUID, val creditorAccountId: UUID, val amountCents: Long)

data class ExternalDebit(val transferId: UUID, val debtorAccountId: UUID, val amountCents: Long)

data class Refund(val transferId: UUID, val reason: String?)

data class TransferRejection(val transferId: UUID, val reason: String)

data class Investigation(val transferId: UUID, val reason: String)

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

    /** SEPA Instant: debits the customer and reserves the amount on the clearing account. */
    fun reserveForExternalTransfer(debit: ExternalDebit): TransferOutcome

    fun markSentToExternalBank(transferId: UUID): TransferOutcome

    /** The clearing house confirmed the settlement: the reserved amount leaves through the settlement account. */
    fun settleExternalTransfer(transferId: UUID): TransferOutcome

    fun startInvestigation(investigation: Investigation): TransferOutcome

    /** Incoming payment: accepted, or rejected with a reason code; decided once. */
    fun answerIncomingPayment(transferId: UUID): TransferOutcome

    /** Incoming payment settled by the clearing house: credits the customer from the settlement account. */
    fun creditIncomingPayment(transferId: UUID): TransferOutcome

    /** Refunds the reserved amount. */
    fun refundExternalTransfer(refund: Refund): TransferOutcome

    /** Transfers started before SEPA Instant (simulated external bank): reserve and send in one step. */
    fun debitForExternalTransfer(debit: ExternalDebit): TransferOutcome

    /** Transfers started before SEPA Instant: the simulated external bank accepted. */
    fun completeExternalTransfer(transferId: UUID): TransferOutcome

    fun rejectTransfer(rejection: TransferRejection): TransferOutcome
}
