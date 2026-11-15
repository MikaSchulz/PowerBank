package com.example.clearing.workflow

import com.example.clearing.activities.BankMessagingActivities
import com.example.clearing.activities.Confirmation
import com.example.clearing.activities.CreditorAnswer
import com.example.clearing.activities.Forward
import com.example.clearing.activities.PaymentOutcome
import com.example.clearing.activities.SettlementActivities
import com.example.clearing.config.ClearingInfrastructure
import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
import com.example.payments.messages.ReasonCodes
import com.example.payments.messages.TxSts
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/**
 * The clearing process with fakes and time skipping: the order of the steps, the deadline, and which bank gets which
 * confirmation. The status rules are tested in PaymentDeciderTest.
 */
class PaymentWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private val settlement = FakeSettlement()
    private val banks = FakeBanks()

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder().setDataConverter(ClearingInfrastructure.kotlinDataConverter()).build(),
                )
                .build(),
        )
        val worker = env.newWorker(CLEARING_TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(settlement, banks)
        env.start()
        client = env.workflowClient
    }

    @AfterEach
    fun tearDown() = env.close()

    @Test
    fun `the creditor bank accepts, the payment is settled and both banks get ACSC`() {
        banks.onForward = { answer(it.uetr, TxSts.ACCP) }

        val result = run()

        assertEquals(ClearingStatus.SETTLED, result.status)
        assertEquals(listOf("reserve", "markForwarded", "recordAnswer", "settle"), settlement.calls)
        assertEquals(listOf("A" to TxSts.ACSC, "B" to TxSts.ACSC), banks.confirmations())
    }

    @Test
    fun `the creditor bank rejects, both banks get RJCT with its reason`() {
        banks.onForward = { answer(it.uetr, TxSts.RJCT, ReasonCodes.CLOSED_ACCOUNT) }

        val result = run()

        assertEquals(ClearingStatus.REJECTED, result.status)
        assertEquals(ReasonCodes.CLOSED_ACCOUNT, result.reasonCode)
        assertEquals(listOf("A" to TxSts.RJCT, "B" to TxSts.RJCT), banks.confirmations())
    }

    @Test
    fun `no answer within the deadline rejects with AB05`() {
        val result = run() // time skipping: the 10 seconds pass instantly

        assertEquals(ClearingStatus.REJECTED, result.status)
        assertEquals(ReasonCodes.TIMEOUT_CREDITOR_AGENT, result.reasonCode)
        assertEquals(listOf("reserve", "markForwarded", "expire"), settlement.calls)
        assertEquals(listOf("A" to TxSts.RJCT, "B" to TxSts.RJCT), banks.confirmations())
    }

    @Test
    fun `without liquidity nothing is forwarded, only the debtor bank learns it`() {
        settlement.reserveResult = PaymentOutcome(ClearingStatus.REJECTED, ReasonCodes.INSUFFICIENT_FUNDS)

        val result = run()

        assertEquals(ReasonCodes.INSUFFICIENT_FUNDS, result.reasonCode)
        assertEquals(0, banks.forwards)
        assertEquals(listOf("A" to TxSts.RJCT), banks.confirmations())
    }

    @Test
    fun `an unreachable creditor bank is treated like a missing answer`() {
        banks.forwardFails = true

        val result = run()

        assertEquals(ReasonCodes.TIMEOUT_CREDITOR_AGENT, result.reasonCode)
        assertEquals(listOf("A" to TxSts.RJCT), banks.confirmations(), "the creditor bank never got the payment")
    }

    @Test
    fun `an answer that arrives before the forward has returned still counts`() {
        banks.onForward = { pacs008 ->
            // The creditor bank answers during the forward call, before the workflow knows it was delivered.
            Thread { answer(pacs008.uetr, TxSts.ACCP) }.start()
            Thread.sleep(500)
        }

        assertEquals(ClearingStatus.SETTLED, run().status)
    }

    // --- helpers ---

    private val pacs008 = Pacs008(
        msgId = UUID.randomUUID(),
        uetr = UUID.randomUUID(),
        endToEndId = "transfer-1",
        acceptanceDateTime = Instant.parse("2026-11-08T10:00:00Z"),
        amountCents = 100_00,
        debtorIban = "DE00990000011000000001",
        debtorAgentBic = "PWRBDEFFXXX",
        creditorIban = "DE00990000021000000001",
    )

    private lateinit var stub: WorkflowStub

    private fun run(): PaymentState {
        val workflow = client.newWorkflowStub(
            PaymentWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(CLEARING_TASK_QUEUE).setWorkflowId(paymentWorkflowId(pacs008.uetr)).build(),
        )
        stub = WorkflowStub.fromTyped(workflow)
        WorkflowClient.start(workflow::clear, ClearingRequest(pacs008, "A", "B", deadlineMillis = 10_000))
        return stub.getResult(PaymentState::class.java)
    }

    private fun answer(uetr: UUID, txSts: TxSts, reason: String? = null) {
        client.newWorkflowStub(
            PaymentWorkflow::class.java,
            paymentWorkflowId(uetr),
        ).creditorAnswer(Pacs002(UUID.randomUUID(), uetr, txSts, reason))
    }

    class FakeSettlement : SettlementActivities {
        val calls = CopyOnWriteArrayList<String>()

        @Volatile var reserveResult = PaymentOutcome(ClearingStatus.RESERVED)

        override fun reserve(uetr: UUID) = reserveResult.also { calls += "reserve" }

        override fun markForwarded(uetr: UUID) = PaymentOutcome(ClearingStatus.FORWARDED).also { calls += "markForwarded" }

        override fun recordAnswer(answer: CreditorAnswer) = (
            if (answer.accepted) PaymentOutcome(ClearingStatus.ACCEPTED) else PaymentOutcome(ClearingStatus.REJECTED, answer.reasonCode)
            ).also { calls += "recordAnswer" }

        override fun expire(uetr: UUID) = PaymentOutcome(ClearingStatus.REJECTED, ReasonCodes.TIMEOUT_CREDITOR_AGENT).also {
            calls +=
                "expire"
        }

        override fun settle(uetr: UUID) = PaymentOutcome(ClearingStatus.SETTLED).also { calls += "settle" }
    }

    class FakeBanks : BankMessagingActivities {
        @Volatile var onForward: (Pacs008) -> Unit = {}

        @Volatile var forwardFails = false

        @Volatile var forwards = 0

        private val confirmed = CopyOnWriteArrayList<Confirmation>()

        override fun forward(forward: Forward) {
            forwards++
            if (forwardFails) error("creditor bank unreachable")
            onForward(forward.pacs008)
        }

        override fun confirm(confirmation: Confirmation) {
            confirmed += confirmation
        }

        fun confirmations() = confirmed.map { it.bank to it.pacs002.txSts }
    }
}
