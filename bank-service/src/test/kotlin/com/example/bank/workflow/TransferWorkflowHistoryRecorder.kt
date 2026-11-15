package com.example.bank.workflow

import com.example.bank.activities.StatusReport
import com.example.bank.activities.Submission
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
import java.util.UUID

/**
 * Records histories of the transfer workflow for [TransferWorkflowReplayTest]. Runs only on request:
 *
 * ```
 * RECORD_HISTORIES=v2 ./gradlew :bank-service:test --tests '*HistoryRecorder' --rerun
 * ```
 *
 * Record before changing the workflow and keep the old files: the replay test then proves that executions started
 * with the old code still run with the new one. The value of `RECORD_HISTORIES` is the prefix of the file names.
 */
@EnabledIfEnvironmentVariable(named = "RECORD_HISTORIES", matches = "v\\d+")
class TransferWorkflowHistoryRecorder {

    private val prefix = System.getenv("RECORD_HISTORIES")
    private val target = File("src/test/resources/histories/transfer")
    private val debtor = UUID.randomUUID()
    private val creditor = UUID.randomUUID()

    @Test
    fun `record histories`() {
        record("internal-booked") { _, client -> finish(start(client, internal(50_00))) }
        record("approval-approved") { _, client ->
            val (workflow, stub) = start(client, internal(20_000_00))
            awaitStatus(workflow, TransferStatus.AWAITING_APPROVAL)
            workflow.approve(ApprovalDecision(approved = true, comment = "checked"))
            finish(workflow to stub)
        }
        record("approval-denied") { _, client ->
            val (workflow, stub) = start(client, internal(20_000_00))
            awaitStatus(workflow, TransferStatus.AWAITING_APPROVAL)
            workflow.approve(ApprovalDecision(approved = false, comment = "phishing"))
            finish(workflow to stub)
        }
        record("approval-expired") { _, client -> finish(start(client, internal(20_000_00))) }
        // Still running: the history ends while the workflow waits, like an execution that is waiting during a deployment.
        record("approval-waiting") { _, client ->
            val started = start(client, internal(20_000_00))
            awaitStatus(started.first, TransferStatus.AWAITING_APPROVAL)
            started
        }
        record("external-settled") { _, client ->
            val started = start(client, external(50_00))
            awaitStatus(started.first, TransferStatus.SENT_TO_EXTERNAL_BANK)
            started.first.confirm(Pacs002(UUID.randomUUID(), started.first.status().transferId, TxSts.ACSC))
            finish(started)
        }
        record("external-rejected") { _, client ->
            val started = start(client, external(50_00))
            awaitStatus(started.first, TransferStatus.SENT_TO_EXTERNAL_BANK)
            started.first.confirm(Pacs002(UUID.randomUUID(), started.first.status().transferId, TxSts.RJCT, ReasonCodes.CLOSED_ACCOUNT))
            finish(started)
        }
        record("external-refused", clearing = { onSubmit = { Submission(received = false, reason = "Refused (HTTP 422)") } }) { _, client ->
            finish(start(client, external(50_00)))
        }
        record("external-settled-after-inquiry", clearing = {
            onInquire = { id, n ->
                StatusReport(
                    if (n <
                        3
                    ) {
                        pending(id)
                    } else {
                        settled(id)
                    },
                )
            }
        }) { _, client ->
            finish(start(client, external(50_00)))
        }
        record(
            "external-not-received",
            clearing = {
                onSubmit = { error("clearing house unreachable") }
                onInquire = { _, _ -> StatusReport(status = null) }
            },
        ) { _, client -> finish(start(client, external(50_00))) }
        // Still running: under investigation, like an execution that is waiting during a deployment.
        record("external-under-investigation", clearing = { onInquire = { _, _ -> error("clearing house down") } }) { env, client ->
            val started = start(client, external(50_00))
            awaitStatus(started.first, TransferStatus.SENT_TO_EXTERNAL_BANK)
            env.sleep(TransferWorkflowImpl.CONFIRMATION_TIMEOUT + TransferWorkflowImpl.INQUIRY_PERIOD + Duration.ofMinutes(2))
            awaitStatus(started.first, TransferStatus.UNDER_INVESTIGATION)
            started
        }
    }

    private fun pending(id: UUID) = Pacs002(UUID.randomUUID(), id, TxSts.PDNG)

    private fun settled(id: UUID) = Pacs002(UUID.randomUUID(), id, TxSts.ACSC)

    private fun record(
        name: String,
        clearing: TransferWorkflowTest.FakeClearingHouse.() -> Unit = {},
        scenario: (TestWorkflowEnvironment, WorkflowClient) -> Pair<TransferWorkflow, WorkflowStub>,
    ) {
        val risk = TransferWorkflowTest.FakeRisk()
        val clearingHouse = TransferWorkflowTest.FakeClearingHouse().apply(clearing)
        if (name.startsWith("approval")) risk.reviewRequired = true
        val env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(TemporalConfig.kotlinDataConverter()).build())
                .build(),
        )
        env.use {
            val worker = env.newWorker(BANK_TASK_QUEUE)
            worker.registerWorkflowImplementationTypes(TransferWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(TransferWorkflowTest.FakeLedger(), risk, clearingHouse)
            env.start()
            val (_, stub) = scenario(env, env.workflowClient)
            val history = env.workflowClient.fetchHistory(checkNotNull(stub.execution).workflowId)
            File(target, "$prefix-$name.json").writeText(history.toJson(true))
        }
    }

    private fun internal(amount: Long) = TransferRequest(UUID.randomUUID(), debtor, amount, creditorAccountId = creditor)

    private fun external(amount: Long) = TransferRequest(UUID.randomUUID(), debtor, amount, creditorIban = "DE89370400440532013000")

    private fun start(client: WorkflowClient, request: TransferRequest): Pair<TransferWorkflow, WorkflowStub> {
        val workflow = client.newWorkflowStub(
            TransferWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(BANK_TASK_QUEUE).setWorkflowId(transferWorkflowId(request.transferId)).build(),
        )
        WorkflowClient.start(workflow::transfer, request)
        return workflow to WorkflowStub.fromTyped(workflow)
    }

    private fun finish(started: Pair<TransferWorkflow, WorkflowStub>): Pair<TransferWorkflow, WorkflowStub> {
        started.second.getResult(TransferState::class.java)
        return started
    }

    private fun awaitStatus(workflow: TransferWorkflow, expected: TransferStatus) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching { workflow.status().status }.getOrNull() == expected) return
            Thread.sleep(20)
        }
        error("Status $expected not reached")
    }
}
