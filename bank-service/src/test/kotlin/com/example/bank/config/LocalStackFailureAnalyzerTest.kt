package com.example.bank.config

import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalStackFailureAnalyzerTest {

    private val analyzer = LocalStackFailureAnalyzer()

    @Test
    fun `a refused Postgres connection points to the local stack`() {
        val refused = SQLException("Connection to localhost:5432 refused.", "08001")
        val startupFailure = IllegalStateException("Flyway failed", RuntimeException("Unable to obtain connection", refused))

        val analysis = assertNotNull(analyzer.analyze(startupFailure))

        assertTrue("localhost:5432 refused" in analysis.description, analysis.description)
        assertTrue("docker compose up -d --wait" in analysis.action.orEmpty(), analysis.action)
        assertEquals(refused, analysis.cause)
    }

    @Test
    fun `other database errors are left to Spring Boot`() {
        val duplicate = SQLException("duplicate key value violates unique constraint", "23505")

        assertNull(analyzer.analyze(RuntimeException(duplicate)))
        assertNull(analyzer.analyze(IllegalStateException("something else")))
    }
}
