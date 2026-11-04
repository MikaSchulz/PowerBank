package com.example.bank.workflow

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import com.example.bank.domain.transfer.TransferStatus as DomainTransferStatus

/**
 * The workflow's status (part of its query and update contract, stored in workflow histories) mirrors the domain's
 * by name: the ledger activities translate one into the other with `valueOf`.
 */
class TransferStatusContractTest {

    @Test
    fun `the workflow knows exactly the domain's statuses`() {
        assertEquals(DomainTransferStatus.entries.map { it.name }, TransferStatus.entries.map { it.name })
        assertEquals(DomainTransferStatus.entries.map { it.finished }, TransferStatus.entries.map { it.terminal })
    }
}
