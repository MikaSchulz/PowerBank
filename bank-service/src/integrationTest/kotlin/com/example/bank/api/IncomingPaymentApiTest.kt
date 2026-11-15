package com.example.bank.api

import com.example.bank.FakeClearingHouse
import com.example.bank.IntegrationTest
import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.SystemAccounts
import com.example.bank.workflow.TransferState
import com.example.bank.workflow.TransferStatus
import com.example.bank.workflow.incomingPaymentId
import com.example.bank.workflow.incomingPaymentWorkflowId
import com.example.payments.messages.Pacs002
import com.example.payments.messages.ReasonCodes
import com.example.payments.messages.TxSts
import com.jayway.jsonpath.JsonPath
import io.temporal.client.WorkflowClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The bank as the creditor bank, end to end: the clearing house's pacs.008 at the inbound endpoint → the incoming
 * payment workflow → the answer at the (fake) clearing house → the confirmation → the ledger.
 */
@IntegrationTest
class IncomingPaymentApiTest @Autowired constructor(
    private val mvc: MockMvc,
    private val client: WorkflowClient,
    private val queries: LedgerQueries,
    private val clearingHouse: FakeClearingHouse,
) {

    @AfterEach
    fun `sum of all postings stays zero`() {
        clearingHouse.reset()
        assertEquals(0, queries.sumOfAllPostings())
    }

    @Test
    fun `a payment from another bank is accepted and credited once the clearing house settles it`() {
        val (account, iban) = openAccount()
        val settlementBefore = balance(SystemAccounts.SETTLEMENT.value)
        val uetr = UUID.randomUUID()

        receive(uetr, iban, 42_00)
        assertEquals(TxSts.ACCP, awaitAnswer(uetr).txSts)
        assertEquals(0, balance(account), "nothing is credited before the settlement")

        confirm(uetr, TxSts.ACSC).andExpect { status { isAccepted() } }

        assertEquals(TransferStatus.BOOKED, awaitResult(uetr).status)
        assertEquals(42_00, balance(account))
        assertEquals(settlementBefore - 42_00, balance(SystemAccounts.SETTLEMENT.value))
        assertEquals(
            listOf(SystemAccounts.SETTLEMENT.value to "debit", account to "credit"),
            queries.postingsOfTransfer(TransferId(incomingPaymentId(uetr))).map { it.accountId to it.leg },
        )
        confirm(uetr, TxSts.ACSC).andExpect { status { isAccepted() } } // a repeated confirmation is fine
    }

    @Test
    fun `the same payment twice is answered and credited once`() {
        val (account, iban) = openAccount()
        val uetr = UUID.randomUUID()

        repeat(2) { receive(uetr, iban, 10_00) }
        awaitAnswer(uetr)
        confirm(uetr, TxSts.ACSC).andExpect { status { isAccepted() } }
        awaitResult(uetr)
        receive(uetr, iban, 10_00)

        assertEquals(1, clearingHouse.answers.count { it.uetr == uetr })
        assertEquals(10_00, balance(account))
    }

    @Test
    fun `a payment to an account the bank doesn't have is rejected with AC01, nothing booked`() {
        val uetr = UUID.randomUUID()

        receive(uetr, "DE89370400440532013000", 10_00) // not an IBAN of this bank

        val answer = awaitAnswer(uetr)
        assertEquals(TxSts.RJCT, answer.txSts)
        assertEquals(ReasonCodes.INCORRECT_ACCOUNT, answer.reasonCode)
        val result = awaitResult(uetr)
        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals("Incorrect account number (AC01)", result.reason)
        assertEquals(emptyList(), queries.postingsOfTransfer(TransferId(incomingPaymentId(uetr))))
        confirm(uetr, TxSts.RJCT).andExpect { status { isAccepted() } } // the clearing house's rejection to both banks
    }

    @Test
    fun `a payment to a blocked account is rejected with AC06`() {
        val (account, iban) = openAccount()
        mvc.post("/api/backoffice/accounts/$account/block").andExpect { status { isOk() } }
        val uetr = UUID.randomUUID()

        receive(uetr, iban, 10_00)

        assertEquals(ReasonCodes.BLOCKED_ACCOUNT, awaitAnswer(uetr).reasonCode)
        assertEquals(TransferStatus.REJECTED, awaitResult(uetr).status)
        assertEquals(0, balance(account))
    }

    @Test
    fun `a payment to a closed account is rejected with AC04`() {
        val (account, iban) = openAccount()
        mvc.post("/api/backoffice/accounts/$account/close").andExpect { status { isOk() } }
        val uetr = UUID.randomUUID()

        receive(uetr, iban, 10_00)

        assertEquals(ReasonCodes.CLOSED_ACCOUNT, awaitAnswer(uetr).reasonCode)
        assertEquals(TransferStatus.REJECTED, awaitResult(uetr).status)
    }

    @Test
    fun `accepted, but rejected by the clearing house, nothing is booked`() {
        val (account, iban) = openAccount()
        val uetr = UUID.randomUUID()
        receive(uetr, iban, 10_00)
        awaitAnswer(uetr)

        confirm(uetr, TxSts.RJCT, ReasonCodes.TIMEOUT_CREDITOR_AGENT).andExpect { status { isAccepted() } }

        val result = awaitResult(uetr)
        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals("The creditor bank did not answer in time (AB05)", result.reason)
        assertEquals(0, balance(account))
        confirm(uetr, TxSts.ACSC).andExpect { status { isConflict() } } // contradicts the final status
    }

    // --- helpers ---

    private fun openAccount(): Pair<UUID, String> {
        val json = mvc.post("/api/backoffice/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(json, "$.id")) to JsonPath.read(json, "$.iban")
    }

    /** What the clearing house does: forward a pacs.008 to the creditor bank. */
    private fun receive(uetr: UUID, creditorIban: String, amountCents: Long) {
        mvc.post("/api/clearing/inbound/pacs008") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"msgId":"${UUID.randomUUID()}","uetr":"$uetr","endToEndId":"e2e","acceptanceDateTime":"${Instant.now()}",
                 "amountCents":$amountCents,"currency":"EUR","debtorIban":"DE38990000021000000001","debtorAgentBic":"PTNRDEFFXXX",
                 "creditorIban":"$creditorIban"}
            """.trimIndent()
        }.andExpect { status { isAccepted() } }
    }

    private fun confirm(uetr: UUID, txSts: TxSts, reasonCode: String? = null) = mvc.post("/api/clearing/inbound/pacs002") {
        contentType = MediaType.APPLICATION_JSON
        content =
            """{"msgId":"${UUID.randomUUID()}","uetr":"$uetr","txSts":"$txSts"${reasonCode?.let { ""","reasonCode":"$it"""" } ?: ""}}"""
    }

    private fun awaitAnswer(uetr: UUID): Pacs002 {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            clearingHouse.answers.firstOrNull { it.uetr == uetr }?.let { return it }
            Thread.sleep(50)
        }
        error("No answer for $uetr")
    }

    private fun awaitResult(uetr: UUID): TransferState =
        client.newUntypedWorkflowStub(incomingPaymentWorkflowId(incomingPaymentId(uetr))).getResult(TransferState::class.java)

    private fun balance(account: UUID) = queries.balanceOf(AccountId(account))
}
