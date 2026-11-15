package com.example.bank.workflow

import com.example.bank.activities.ApprovalRequest
import com.example.bank.activities.ApprovalVerdict
import com.example.bank.activities.ClearingHouseActivities
import com.example.bank.activities.ExternalDebit
import com.example.bank.activities.InstantPayment
import com.example.bank.activities.InternalBooking
import com.example.bank.activities.Investigation
import com.example.bank.activities.LedgerActivities
import com.example.bank.activities.PaymentAnswer
import com.example.bank.activities.Refund
import com.example.bank.activities.RiskActivities
import com.example.bank.activities.RiskAssessment
import com.example.bank.activities.RiskCheck
import com.example.bank.activities.StatusReport
import com.example.bank.activities.Submission
import com.example.bank.activities.TransferOutcome
import com.example.bank.activities.TransferRejection
import com.example.payments.messages.Pacs002
import com.example.payments.messages.TxSts
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The activities as fakes for the workflow tests and the history recorders: no Spring, no database, no HTTP.

/** Answers like the ledger would, without its rules: each step simply leads to its usual status. */
class FakeLedger : LedgerActivities {
    val calls = CopyOnWriteArrayList<String>()

    @Volatile var bookResult = TransferOutcome(TransferStatus.BOOKED)

    @Volatile var reserveResult = TransferOutcome(TransferStatus.RESERVED)

    /** Incoming payments: accepted unless a test says otherwise. */
    @Volatile var incomingAnswer = TransferOutcome(TransferStatus.ACCEPTED)

    @Volatile var creditResult = TransferOutcome(TransferStatus.BOOKED)

    @Volatile var investigation: Investigation? = null

    @Volatile var rejection: TransferRejection? = null

    @Volatile var verdict: ApprovalVerdict? = null

    /** If set, `requireApproval` waits for it: the ledger has recorded the status, the workflow doesn't know yet. */
    @Volatile var requireApprovalGate: CountDownLatch? = null

    val requireApprovalEntered = CountDownLatch(1)

    /** If set, `settleExternalTransfer` waits for it: the workflow is busy with the confirmation it got. */
    @Volatile var settleGate: CountDownLatch? = null

    val settleEntered = CountDownLatch(1)

    override fun requireApproval(request: ApprovalRequest): TransferOutcome {
        requireApprovalEntered.countDown()
        requireApprovalGate?.await(10, TimeUnit.SECONDS)
        calls += "requireApproval"
        return TransferOutcome(TransferStatus.AWAITING_APPROVAL, request.reason)
    }

    override fun decideApproval(verdict: ApprovalVerdict): TransferOutcome {
        this.verdict = verdict
        calls += if (verdict.approved) "approve" else "deny"
        return if (verdict.approved) {
            TransferOutcome(
                TransferStatus.APPROVED,
            )
        } else {
            TransferOutcome(TransferStatus.REJECTED, "denied by the ledger")
        }
    }

    override fun expireApproval(transferId: UUID) = TransferOutcome(TransferStatus.REJECTED, "expired").also { calls += "expire" }

    override fun bookInternalTransfer(booking: InternalBooking) = bookResult.also { calls += "book" }

    override fun reserveForExternalTransfer(debit: ExternalDebit) = reserveResult.also { calls += "reserve" }

    override fun markSentToExternalBank(transferId: UUID) = TransferOutcome(TransferStatus.SENT_TO_EXTERNAL_BANK).also {
        calls += "markSent"
    }

    override fun settleExternalTransfer(transferId: UUID): TransferOutcome {
        settleEntered.countDown()
        settleGate?.await(10, TimeUnit.SECONDS)
        calls += "settle"
        return TransferOutcome(TransferStatus.COMPLETED)
    }

    override fun startInvestigation(investigation: Investigation): TransferOutcome {
        this.investigation = investigation
        calls += "investigate"
        return TransferOutcome(TransferStatus.UNDER_INVESTIGATION, investigation.reason)
    }

    override fun refundExternalTransfer(refund: Refund) = TransferOutcome(TransferStatus.REFUNDED, refund.reason).also { calls += "refund" }

    override fun rejectTransfer(rejection: TransferRejection): TransferOutcome {
        this.rejection = rejection
        calls += "reject"
        return TransferOutcome(TransferStatus.REJECTED, rejection.reason)
    }

    override fun answerIncomingPayment(transferId: UUID) = incomingAnswer.also { calls += "answer" }

    override fun creditIncomingPayment(transferId: UUID) = creditResult.also { calls += "credit" }

    // The simulated external bank's steps: only executions from before SEPA Instant use them (replay test).
    override fun debitForExternalTransfer(debit: ExternalDebit) = TransferOutcome(TransferStatus.SENT_TO_EXTERNAL_BANK).also {
        calls +=
            "debit"
    }

    override fun completeExternalTransfer(transferId: UUID) = TransferOutcome(TransferStatus.COMPLETED).also { calls += "complete" }
}

/** Completes the future with the call's result, or with its failure, so another thread can wait for it. */
fun <T> CompletableFuture<T>.completeWith(call: () -> T) {
    runCatching(call).fold({ complete(it) }, { completeExceptionally(it) })
}

class FakeRisk : RiskActivities {
    @Volatile var reviewRequired = false

    override fun assess(check: RiskCheck) = if (reviewRequired) {
        RiskAssessment(
            true,
            "Amount exceeds approval limit",
        )
    } else {
        RiskAssessment(false)
    }
}

/** Takes every payment and answer; answers inquiries as the test says (by default: still pending). */
class FakeClearingHouse : ClearingHouseActivities {
    @Volatile var onSubmit: (InstantPayment) -> Submission = { Submission(received = true) }

    /** Called with the UETR and the number of the inquiry (1, 2, …). */
    @Volatile var onInquire: (UUID, Int) -> StatusReport = { uetr, _ -> StatusReport(Pacs002(UUID.randomUUID(), uetr, TxSts.PDNG)) }

    /** True: the clearing house takes the bank's answer to an incoming payment. */
    @Volatile var onAnswer: (PaymentAnswer) -> Boolean = { true }

    val submissions = CopyOnWriteArrayList<InstantPayment>()
    val submissionAttempts = AtomicInteger()
    val inquiries = AtomicInteger()
    val answers = CopyOnWriteArrayList<PaymentAnswer>()
    val answerAttempts = AtomicInteger()

    override fun submit(payment: InstantPayment): Submission {
        submissionAttempts.incrementAndGet()
        return onSubmit(payment).also { submissions += payment }
    }

    override fun inquire(uetr: UUID) = onInquire(uetr, inquiries.incrementAndGet())

    override fun answer(answer: PaymentAnswer): Boolean {
        answerAttempts.incrementAndGet()
        return onAnswer(answer).also { answers += answer }
    }
}
