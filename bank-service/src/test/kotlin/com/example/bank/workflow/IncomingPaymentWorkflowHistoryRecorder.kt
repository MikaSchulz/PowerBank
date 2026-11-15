package com.example.bank.workflow

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
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Records histories of the incoming payment workflow for [WorkflowReplayTest]. Runs only on request:
 *
 * ```
 * RECORD_HISTORIES=v2 ./gradlew :bank-service:test --tests '*IncomingPaymentWorkflowHistoryRecorder' --rerun
 * ```
 *
 * Record before changing the workflow and keep the old files; an existing file is never overwritten.
 */
@EnabledIfEnvironmentVariable(named = "RECORD_HISTORIES", matches = "v\\d+")
class IncomingPaymentWorkflowHistoryRecorder {

    private val prefix = System.getenv("RECORD_HISTORIES")
    private val target = File("src/test/resources/histories/incoming-payment")

    @Test
    fun `record histories`() {
        record("credited") { _, client ->
            val started = start(client)
            awaitStatus(started.workflow, TransferStatus.ACCEPTED)
            started.confirm(TxSts.ACSC)
            finish(started)
        }
        record("rejected-by-the-bank", ledger = {
            incomingAnswer = TransferOutcome(TransferStatus.REJECTED, "Account closed (AC04)", ReasonCodes.CLOSED_ACCOUNT)
        }) { _, client -> finish(start(client)) }
        record("rejected-by-the-clearing-house") { _, client ->
            val started = start(client)
            awaitStatus(started.workflow, TransferStatus.ACCEPTED)
            started.confirm(TxSts.RJCT, ReasonCodes.TIMEOUT_CREDITOR_AGENT)
            finish(started)
        }
        record("credited-after-inquiry", clearing = {
            onInquire =
                { id, n -> StatusReport(Pacs002(UUID.randomUUID(), id, if (n < 2) TxSts.PDNG else TxSts.ACSC)) }
        }) { _, client ->
            finish(start(client))
        }
        // Still running: under investigation, like an execution that is waiting during a deployment.
        record("under-investigation", clearing = { onInquire = { _, _ -> error("clearing house down") } }) { env, client ->
            val started = start(client)
            awaitStatus(started.workflow, TransferStatus.ACCEPTED)
            env.sleep(ClearingHouseWait.CONFIRMATION_TIMEOUT + ClearingHouseWait.INQUIRY_PERIOD + Duration.ofMinutes(2))
            awaitStatus(started.workflow, TransferStatus.UNDER_INVESTIGATION)
            started
        }
    }

    private fun record(
        name: String,
        ledger: FakeLedger.() -> Unit = {},
        clearing: FakeClearingHouse.() -> Unit = {},
        scenario: (TestWorkflowEnvironment, WorkflowClient) -> Started,
    ) {
        val env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(TemporalConfig.kotlinDataConverter()).build())
                .build(),
        )
        env.use {
            val worker = env.newWorker(BANK_TASK_QUEUE)
            worker.registerWorkflowImplementationTypes(IncomingPaymentWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(FakeLedger().apply(ledger), FakeClearingHouse().apply(clearing))
            env.start()
            val started = scenario(env, env.workflowClient)
            val history = env.workflowClient.fetchHistory(checkNotNull(started.stub.execution).workflowId)
            val file = File(target, "$prefix-$name.json")
            check(!file.exists()) { "$file exists: old histories stay, record with a new prefix" }
            file.writeText(history.toJson(true))
        }
    }

    private class Started(val workflow: IncomingPaymentWorkflow, val stub: WorkflowStub, val uetr: UUID) {
        fun confirm(txSts: TxSts, reasonCode: String? = null) = workflow.confirm(Pacs002(UUID.randomUUID(), uetr, txSts, reasonCode))
    }

    private fun start(client: WorkflowClient): Started {
        val uetr = UUID.randomUUID()
        val workflow = client.newWorkflowStub(
            IncomingPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(
                BANK_TASK_QUEUE,
            ).setWorkflowId(incomingPaymentWorkflowId(incomingPaymentId(uetr))).build(),
        )
        WorkflowClient.start(workflow::receive, IncomingPaymentRequest(incomingPaymentId(uetr), uetr, 25_00, Instant.now()))
        return Started(workflow, WorkflowStub.fromTyped(workflow), uetr)
    }

    private fun finish(started: Started): Started {
        started.stub.getResult(TransferState::class.java)
        return started
    }

    private fun awaitStatus(workflow: IncomingPaymentWorkflow, expected: TransferStatus) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching { workflow.status().status }.getOrNull() == expected) return
            Thread.sleep(20)
        }
        error("Status $expected not reached")
    }
}
