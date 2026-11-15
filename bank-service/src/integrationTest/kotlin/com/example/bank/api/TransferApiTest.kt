package com.example.bank.api

import com.example.bank.FakeClearingHouse
import com.example.bank.IntegrationTest
import com.example.bank.config.BankIdentity
import com.example.bank.domain.AccountId
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.SystemAccounts
import com.example.bank.workflow.TransferState
import com.example.bank.workflow.TransferStatus
import com.example.bank.workflow.transferWorkflowId
import com.example.payments.messages.ReasonCodes
import com.example.payments.messages.TxSts
import com.jayway.jsonpath.JsonPath
import io.micrometer.core.instrument.MeterRegistry
import io.temporal.client.WorkflowClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End to end through the areas' APIs: customer (online banking), back office, operations → TransferWorkflow (Temporal)
 * → activities → ledger (Postgres).
 */
@IntegrationTest
class TransferApiTest @Autowired constructor(
    private val mvc: MockMvc,
    private val client: WorkflowClient,
    private val queries: LedgerQueries,
    private val registry: MeterRegistry,
    private val clearingHouse: FakeClearingHouse,
    private val identity: BankIdentity,
) {

    @AfterEach
    fun `sum of all postings stays zero`() {
        clearingHouse.reset()
        assertEquals(0, queries.sumOfAllPostings())
    }

    @Test
    fun `open accounts, deposit and transfer internally`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 100_00)

        val transferId = send(alice, ibanOf(bob), 2500)

        assertEquals(TransferStatus.BOOKED, awaitResult(transferId).status)
        assertEquals(75_00, balance(alice))
        assertEquals(25_00, balance(bob))
        mvc.get("/api/backoffice/transfers/$transferId").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("BOOKED") }
        }
    }

    @Test
    fun `submitting twice with the same transfer ID is rejected`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 100_00)
        val transferId = UUID.randomUUID()

        send(alice, ibanOf(bob), 1000, transferId)
        awaitResult(transferId)
        sendRequest(alice, ibanOf(bob), 1000, transferId).andExpect { status { isConflict() } }

        assertEquals(90_00, balance(alice))
        assertEquals(10_00, balance(bob))
    }

    @Test
    fun `concurrent transfers from the same account never overdraw it`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 100_00)
        val pool = Executors.newFixedThreadPool(8)

        val ids = pool.invokeAll(
            (1..10).map {
                Callable { send(alice, ibanOf(bob), 3000) }
            },
        ).map { it.get() }
        pool.shutdown()
        val results = ids.map { awaitResult(it) }

        assertEquals(3, results.count { it.status == TransferStatus.BOOKED })
        assertTrue(results.filter { it.status != TransferStatus.BOOKED }.all { it.reason == "Insufficient funds" })
        assertEquals(10_00, balance(alice))
    }

    @Test
    fun `an external transfer goes to the clearing house and is settled once it confirms`() {
        val alice = openAccount()
        deposit(alice, 100_00)
        val clearingBefore = balance(SystemAccounts.EXTERNAL_CLEARING.value)
        val settlementBefore = balance(SystemAccounts.SETTLEMENT.value)

        val transferId = send(alice, "DE89 3704 0044 0532 0130 00", 4000)
        awaitStatus(transferId, "SENT_TO_EXTERNAL_BANK")
        assertEquals(60_00, balance(alice), "reserved")
        assertEquals(clearingBefore + 40_00, balance(SystemAccounts.EXTERNAL_CLEARING.value))
        assertEquals("DE89370400440532013000", clearingHouse.payments.single { it.uetr == transferId }.creditorIban)

        confirm(transferId, TxSts.ACSC).andExpect { status { isAccepted() } }

        assertEquals(TransferStatus.COMPLETED, awaitResult(transferId).status)
        assertEquals(60_00, balance(alice))
        assertEquals(clearingBefore, balance(SystemAccounts.EXTERNAL_CLEARING.value))
        assertEquals(settlementBefore + 40_00, balance(SystemAccounts.SETTLEMENT.value))
        assertEquals(
            listOf("RECEIVED", "RESERVED", "SENT_TO_EXTERNAL_BANK", "COMPLETED"),
            read<List<String>>("/api/backoffice/transfers/$transferId/history", "$[*].status"),
        )
        confirm(transferId, TxSts.ACSC).andExpect { status { isAccepted() } } // a repeated confirmation is fine
        confirm(transferId, TxSts.RJCT).andExpect { status { isConflict() } } // a contradicting one is not
    }

    @Test
    fun `a rejection by the creditor bank refunds the reservation with a readable reason`() {
        val alice = openAccount()
        deposit(alice, 100_00)
        val clearingBefore = balance(SystemAccounts.EXTERNAL_CLEARING.value)

        val transferId =
            send(alice, iban("DE", "370400440000000000"), 4000)
        awaitStatus(transferId, "SENT_TO_EXTERNAL_BANK")
        confirm(transferId, TxSts.RJCT, ReasonCodes.CLOSED_ACCOUNT).andExpect { status { isAccepted() } }

        val result = awaitResult(transferId)
        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("Account closed (AC04)", result.reason)
        assertEquals(100_00, balance(alice))
        assertEquals(clearingBefore, balance(SystemAccounts.EXTERNAL_CLEARING.value))
        assertEquals(
            listOf("debit", "credit", "refund", "refund"),
            queries.postingsOfTransfer(com.example.bank.domain.TransferId(transferId)).map {
                it.leg
            },
        )
    }

    @Test
    fun `a payment the clearing house refuses is refunded at once`() {
        clearingHouse.onPacs008 = { 422 } // e.g. it arrived after the deadline
        val alice = openAccount()
        deposit(alice, 100_00)

        val transferId = send(alice, "DE89370400440532013000", 4000)

        val result = awaitResult(transferId)
        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("Refused by the clearing house (HTTP 422)", result.reason)
        assertEquals(100_00, balance(alice))
    }

    @Test
    fun `a confirmation for a payment the bank doesn't know is refused`() {
        confirm(UUID.randomUUID(), TxSts.ACSC).andExpect { status { isNotFound() } }
    }

    @Test
    fun `a transfer above the limit awaits approval and is booked once approved`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 20_000_00)

        val transferId = send(alice, ibanOf(bob), 1500000)
        awaitStatus(transferId, "AWAITING_APPROVAL")
        assertEquals(20_000_00, balance(alice), "nothing is booked before approval")

        mvc.post("/api/backoffice/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":true}"""
        }.andExpect { status { isOk() } }

        assertEquals(TransferStatus.BOOKED, awaitResult(transferId).status)
        assertEquals(5_000_00, balance(alice))
        assertEquals(15_000_00, balance(bob))

        mvc.post("/api/backoffice/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":true}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("Transfer is not awaiting approval (status BOOKED)") }
        }
    }

    @Test
    fun `the ledger gauges show a balanced ledger and the transfer waiting for approval`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 20_000_00)
        val waitingBefore = inFlight("AWAITING_APPROVAL")

        val transferId = send(alice, ibanOf(bob), 1500000)
        awaitStatus(transferId, "AWAITING_APPROVAL")

        assertEquals(waitingBefore + 1, inFlight("AWAITING_APPROVAL"))
        assertTrue(registry.get("bank.transfers.in.flight.age.max").tag("status", "AWAITING_APPROVAL").gauge().value() >= 0.0)
        assertEquals(0.0, registry.get("bank.ledger.sum").gauge().value())

        mvc.post("/api/backoffice/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":false}"""
        }.andExpect { status { isOk() } }
        awaitResult(transferId)
        assertEquals(waitingBefore, inFlight("AWAITING_APPROVAL"))
    }

    @Test
    fun `a denied approval books nothing`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 20_000_00)
        val transferId = send(alice, ibanOf(bob), 1500000)
        awaitStatus(transferId, "AWAITING_APPROVAL")

        mvc.post("/api/backoffice/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":false,"comment":"phishing"}"""
        }.andExpect { status { isOk() } }

        val result = awaitResult(transferId)
        assertEquals(TransferStatus.REJECTED, result.status)
        assertEquals("Approval denied: phishing", result.reason)
        assertEquals(20_000_00, balance(alice))
        assertEquals(0, balance(bob))
    }

    @Test
    fun `invalid requests are rejected with 400`() {
        val alice = openAccount()
        val unknownHere = iban("DE", "${identity.bankCode}9999999999") // this bank's code, no such account
        listOf(
            """{"creditorIban":"${ibanOf(alice)}","amountCents":0}""",
            """{"amountCents":100}""",
            """{"creditorIban":"DE00 1234","amountCents":100}""",
            """{"creditorIban":"DE88370400440532013000","amountCents":100}""",
            """{"creditorIban":"$unknownHere","amountCents":100}""",
        ).forEach { body ->
            mvc.post("/api/customer/accounts/$alice/transfers") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
                .andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `a customer sees only their own account and transfers`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 100_00)
        val transferId = send(alice, ibanOf(bob), 10_00)
        awaitResult(transferId)
        val carol = openAccount()

        mvc.get("/api/customer/accounts/$bob/transfers/$transferId").andExpect { status { isOk() } } // bob received it
        mvc.get("/api/customer/accounts/$carol/transfers/$transferId").andExpect { status { isNotFound() } }
        mvc.get("/api/customer/accounts/$carol/transfers/$transferId/history").andExpect { status { isNotFound() } }
        mvc.get("/api/customer/accounts/$carol/transfers").andExpect { jsonPath("$.length()") { value(0) } }
        mvc.get("/api/customer/accounts/${SystemAccounts.CASH.value}").andExpect { status { isNotFound() } }
        assertEquals(
            listOf("RECEIVED", "BOOKED"),
            read<List<String>>("/api/customer/accounts/$alice/transfers/$transferId/history", "$[*].status"),
        )
        assertEquals(listOf(10_00), read<List<Int>>("/api/customer/accounts/$bob/postings", "$[*].amountCents"))
    }

    @Test
    fun `the back office finds the transfers waiting for a decision`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 20_000_00)
        val transferId = send(alice, ibanOf(bob), 1500000)
        awaitStatus(transferId, "AWAITING_APPROVAL")

        assertTrue(transferId.toString() in read<List<String>>("/api/backoffice/transfers?status=AWAITING_APPROVAL", "$[*].transferId"))
        assertTrue(transferId.toString() !in read<List<String>>("/api/backoffice/transfers?status=UNDER_INVESTIGATION", "$[*].transferId"))

        mvc.post("/api/backoffice/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":false}"""
        }.andExpect { status { isOk() } }
        awaitResult(transferId)
    }

    @Test
    fun `only an account without money can be closed, and a closed one takes no transfers`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 10_00)

        mvc.post("/api/backoffice/accounts/$alice/close").andExpect { status { isConflict() } }
        mvc.post("/api/backoffice/accounts/$bob/close").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CLOSED") }
        }

        val transferId = send(alice, ibanOf(bob), 5_00)
        assertEquals(TransferStatus.REJECTED, awaitResult(transferId).status)
    }

    @Test
    fun `transfers are listed with their details, newest first`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 100_00)
        val first = send(alice, ibanOf(bob), 1000)
        awaitResult(first)
        val second = send(alice, ibanOf(bob), 999999)
        awaitResult(second)

        mvc.get("/api/backoffice/transfers?accountId=$bob").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].transferId") { value(second.toString()) }
            jsonPath("$[0].status") { value("REJECTED") }
            jsonPath("$[0].reason") { value("Insufficient funds") }
            jsonPath("$[1].transferId") { value(first.toString()) }
            jsonPath("$[1].kind") { value("INTERNAL") }
            jsonPath("$[1].debtorAccountId") { value(alice.toString()) }
            jsonPath("$[1].amountCents") { value(1000) }
            jsonPath("$[1].status") { value("BOOKED") }
        }
        assertEquals(listOf(-1000, 1000), read<List<Int>>("/api/backoffice/transfers/$first/postings", "$[*].amountCents"))
        mvc.get("/api/backoffice/transfers?accountId=$alice").andExpect {
            jsonPath("$[2].kind") { value("DEPOSIT") }
        }
    }

    @Test
    fun `a transfer awaiting approval shows its status from the ledger, and its history`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 20_000_00)
        val transferId = send(alice, ibanOf(bob), 1500000)
        awaitStatus(transferId, "AWAITING_APPROVAL")

        mvc.get("/api/backoffice/transfers?accountId=$bob").andExpect {
            jsonPath("$[0].status") { value("AWAITING_APPROVAL") }
            jsonPath("$[0].reason") { value("Amount exceeds approval limit") }
        }

        mvc.post("/api/backoffice/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":false}"""
        }
        awaitResult(transferId)
        assertEquals(
            listOf("RECEIVED", "AWAITING_APPROVAL", "REJECTED"),
            read<List<String>>("/api/backoffice/transfers/$transferId/history", "$[*].status"),
        )
    }

    @Test
    fun `accounts and the ledger summary are listed`() {
        val alice = openAccount()
        deposit(alice, 5_00)

        assertEquals(listOf(500), read<List<Int>>("/api/backoffice/accounts", "$[?(@.id == '$alice')].balanceCents"))
        assertEquals(emptyList(), read<List<String>>("/api/backoffice/accounts", "$[?(@.kind == 'SYSTEM')].id"))
        assertEquals(
            listOf("SYSTEM"),
            read<List<String>>("/api/operations/system-accounts", "$[?(@.id == '${SystemAccounts.CASH.value}')].kind"),
        )
        mvc.get("/api/operations/ledger/summary").andExpect {
            status { isOk() }
            jsonPath("$.sumOfAllPostingsCents") { value(0) }
        }
    }

    @Test
    fun `operations reach the clearing house's chaos settings through the bank`() {
        mvc.put("/api/operations/clearing-house/chaos") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"forwardDelayMs":2000,"dropForwardRate":0.5}"""
        }.andExpect { status { isOk() } }
        assertEquals(2000, read<Int>("/api/operations/clearing-house/chaos", "$.forwardDelayMs"))

        mvc.put("/api/operations/clearing-house/chaos") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"forwardDelayMs":0,"dropForwardRate":7}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("dropForwardRate must be between 0 and 1") }
        }
    }

    @Test
    fun `each area has its page`() {
        listOf("/", "/customer.html", "/backoffice.html", "/operations.html", "/common.js").forEach { page ->
            mvc.get(page).andExpect { status { isOk() } }
        }
    }

    // --- helpers ---

    private val ibans = mutableMapOf<UUID, String>()

    private fun ibanOf(account: UUID) = ibans.getValue(account)

    private fun openAccount(): UUID {
        val json = mvc.post("/api/backoffice/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        val id = UUID.fromString(JsonPath.read(json, "$.id"))
        ibans[id] = JsonPath.read(json, "$.iban")
        return id
    }

    private fun deposit(account: UUID, cents: Long) {
        val json = mvc.post("/api/backoffice/accounts/$account/deposits") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amountCents":$cents}"""
        }.andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        assertEquals(TransferStatus.BOOKED, awaitResult(UUID.fromString(JsonPath.read(json, "$.transferId"))).status)
    }

    /** What a customer does in online banking: money to an IBAN, of this bank or another one. */
    private fun send(from: UUID, toIban: String, amountCents: Long, transferId: UUID? = null): UUID {
        val json = sendRequest(from, toIban, amountCents, transferId)
            .andExpect { status { isAccepted() } }
            .andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(json, "$.transferId"))
    }

    private fun sendRequest(from: UUID, toIban: String, amountCents: Long, transferId: UUID?) =
        mvc.post("/api/customer/accounts/$from/transfers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"creditorIban":"$toIban","amountCents":$amountCents${transferId?.let { ""","transferId":"$it"""" } ?: ""}}"""
        }

    /** What the clearing house does once the payment has its final status: a pacs.002 to the bank's inbound endpoint. */
    private fun confirm(transferId: UUID, txSts: TxSts, reasonCode: String? = null) = mvc.post("/api/clearing/inbound/pacs002") {
        contentType = MediaType.APPLICATION_JSON
        content =
            """{"msgId":"${UUID.randomUUID()}","uetr":"$transferId","txSts":"$txSts"${reasonCode?.let {
                ""","reasonCode":"$it""""
            } ?: ""}}"""
    }

    private fun awaitResult(transferId: UUID): TransferState =
        client.newUntypedWorkflowStub(transferWorkflowId(transferId)).getResult(TransferState::class.java)

    private fun awaitStatus(transferId: UUID, expected: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            val json = mvc.get("/api/backoffice/transfers/$transferId").andReturn().response.contentAsString
            if (json.isNotEmpty() && JsonPath.read<String>(json, "$.status") == expected) return
            Thread.sleep(50)
        }
        error("Status $expected not reached")
    }

    private fun <T> read(url: String, path: String): T =
        JsonPath.read(mvc.get(url).andExpect { status { isOk() } }.andReturn().response.contentAsString, path)

    private fun balance(account: UUID) = queries.balanceOf(AccountId(account))

    private fun inFlight(status: String) = registry.get("bank.transfers.in.flight").tag("status", status).gauge().value()

    /** Builds an IBAN with valid check digits. */
    private fun iban(country: String, bban: String): String {
        val numeric = (bban + country + "00").map { if (it.isLetter()) (it - 'A' + 10).toString() else it.toString() }.joinToString("")
        val check = 98 - BigInteger(numeric).mod(BigInteger.valueOf(97)).toInt()
        return country + check.toString().padStart(2, '0') + bban
    }
}
