package com.example.bank.workflow

import com.example.bank.activities.ApprovalRequest
import com.example.bank.activities.ApprovalVerdict
import com.example.bank.activities.ClearingHouseActivities
import com.example.bank.activities.ExternalDebit
import com.example.bank.activities.InstantPayment
import com.example.bank.activities.InternalBooking
import com.example.bank.activities.Investigation
import com.example.bank.activities.LedgerActivities
import com.example.bank.activities.Refund
import com.example.bank.activities.RiskActivities
import com.example.bank.activities.RiskAssessment
import com.example.bank.activities.RiskCheck
import com.example.bank.activities.StatusReport
import com.example.bank.activities.Submission
import com.example.bank.activities.TransferOutcome
import com.example.bank.activities.TransferRejection
import com.example.bank.config.TemporalConfig
import com.example.payments.messages.Pacs002
import com.example.payments.messages.ReasonCodes
import com.example.payments.messages.TxSts
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.client.WorkflowUpdateStage
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Workflow tests without Spring and without a database: in-memory test server with time skipping,
 * activities as fakes. Days of approval deadline and hours of investigation pass in milliseconds.
 *
 * They test the orchestration: which ledger step comes when, what it gets, and that the workflow takes over the
 * status the ledger decided. The status rules themselves are tested in the transfer decider (`TransferDeciderTest`).
 */
class TransferWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private val ledger = FakeLedger()
    private val risk = FakeRisk()
    private val clearing = FakeClearingHouse()

    private val debtor = UUID.randomUUID()
    private val creditor = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(TemporalConfig.kotlinDataConverter()).build())
                .build(),
        )
        val worker = env.newWorker(BANK_TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(TransferWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(ledger, risk, clearing)
        env.start()
        client = env.workflowClient
    }

    @AfterEach
    fun tearDown() = env.close()

    @Test
    fun `internal transfer is booked in one activity`() {
        val result = run(internal(50_00))

        assertEquals(TransferStatus.BOOKED, result.status)
        assertEquals(listOf("book"), ledger.calls)
    }

    @Test
    fun `a business rejection by the ledger ends the transfer`() {
        ledger.bookResult = TransferOutcome(TransferStatus.REJECTED, "Insufficient funds")

        val result = run(internal(50_00))

        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals("Insufficient funds", result.reason)
    }

    @Test
    fun `the clearing house confirms the settlement`() {
        val (workflow, stub) = start(external(50_00))
        awaitStatus(workflow, TransferStatus.SENT_TO_EXTERNAL_BANK)

        workflow.confirm(confirmation(workflow, TxSts.ACSC))

        assertEquals(TransferStatus.COMPLETED, stub.getResult(TransferState::class.java).status)
        assertEquals(listOf("reserve", "markSent", "settle"), ledger.calls)
        assertEquals(0, clearing.inquiries.get(), "a confirmation needs no inquiry")
        val payment = clearing.submissions.single()
        assertEquals("DE89370400440532013000", payment.creditorIban)
        assertEquals(50_00, payment.amountCents)
    }

    @Test
    fun `the creditor bank rejects, the reservation is refunded with a readable reason`() {
        val (workflow, stub) = start(external(50_00))
        awaitStatus(workflow, TransferStatus.SENT_TO_EXTERNAL_BANK)

        workflow.confirm(confirmation(workflow, TxSts.RJCT, ReasonCodes.CLOSED_ACCOUNT))

        val result = stub.getResult(TransferState::class.java)
        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("Account closed (AC04)", result.reason)
        assertEquals(listOf("reserve", "markSent", "refund"), ledger.calls)
    }

    @Test
    fun `without sufficient funds nothing is sent to the clearing house`() {
        ledger.reserveResult = TransferOutcome(TransferStatus.REJECTED, "Insufficient funds")

        val result = run(external(50_00))

        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals(0, clearing.submissions.size)
    }

    @Test
    fun `refused by the clearing house, the reservation is refunded at once`() {
        clearing.onSubmit = { Submission(received = false, reason = "Refused by the clearing house (HTTP 422)") }

        val result = run(external(50_00))

        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("Refused by the clearing house (HTTP 422)", result.reason)
        assertEquals(listOf("reserve", "refund"), ledger.calls)
        assertEquals(0, clearing.inquiries.get())
    }

    @Test
    fun `a confirmation that arrives before the payment's submission has returned still counts`() {
        clearing.onSubmit = { payment ->
            // The clearing house is fast: its confirmation arrives while the bank still waits for the HTTP answer.
            Thread { confirm(payment.transferId, TxSts.ACSC) }.start()
            Thread.sleep(500)
            Submission(received = true)
        }

        assertEquals(TransferStatus.COMPLETED, run(external(50_00)).status)
        assertEquals(listOf("reserve", "markSent", "settle"), ledger.calls)
    }

    @Test
    fun `no confirmation in time, still pending at the inquiry, then a late confirmation settles it`() {
        clearing.onInquire = { transferId, n ->
            if (n == 1) confirm(transferId, TxSts.ACSC) // arrives late, while the bank is already asking
            StatusReport(Pacs002(UUID.randomUUID(), transferId, TxSts.PDNG))
        }

        val result = run(external(50_00)) // time skipping: the 20 seconds pass instantly

        assertEquals(TransferStatus.COMPLETED, result.status)
        assertEquals(listOf("reserve", "markSent", "settle"), ledger.calls)
        assertEquals(1, clearing.inquiries.get())
    }

    @Test
    fun `a lost confirmation is resolved by the status inquiry`() {
        clearing.onInquire = { transferId, _ -> StatusReport(Pacs002(UUID.randomUUID(), transferId, TxSts.ACSC)) }

        assertEquals(TransferStatus.COMPLETED, run(external(50_00)).status)
        assertEquals(listOf("reserve", "markSent", "settle"), ledger.calls)
    }

    @Test
    fun `a lost rejection is resolved by the status inquiry`() {
        clearing.onInquire = { transferId, _ ->
            StatusReport(Pacs002(UUID.randomUUID(), transferId, TxSts.RJCT, ReasonCodes.TIMEOUT_CREDITOR_AGENT))
        }

        val result = run(external(50_00))

        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("The creditor bank did not answer in time (AB05)", result.reason)
    }

    @Test
    fun `unreachable while sending and unknown to the clearing house afterwards, the payment is refunded`() {
        clearing.onSubmit = { error("clearing house unreachable") }
        clearing.onInquire = { _, _ -> StatusReport(status = null) }

        val result = run(external(50_00))

        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("Not received by the clearing house", result.reason)
        assertEquals(listOf("reserve", "refund"), ledger.calls)
        assertTrue(clearing.submissionAttempts.get() > 1, "sending is retried until the deadline")
    }

    @Test
    fun `unreachable while sending but received after all, the payment is settled`() {
        clearing.onSubmit = { error("connection reset after the request was sent") }
        clearing.onInquire = { transferId, _ -> StatusReport(Pacs002(UUID.randomUUID(), transferId, TxSts.ACSC)) }

        assertEquals(TransferStatus.COMPLETED, run(external(50_00)).status)
        assertEquals(listOf("reserve", "settle"), ledger.calls)
    }

    @Test
    fun `unknown although the clearing house acknowledged it, the money is not refunded but investigated`() {
        clearing.onInquire = { transferId, _ ->
            // The clearing house's books only show it once the investigation is under way (e.g. after a restore).
            val known = "investigate" in ledger.calls
            StatusReport(if (known) Pacs002(UUID.randomUUID(), transferId, TxSts.ACSC) else null)
        }

        val result = run(external(50_00))

        assertEquals(TransferStatus.COMPLETED, result.status)
        assertEquals(listOf("reserve", "markSent", "investigate", "settle"), ledger.calls)
        assertEquals("No final status from the clearing house", ledger.investigation?.reason)
    }

    @Test
    fun `under investigation the amount stays reserved until a final status arrives`() {
        clearing.onInquire = { _, _ -> error("clearing house down") }
        val (workflow, stub) = start(external(50_00))
        awaitStatus(workflow, TransferStatus.SENT_TO_EXTERNAL_BANK)

        env.sleep(TransferWorkflowImpl.CONFIRMATION_TIMEOUT + TransferWorkflowImpl.INQUIRY_PERIOD + Duration.ofMinutes(2))
        awaitStatus(workflow, TransferStatus.UNDER_INVESTIGATION)
        env.sleep(Duration.ofHours(5))
        assertEquals(TransferStatus.UNDER_INVESTIGATION, workflow.status().status, "no guessing, also hours later")
        assertFalse("refund" in ledger.calls)

        workflow.confirm(confirmation(workflow, TxSts.ACSC))

        assertEquals(TransferStatus.COMPLETED, stub.getResult(TransferState::class.java).status)
        assertEquals(listOf("reserve", "markSent", "investigate", "settle"), ledger.calls)
    }

    @Test
    fun `the validator takes only the one confirmation it expects`() {
        val (workflow, stub) = start(external(50_00))
        awaitStatus(workflow, TransferStatus.SENT_TO_EXTERNAL_BANK)

        assertRefused("ACSC or RJCT") { workflow.confirm(confirmation(workflow, TxSts.PDNG)) }
        assertRefused("another payment") { workflow.confirm(Pacs002(UUID.randomUUID(), UUID.randomUUID(), TxSts.ACSC)) }
        val gate = CountDownLatch(1)
        ledger.settleGate = gate
        workflow.confirm(confirmation(workflow, TxSts.ACSC))
        assertTrue(ledger.settleEntered.await(10, TimeUnit.SECONDS)) // the workflow acts on the first confirmation
        assertRefused("No confirmation expected") { workflow.confirm(confirmation(workflow, TxSts.RJCT)) }
        gate.countDown()

        assertEquals(TransferStatus.COMPLETED, stub.getResult(TransferState::class.java).status)
    }

    @Test
    fun `an internal transfer expects no confirmation`() {
        risk.reviewRequired = true
        val (workflow, _) = start(internal(20_000_00))
        awaitStatus(workflow, TransferStatus.AWAITING_APPROVAL)

        assertRefused("No confirmation expected") { workflow.confirm(confirmation(workflow, TxSts.ACSC)) }
    }

    @Test
    fun `suspected fraud pauses the transfer and approval via update books it`() {
        risk.reviewRequired = true
        val (workflow, stub) = start(internal(20_000_00))

        awaitStatus(workflow, TransferStatus.AWAITING_APPROVAL)
        assertEquals("Amount exceeds approval limit", workflow.status().reason)
        assertEquals(listOf("requireApproval"), ledger.calls, "nothing is booked before approval")

        val afterUpdate = workflow.approve(ApprovalDecision(approved = true, comment = "checked"))
        assertEquals(TransferStatus.APPROVED, afterUpdate.status)

        assertEquals(TransferStatus.BOOKED, stub.getResult(TransferState::class.java).status)
        assertEquals(listOf("requireApproval", "approve", "book"), ledger.calls)
        assertEquals("checked", ledger.verdict?.comment)
    }

    @Test
    fun `an approval right after the ledger shows the transfer as waiting is accepted`() {
        risk.reviewRequired = true
        val gate = CountDownLatch(1)
        ledger.requireApprovalGate = gate
        val (_, stub) = start(internal(20_000_00))
        assertTrue(ledger.requireApprovalEntered.await(10, TimeUnit.SECONDS)) // the ledger records AWAITING_APPROVAL now

        // The UI reads AWAITING_APPROVAL from the ledger and approves before the workflow has the activity's result.
        val update = stub.startUpdate("approve", WorkflowUpdateStage.ACCEPTED, TransferState::class.java, ApprovalDecision(approved = true))
        gate.countDown()

        assertEquals(TransferStatus.APPROVED, update.result.status)
        assertEquals(TransferStatus.BOOKED, stub.getResult(TransferState::class.java).status)
        assertEquals(listOf("requireApproval", "approve", "book"), ledger.calls)
    }

    @Test
    fun `denied approval aborts the transfer`() {
        risk.reviewRequired = true
        val (workflow, stub) = start(internal(20_000_00))
        awaitStatus(workflow, TransferStatus.AWAITING_APPROVAL)

        workflow.approve(ApprovalDecision(approved = false, comment = "customer reports fraud"))

        val result = stub.getResult(TransferState::class.java)
        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals("denied by the ledger", result.reason, "the workflow takes over the ledger's reason")
        assertEquals(listOf("requireApproval", "deny"), ledger.calls)
        assertEquals("customer reports fraud", ledger.verdict?.comment)
    }

    @Test
    fun `without approval within the deadline the transfer is rejected`() {
        risk.reviewRequired = true
        val (_, stub) = start(internal(20_000_00))

        val result = stub.getResult(TransferState::class.java) // time skipping: three days pass instantly

        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals(listOf("requireApproval", "expire"), ledger.calls)
    }

    @Test
    fun `the validator rejects an approval when none is pending`() {
        val (workflow, stub) = start(external(50_00)) // waits for the clearing house's confirmation
        awaitStatus(workflow, TransferStatus.SENT_TO_EXTERNAL_BANK)

        assertRefused("not awaiting approval") { workflow.approve(ApprovalDecision(approved = true)) }

        workflow.confirm(confirmation(workflow, TxSts.ACSC))
        assertEquals(TransferStatus.COMPLETED, stub.getResult(TransferState::class.java).status)
    }

    // --- helpers ---

    private fun internal(amount: Long) = TransferRequest(UUID.randomUUID(), debtor, amount, creditorAccountId = creditor)

    private fun external(amount: Long) = TransferRequest(UUID.randomUUID(), debtor, amount, creditorIban = "DE89370400440532013000")

    private fun run(request: TransferRequest): TransferState {
        val (_, stub) = start(request)
        return stub.getResult(TransferState::class.java)
    }

    private fun start(request: TransferRequest): Pair<TransferWorkflow, WorkflowStub> {
        val workflow = client.newWorkflowStub(
            TransferWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(BANK_TASK_QUEUE).setWorkflowId(transferWorkflowId(request.transferId)).build(),
        )
        WorkflowClient.start(workflow::transfer, request)
        return workflow to WorkflowStub.fromTyped(workflow)
    }

    private fun confirmation(workflow: TransferWorkflow, txSts: TxSts, reasonCode: String? = null) =
        Pacs002(UUID.randomUUID(), workflow.status().transferId, txSts, reasonCode)

    /** What the bank's inbound endpoint does with the clearing house's pacs.002. */
    private fun confirm(transferId: UUID, txSts: TxSts) =
        client.newWorkflowStub(TransferWorkflow::class.java, transferWorkflowId(transferId))
            .confirm(Pacs002(UUID.randomUUID(), transferId, txSts))

    private fun assertRefused(message: String, update: () -> Unit) {
        val error = assertThrows<WorkflowUpdateException> { update() }
        assertTrue(error.cause?.message.orEmpty().contains(message), "expected '$message', got: ${error.cause?.message}")
    }

    private fun awaitStatus(workflow: TransferWorkflow, expected: TransferStatus) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching { workflow.status().status }.getOrNull() == expected) return
            Thread.sleep(20)
        }
        error("Status $expected not reached, last: ${runCatching { workflow.status() }.getOrNull()}")
    }

    /** Answers like the ledger would, without its rules: each step simply leads to its usual status. */
    class FakeLedger : LedgerActivities {
        val calls = CopyOnWriteArrayList<String>()

        @Volatile var bookResult = TransferOutcome(TransferStatus.BOOKED)

        @Volatile var reserveResult = TransferOutcome(TransferStatus.RESERVED)

        @Volatile var investigation: Investigation? = null

        @Volatile var verdict: ApprovalVerdict? = null

        /** If set, `requireApproval` waits for it: the ledger has recorded the status, the workflow doesn't know yet. */
        @Volatile var requireApprovalGate: CountDownLatch? = null

        val requireApprovalEntered = CountDownLatch(1)

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

        override fun expireApproval(transferId: UUID) =
            TransferOutcome(TransferStatus.REJECTED, "expired").also { calls += "expire" }

        override fun bookInternalTransfer(booking: InternalBooking) = bookResult.also { calls += "book" }

        override fun reserveForExternalTransfer(debit: ExternalDebit) = reserveResult.also { calls += "reserve" }

        override fun markSentToExternalBank(transferId: UUID) =
            TransferOutcome(TransferStatus.SENT_TO_EXTERNAL_BANK).also { calls += "markSent" }

        /** If set, `settleExternalTransfer` waits for it: the workflow is busy with the confirmation it got. */
        @Volatile var settleGate: CountDownLatch? = null

        val settleEntered = CountDownLatch(1)

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

        // The simulated external bank's steps: only executions from before SEPA Instant use them (replay test).
        override fun debitForExternalTransfer(debit: ExternalDebit) =
            TransferOutcome(TransferStatus.SENT_TO_EXTERNAL_BANK).also { calls += "debit" }

        override fun completeExternalTransfer(transferId: UUID) = TransferOutcome(TransferStatus.COMPLETED).also { calls += "complete" }

        override fun refundExternalTransfer(refund: Refund) = TransferOutcome(TransferStatus.REFUNDED, refund.reason).also {
            calls +=
                "refund"
        }

        override fun rejectTransfer(rejection: TransferRejection) =
            TransferOutcome(TransferStatus.REJECTED, rejection.reason).also { calls += "reject" }
    }

    class FakeRisk : RiskActivities {
        @Volatile var reviewRequired = false

        override fun assess(check: RiskCheck) =
            if (reviewRequired) RiskAssessment(true, "Amount exceeds approval limit") else RiskAssessment(false)
    }

    /** Receives every payment; answers inquiries as the test says (by default: still pending). */
    class FakeClearingHouse : ClearingHouseActivities {
        @Volatile var onSubmit: (InstantPayment) -> Submission = { Submission(received = true) }

        /** Called with the transfer ID and the number of the inquiry (1, 2, …). */
        @Volatile var onInquire: (
            UUID,
            Int,
        ) -> StatusReport = { transferId, _ -> StatusReport(Pacs002(UUID.randomUUID(), transferId, TxSts.PDNG)) }

        val submissions = CopyOnWriteArrayList<InstantPayment>()
        val submissionAttempts = AtomicInteger()
        val inquiries = AtomicInteger()

        override fun submit(payment: InstantPayment): Submission {
            submissionAttempts.incrementAndGet()
            return onSubmit(payment).also { submissions += payment }
        }

        override fun inquire(transferId: UUID) = onInquire(transferId, inquiries.incrementAndGet())
    }
}
