package com.example.bank.workflow

import com.example.bank.activities.ApprovalRequest
import com.example.bank.activities.ApprovalVerdict
import com.example.bank.activities.ExternalBankActivities
import com.example.bank.activities.ExternalBankAnswer
import com.example.bank.activities.ExternalDebit
import com.example.bank.activities.ExternalPayment
import com.example.bank.activities.InternalBooking
import com.example.bank.activities.LedgerActivities
import com.example.bank.activities.Refund
import com.example.bank.activities.RiskActivities
import com.example.bank.activities.RiskAssessment
import com.example.bank.activities.RiskCheck
import com.example.bank.activities.TransferOutcome
import com.example.bank.activities.TransferRejection
import com.example.bank.config.TemporalConfig
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Workflow tests without Spring and without a database: in-memory test server with time skipping,
 * activities as fakes. Days of approval deadline pass in milliseconds.
 *
 * They test the orchestration: which ledger step comes when, what it gets, and that the workflow takes over the
 * status the ledger decided. The status rules themselves are tested in the transfer decider (`TransferDeciderTest`).
 */
class TransferWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private val ledger = FakeLedger()
    private val risk = FakeRisk()
    private val bank = FakeExternalBank()

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
        worker.registerActivitiesImplementations(ledger, risk, bank)
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
    fun `external bank accepts`() {
        val result = run(external(50_00))

        assertEquals(TransferStatus.COMPLETED, result.status)
        assertEquals(listOf("debit", "complete"), ledger.calls)
    }

    @Test
    fun `external bank rejects and the debit is refunded`() {
        bank.answer = ExternalBankAnswer(accepted = false, reason = "Creditor account closed")

        val result = run(external(50_00))

        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("Creditor account closed", result.reason)
        assertEquals(listOf("debit", "refund"), ledger.calls)
    }

    @Test
    fun `without sufficient funds nothing is sent to the external bank`() {
        ledger.debitResult = TransferOutcome(TransferStatus.REJECTED, "Insufficient funds")

        val result = run(external(50_00))

        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals(0, bank.attempts)
    }

    @Test
    fun `technical errors of the external bank are retried`() {
        bank.failuresBeforeSuccess = 3

        val result = run(external(50_00))

        assertEquals(TransferStatus.COMPLETED, result.status)
        assertEquals(4, bank.attempts)
    }

    @Test
    fun `if the external bank stays unreachable for ten minutes, the debit is refunded`() {
        bank.failuresBeforeSuccess = Int.MAX_VALUE

        val result = run(external(50_00))

        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("External bank unreachable", result.reason)
        assertEquals(listOf("debit", "refund"), ledger.calls)
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
        bank.failuresBeforeSuccess = Int.MAX_VALUE // workflow is stuck at the external bank
        val (workflow, stub) = start(external(50_00))
        awaitStatus(workflow, TransferStatus.SENT_TO_EXTERNAL_BANK)

        val error = assertThrows<WorkflowUpdateException> { workflow.approve(ApprovalDecision(approved = true)) }
        assertTrue(error.cause?.message.orEmpty().contains("not awaiting approval"))

        bank.failuresBeforeSuccess = 0
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

        @Volatile var debitResult = TransferOutcome(TransferStatus.SENT_TO_EXTERNAL_BANK)

        @Volatile var verdict: ApprovalVerdict? = null

        override fun requireApproval(request: ApprovalRequest) =
            TransferOutcome(TransferStatus.AWAITING_APPROVAL, request.reason).also { calls += "requireApproval" }

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

        override fun debitForExternalTransfer(debit: ExternalDebit) = debitResult.also { calls += "debit" }

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

    class FakeExternalBank : ExternalBankActivities {
        @Volatile var answer = ExternalBankAnswer(accepted = true)

        @Volatile var failuresBeforeSuccess = 0

        @Volatile var attempts = 0

        override fun submitPayment(payment: ExternalPayment): ExternalBankAnswer {
            attempts++
            if (attempts <= failuresBeforeSuccess) error("external bank unreachable")
            return answer
        }
    }
}
