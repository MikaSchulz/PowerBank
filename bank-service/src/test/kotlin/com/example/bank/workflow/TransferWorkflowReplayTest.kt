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
 * Replays recorded histories of the transfer workflow with the current code. If a change alters the order of the
 * workflow's steps, executions that were running before can't continue: the replay fails with a non-determinism error,
 * here instead of in production. Then the change needs `Workflow.getVersion` (decision 0007, stage 11).
 *
 * The histories are recorded with [TransferWorkflowHistoryRecorder]; one of them ends while the transfer waits for its
 * approval, like an execution that is waiting during a deployment.
 */
class TransferWorkflowReplayTest {

    private val histories = File("src/test/resources/histories/transfer").listFiles { file -> file.extension == "json" }
        .orEmpty()
        .sortedBy { it.name }

    @TestFactory
    fun `recorded histories replay with the current code`(): List<DynamicTest> {
        assertTrue(histories.isNotEmpty(), "no recorded histories found")
        return histories.map { file ->
            DynamicTest.dynamicTest(file.nameWithoutExtension) {
                val env = TestWorkflowEnvironment.newInstance(
                    TestEnvironmentOptions.newBuilder()
                        .setWorkflowClientOptions(
                            WorkflowClientOptions.newBuilder().setDataConverter(TemporalConfig.kotlinDataConverter()).build(),
                        )
                        .build(),
                )
                env.use {
                    WorkflowReplayer.replayWorkflowExecution(
                        WorkflowExecutionHistory.fromJson(file.readText()),
                        env,
                        TransferWorkflowImpl::class.java,
                    )
                }
            }
        }
    }
}
