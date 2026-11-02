package com.example.bank.activities

import com.example.bank.ledger.BookingResult
import io.temporal.activity.ActivityInterface
import java.util.UUID

data class InternalBooking(val transferId: UUID, val debtorAccountId: UUID, val creditorAccountId: UUID, val amountCents: Long)

data class ExternalDebit(val transferId: UUID, val debtorAccountId: UUID, val amountCents: Long)

data class Refund(val transferId: UUID, val reason: String?)

data class TransferRejection(val transferId: UUID, val reason: String)

/** All postings go through these activities. Every method is idempotent (at-least-once). */
@ActivityInterface(namePrefix = "Ledger")
interface LedgerActivities {
    fun bookInternalTransfer(booking: InternalBooking): BookingResult

    fun debitForExternalTransfer(debit: ExternalDebit): BookingResult

    fun completeExternalTransfer(transferId: UUID)

    fun refundExternalTransfer(refund: Refund): BookingResult

    fun rejectTransfer(rejection: TransferRejection)
}
