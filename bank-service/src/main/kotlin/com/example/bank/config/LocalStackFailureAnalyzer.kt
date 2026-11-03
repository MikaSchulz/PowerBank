package com.example.bank.config

import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.boot.diagnostics.FailureAnalyzer
import java.sql.SQLException

/**
 * Turns "Postgres refuses the connection" at startup into a clear hint. The app doesn't start containers itself:
 * the local stack from compose.yaml is started and stopped by hand.
 */
class LocalStackFailureAnalyzer : FailureAnalyzer {

    override fun analyze(failure: Throwable): FailureAnalysis? {
        val connectionError = generateSequence(failure) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull { it.sqlState?.startsWith(CONNECTION_EXCEPTION) == true }
            ?: return null
        return FailureAnalysis(
            "Postgres is not reachable: ${connectionError.message}",
            "Start the local stack first: docker compose up -d --wait (and stop it later with docker compose down). " +
                "Outside the local setup, check spring.datasource.url.",
            connectionError,
        )
    }

    companion object {
        /** SQLSTATE class 08: connection exception, e.g. 08001 when the server refuses the connection. */
        private const val CONNECTION_EXCEPTION = "08"
    }
}
