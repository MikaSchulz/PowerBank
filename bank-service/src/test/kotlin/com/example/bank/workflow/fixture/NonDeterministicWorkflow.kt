package com.example.bank.workflow.fixture

import com.example.bank.ledger.LedgerQueries
import java.time.Instant
import java.util.UUID

/** Deliberately broken workflow code: only used to prove that the architecture rules catch it. */
@Suppress("unused")
class NonDeterministicWorkflow(private val queries: LedgerQueries) {
    fun run(): String {
        Thread.sleep(10)
        return "${Instant.now()} ${UUID.randomUUID()} ${java.util.Random().nextInt()}"
    }
}
