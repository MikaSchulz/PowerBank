package com.example.clearing.workflow

import com.example.clearing.domain.PaymentStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The workflow's status (stored in its histories) mirrors the domain's by name. */
class ClearingStatusContractTest {
    @Test
    fun `the workflow knows exactly the domain's statuses`() =
        assertEquals(PaymentStatus.entries.map { it.name }, ClearingStatus.entries.map { it.name })
}
