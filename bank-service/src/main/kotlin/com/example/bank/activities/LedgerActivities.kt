package com.example.bank.activities

import io.temporal.activity.ActivityInterface
import java.util.UUID

/** Result of a booking activity. The workflow only knows this contract, not the ledger's own types. */
data class BookingOutcome(val booked: Boolean, val reason: String? = null)

data class InternalBooking(val transferId: UUID, val debtorAccountId: UUID, val creditorAccountId: UUID, val amountCents: Long)

data class ExternalDebit(val transferId: UUID, val debtorAccountId: UUID, val amountCents: Long)

data class Refund(val transferId: UUID, val reason: String?)

data class TransferRejection(val transferId: UUID, val reason: String)

/** All postings go through these activities. Every method is idempotent (at-least-once). */
@ActivityInterface(namePrefix = "Ledger")
interface LedgerActivities {
    fun bookInternalTransfer(booking: InternalBooking): BookingOutcome

    fun debitForExternalTransfer(debit: ExternalDebit): BookingOutcome

    fun completeExternalTransfer(transferId: UUID)

    fun refundExternalTransfer(refund: Refund): BookingOutcome

    fun rejectTransfer(rejection: TransferRejection)
}
