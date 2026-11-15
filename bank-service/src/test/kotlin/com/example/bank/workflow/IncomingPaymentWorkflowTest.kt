package com.example.bank.workflow

import com.example.bank.activities.PaymentAnswer
import com.example.bank.activities.StatusReport
import com.example.bank.activities.TransferOutcome
import com.example.bank.config.TemporalConfig
import com.example.payments.messages.Pacs002
import com.example.payments.messages.ReasonCodes
import com.example.payments.messages.TxSts
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bank as the creditor bank, with fakes and time skipping: the answer to the clearing house, the wait for its
 * settlement, and that the customer is credited only on ACSC. Whether an account can receive a payment is decided by
 * the ledger (`IncomingPaymentPolicy`, tested in the domain).
 */
class IncomingPaymentWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private val ledger = FakeLedger()
    private val clearing = FakeClearingHouse()

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(TemporalConfig.kotlinDataConverter()).build())
                .build(),
        )
        val worker = env.newWorker(BANK_TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(IncomingPaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(ledger, clearing)
        env.start()
        client = env.workflowClient
    }

    @AfterEach
    fun tearDown() = env.close()

    @Test
    fun `an accepted payment is credited once the clearing house settles it`() {
        val (workflow, stub) = start()
        awaitStatus(workflow, TransferStatus.ACCEPTED)
        assertEquals(listOf(PaymentAnswer(uetr, accepted = true)), clearing.answers)
        assertEquals(listOf("answer"), ledger.calls, "nothing is credited before the settlement")

        workflow.confirm(Pacs002(UUID.randomUUID(), uetr, TxSts.ACSC))

        assertEquals(TransferStatus.BOOKED, stub.getResult(TransferState::class.java).status)
        assertEquals(listOf("answer", "credit"), ledger.calls)
        assertEquals(0, clearing.inquiries.get())
    }

    @Test
    fun `a payment the account can't receive is answered with its reason code, and nothing waits`() {
        ledger.incomingAnswer = TransferOutcome(TransferStatus.REJECTED, "Account closed (AC04)", ReasonCodes.CLOSED_ACCOUNT)

        val result = run()

        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals(listOf(PaymentAnswer(uetr, accepted = false, reasonCode = ReasonCodes.CLOSED_ACCOUNT)), clearing.answers)
        assertEquals(listOf("answer"), ledger.calls)
        assertEquals(0, clearing.inquiries.get())
    }

    @Test
    fun `accepted, but the clearing house rejects it, so nothing is credited`() {
        val (workflow, stub) = start()
        awaitStatus(workflow, TransferStatus.ACCEPTED)

        workflow.confirm(Pacs002(UUID.randomUUID(), uetr, TxSts.RJCT, ReasonCodes.TIMEOUT_CREDITOR_AGENT))

        val result = stub.getResult(TransferState::class.java)
        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals(listOf("answer", "reject"), ledger.calls)
        assertEquals("The creditor bank did not answer in time (AB05)", ledger.rejection?.reason)
    }

    @Test
    fun `a lost confirmation is resolved by the status inquiry`() {
        clearing.onInquire = { id, _ -> StatusReport(Pacs002(UUID.randomUUID(), id, TxSts.ACSC)) }

        assertEquals(TransferStatus.BOOKED, run().status) // time skipping: the 20 seconds pass instantly
        assertEquals(listOf("answer", "credit"), ledger.calls)
        assertEquals(1, clearing.inquiries.get())
    }

    @Test
    fun `unknown at the clearing house is not final for a payment it forwarded`() {
        clearing.onInquire = { id, _ ->
            StatusReport(if ("investigate" in ledger.calls) Pacs002(UUID.randomUUID(), id, TxSts.ACSC) else null)
        }

        assertEquals(TransferStatus.BOOKED, run().status)
        assertEquals(listOf("answer", "investigate", "credit"), ledger.calls, "investigated, never rejected on a guess")
    }

    @Test
    fun `an answer that doesn't get through before the deadline leaves it to the clearing house`() {
        clearing.onAnswer = { error("clearing house unreachable") }
        clearing.onInquire = { id, _ -> StatusReport(Pacs002(UUID.randomUUID(), id, TxSts.RJCT, ReasonCodes.TIMEOUT_CREDITOR_AGENT)) }

        val result = run()

        assertEquals(TransferStatus.REJECTED, result.status)
        assertTrue(clearing.answerAttempts.get() > 1, "the answer is retried until the deadline")
        assertEquals(listOf("answer", "reject"), ledger.calls)
    }

    @Test
    fun `a confirmation that arrives before the answer's activity has returned still counts`() {
        val confirmed = CompletableFuture<Unit>()
        clearing.onAnswer = { answer ->
            Thread { confirmed.completeWith { confirm(answer.uetr, TxSts.ACSC) } }.start()
            Thread.sleep(500)
            true
        }

        val (_, stub) = start()
        // A refused confirmation must fail the test here: without it the workflow would ask forever (time skipping).
        confirmed.get(10, TimeUnit.SECONDS)
        assertEquals(TransferStatus.BOOKED, stub.getResult(TransferState::class.java).status)
        assertEquals(listOf("answer", "credit"), ledger.calls)
    }

    @Test
    fun `the validator only takes a final status for this payment`() {
        val (workflow, stub) = start()
        awaitStatus(workflow, TransferStatus.ACCEPTED)

        assertRefused("ACSC or RJCT") { workflow.confirm(Pacs002(UUID.randomUUID(), uetr, TxSts.PDNG)) }
        assertRefused("another payment") { workflow.confirm(Pacs002(UUID.randomUUID(), UUID.randomUUID(), TxSts.ACSC)) }

        workflow.confirm(Pacs002(UUID.randomUUID(), uetr, TxSts.ACSC))
        assertEquals(TransferStatus.BOOKED, stub.getResult(TransferState::class.java).status)
    }

    // --- helpers ---

    private val uetr = UUID.randomUUID()
    private val request = IncomingPaymentRequest(incomingPaymentId(uetr), uetr, 25_00, Instant.now()) // the test server starts at real time

    private fun run(): TransferState = start().second.getResult(TransferState::class.java)

    private fun start(): Pair<IncomingPaymentWorkflow, WorkflowStub> {
        val workflow = client.newWorkflowStub(
            IncomingPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(
                BANK_TASK_QUEUE,
            ).setWorkflowId(incomingPaymentWorkflowId(incomingPaymentId(uetr))).build(),
        )
        WorkflowClient.start(workflow::receive, request)
        return workflow to WorkflowStub.fromTyped(workflow)
    }

    /** What the bank's inbound endpoint does with the clearing house's pacs.002. */
    private fun confirm(uetr: UUID, txSts: TxSts) =
        client.newWorkflowStub(
            IncomingPaymentWorkflow::class.java,
            incomingPaymentWorkflowId(incomingPaymentId(uetr)),
        ).confirm(Pacs002(UUID.randomUUID(), uetr, txSts))

    private fun assertRefused(message: String, update: () -> Unit) {
        val error = assertThrows<WorkflowUpdateException> { update() }
        assertTrue(error.cause?.message.orEmpty().contains(message), "expected '$message', got: ${error.cause?.message}")
    }

    private fun awaitStatus(workflow: IncomingPaymentWorkflow, expected: TransferStatus) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching { workflow.status().status }.getOrNull() == expected) return
            Thread.sleep(20)
        }
        error("Status $expected not reached, last: ${runCatching { workflow.status() }.getOrNull()}")
    }
}
