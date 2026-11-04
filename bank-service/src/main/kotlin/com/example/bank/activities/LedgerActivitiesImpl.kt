package com.example.bank.activities

import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId
import com.example.bank.ledger.IllegalTransferState
import com.example.bank.ledger.Ledger
import com.example.bank.ledger.TransferResult
import com.example.bank.observability.BankSpans
import com.example.bank.workflow.BANK_TASK_QUEUE
import com.example.bank.workflow.TransferStatus
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Thin layer over the [Ledger]: translates activity DTOs into domain types and back.
 * The business decisions are made by the fmodel aggregates inside the ledger.
 */
@Component
@ActivityImpl(taskQueues = [BANK_TASK_QUEUE])
class LedgerActivitiesImpl(private val ledger: Ledger) : LedgerActivities {

    // Temporal puts WorkflowId, RunId, ActivityType, Attempt, … into the MDC of activity threads.
    private val log = LoggerFactory.getLogger(javaClass)

    override fun requireApproval(request: ApprovalRequest) = change(request.transferId) {
        ledger.requireApproval(it, request.reason ?: "Manual review required")
    }

    override fun decideApproval(verdict: ApprovalVerdict) = change(verdict.transferId) {
        if (verdict.approved) ledger.approve(it, verdict.comment) else ledger.denyApproval(it, verdict.comment)
    }

    override fun expireApproval(transferId: UUID) = change(transferId) { ledger.expireApproval(it) }

    override fun bookInternalTransfer(booking: InternalBooking): TransferOutcome {
        BankSpans.tag(
            BankSpans.TRANSFER_ID to booking.transferId,
            BankSpans.DEBTOR_ACCOUNT_ID to booking.debtorAccountId,
            BankSpans.CREDITOR_ACCOUNT_ID to booking.creditorAccountId,
        )
        return change(booking.transferId) {
            ledger.bookInternalTransfer(it, AccountId(booking.debtorAccountId), AccountId(booking.creditorAccountId), booking.amountCents)
        }
    }

    override fun debitForExternalTransfer(debit: ExternalDebit): TransferOutcome {
        BankSpans.tag(BankSpans.DEBTOR_ACCOUNT_ID to debit.debtorAccountId)
        return change(debit.transferId) { ledger.debitForExternalTransfer(it, AccountId(debit.debtorAccountId), debit.amountCents) }
    }

    override fun completeExternalTransfer(transferId: UUID) = change(transferId) { ledger.completeExternalTransfer(it) }

    override fun refundExternalTransfer(refund: Refund) = change(refund.transferId) {
        ledger.refundExternalTransfer(it, refund.reason ?: "Rejected by the external bank")
    }

    override fun rejectTransfer(rejection: TransferRejection) = change(rejection.transferId) {
        ledger.rejectTransfer(it, rejection.reason)
    }

    /**
     * Runs one ledger operation and reports the transfer's status afterwards. A step the transfer's status doesn't
     * allow does not get better by retrying: it fails right away instead of retrying forever.
     */
    private fun change(transferId: UUID, operation: (TransferId) -> TransferResult): TransferOutcome {
        BankSpans.tag(BankSpans.TRANSFER_ID to transferId)
        val result = try {
            operation(TransferId(transferId))
        } catch (e: IllegalTransferState) {
            throw ApplicationFailure.newNonRetryableFailureWithCause(e.message, "IllegalTransferState", e)
        }
        log.info("Transfer {}: {}{}", transferId, result.status, result.reason?.let { " ($it)" } ?: "")
        return TransferOutcome(TransferStatus.valueOf(result.status.name), result.reason)
    }
}
