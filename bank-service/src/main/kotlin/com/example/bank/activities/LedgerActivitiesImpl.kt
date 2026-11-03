package com.example.bank.activities

import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId
import com.example.bank.ledger.BookingResult
import com.example.bank.ledger.IllegalTransferState
import com.example.bank.ledger.Ledger
import com.example.bank.observability.BankSpans
import com.example.bank.workflow.BANK_TASK_QUEUE
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Thin layer over the [Ledger]: translates activity DTOs into domain types.
 * The business decision is made by the fmodel aggregate inside the ledger.
 */
@Component
@ActivityImpl(taskQueues = [BANK_TASK_QUEUE])
class LedgerActivitiesImpl(private val ledger: Ledger) : LedgerActivities {

    // Temporal puts WorkflowId, RunId, ActivityType, Attempt, … into the MDC of activity threads.
    private val log = LoggerFactory.getLogger(javaClass)

    override fun bookInternalTransfer(booking: InternalBooking): BookingOutcome {
        BankSpans.tag(
            BankSpans.TRANSFER_ID to booking.transferId,
            BankSpans.DEBTOR_ACCOUNT_ID to booking.debtorAccountId,
            BankSpans.CREDITOR_ACCOUNT_ID to booking.creditorAccountId,
        )
        return ledger.bookInternalTransfer(
            TransferId(booking.transferId),
            AccountId(booking.debtorAccountId),
            AccountId(booking.creditorAccountId),
            booking.amountCents,
        ).toOutcome().also { log.info("Internal transfer {}: {}", booking.transferId, it.describe()) }
    }

    override fun debitForExternalTransfer(debit: ExternalDebit): BookingOutcome {
        BankSpans.tag(BankSpans.TRANSFER_ID to debit.transferId, BankSpans.DEBTOR_ACCOUNT_ID to debit.debtorAccountId)
        return ledger.debitForExternalTransfer(TransferId(debit.transferId), AccountId(debit.debtorAccountId), debit.amountCents)
            .toOutcome().also { log.info("External transfer {} debit: {}", debit.transferId, it.describe()) }
    }

    override fun completeExternalTransfer(transferId: UUID) = nonRetryableOnIllegalState {
        BankSpans.tag(BankSpans.TRANSFER_ID to transferId)
        ledger.completeExternalTransfer(TransferId(transferId))
    }

    override fun refundExternalTransfer(refund: Refund): BookingOutcome = nonRetryableOnIllegalState {
        BankSpans.tag(BankSpans.TRANSFER_ID to refund.transferId)
        ledger.refundExternalTransfer(TransferId(refund.transferId), refund.reason).toOutcome()
            .also { log.info("External transfer {} refunded ({})", refund.transferId, refund.reason) }
    }

    override fun rejectTransfer(rejection: TransferRejection) {
        BankSpans.tag(BankSpans.TRANSFER_ID to rejection.transferId)
        ledger.rejectTransfer(TransferId(rejection.transferId), rejection.reason)
    }

    private fun BookingResult.toOutcome() = BookingOutcome(booked, reason)

    private fun BookingOutcome.describe() = if (booked) "booked" else "rejected ($reason)"

    /** An illegal state transition does not get better by retrying: fail right away instead of retrying forever. */
    private fun <T> nonRetryableOnIllegalState(block: () -> T): T = try {
        block()
    } catch (e: IllegalTransferState) {
        throw ApplicationFailure.newNonRetryableFailure(e.message, "IllegalTransferState")
    }
}
