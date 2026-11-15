package com.example.bank.workflow

import com.example.bank.config.TemporalConfig
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertTrue

/**
 * Replays recorded histories of the bank's workflows with the current code. If a change alters the order of a
 * workflow's steps, executions that were running before can't continue: the replay fails with a non-determinism error,
 * here instead of in production. Then the change needs `Workflow.getVersion` (decision 0007, stage 11).
 *
 * The histories are recorded with the `*HistoryRecorder` tests; some of them end while the workflow waits (for an
 * approval, under investigation), like an execution that is waiting during a deployment.
 */
class WorkflowReplayTest {

    /** Where each workflow's histories are. */
    private val workflows = mapOf(
        "transfer" to TransferWorkflowImpl::class.java,
        "incoming-payment" to IncomingPaymentWorkflowImpl::class.java,
    )

    @TestFactory
    fun `recorded histories replay with the current code`(): List<DynamicTest> = workflows.flatMap { (directory, implementation) ->
        val histories = File("src/test/resources/histories/$directory").listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue(histories.isNotEmpty(), "no recorded histories found for $directory")
        histories.map { file ->
            DynamicTest.dynamicTest("$directory/${file.nameWithoutExtension}") {
                val env = TestWorkflowEnvironment.newInstance(
                    TestEnvironmentOptions.newBuilder()
                        .setWorkflowClientOptions(
                            WorkflowClientOptions.newBuilder().setDataConverter(TemporalConfig.kotlinDataConverter()).build(),
                        )
                        .build(),
                )
                env.use {
                    WorkflowReplayer.replayWorkflowExecution(WorkflowExecutionHistory.fromJson(file.readText()), env, implementation)
                }
            }
        }
    }
}
