package com.example.bank.activities

import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId
import com.example.bank.ledger.BookingResult
import com.example.bank.ledger.IllegalTransferState
import com.example.bank.ledger.Ledger
import com.example.bank.workflow.BANK_TASK_QUEUE
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Thin layer over the [Ledger]: translates activity DTOs into domain types.
 * The business decision is made by the fmodel aggregate inside the ledger.
 */
@Component
@ActivityImpl(taskQueues = [BANK_TASK_QUEUE])
class LedgerActivitiesImpl(private val ledger: Ledger) : LedgerActivities {

    override fun bookInternalTransfer(booking: InternalBooking): BookingResult =
        ledger.bookInternalTransfer(
            TransferId(booking.transferId),
            AccountId(booking.debtorAccountId),
            AccountId(booking.creditorAccountId),
            booking.amountCents,
        )

    override fun debitForExternalTransfer(debit: ExternalDebit): BookingResult =
        ledger.debitForExternalTransfer(TransferId(debit.transferId), AccountId(debit.debtorAccountId), debit.amountCents)

    override fun completeExternalTransfer(transferId: UUID) = nonRetryableOnIllegalState {
        ledger.completeExternalTransfer(TransferId(transferId))
    }

    override fun refundExternalTransfer(refund: Refund): BookingResult = nonRetryableOnIllegalState {
        ledger.refundExternalTransfer(TransferId(refund.transferId), refund.reason)
    }

    override fun rejectTransfer(rejection: TransferRejection) {
        ledger.rejectTransfer(TransferId(rejection.transferId), rejection.reason)
    }

    /** An illegal state transition does not get better by retrying: fail right away instead of retrying forever. */
    private fun <T> nonRetryableOnIllegalState(block: () -> T): T = try {
        block()
    } catch (e: IllegalTransferState) {
        throw ApplicationFailure.newNonRetryableFailure(e.message, "IllegalTransferState")
    }
}
