package com.example.bank.api

import com.example.bank.IntegrationTest
import com.example.bank.domain.AccountId
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.SystemAccounts
import com.example.bank.workflow.TransferState
import com.example.bank.workflow.TransferStatus
import com.example.bank.workflow.transferWorkflowId
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

/** End to end: REST → TransferWorkflow (Temporal test server) → activities → ledger (Postgres). */
@IntegrationTest
class TransferApiTest @Autowired constructor(
    private val mvc: MockMvc,
    private val client: WorkflowClient,
    private val queries: LedgerQueries,
    private val registry: MeterRegistry,
) {

    @AfterEach
    fun `sum of all postings stays zero`() = assertEquals(0, queries.sumOfAllPostings())

    @Test
    fun `open accounts, deposit and transfer internally`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 100_00)

        val transferId = postTransfer("""{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":2500}""")

        assertEquals(TransferStatus.BOOKED, awaitResult(transferId).status)
        assertEquals(75_00, balance(alice))
        assertEquals(25_00, balance(bob))
        mvc.get("/api/transfers/$transferId").andExpect {
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
        val body = """{"transferId":"$transferId","debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":1000}"""

        postTransfer(body)
        awaitResult(transferId)
        mvc.post("/api/transfers") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
            .andExpect { status { isConflict() } }

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
                Callable { postTransfer("""{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":3000}""") }
            },
        ).map { it.get() }
        pool.shutdown()
        val results = ids.map { awaitResult(it) }

        assertEquals(3, results.count { it.status == TransferStatus.BOOKED })
        assertTrue(results.filter { it.status != TransferStatus.BOOKED }.all { it.reason == "Insufficient funds" })
        assertEquals(10_00, balance(alice))
    }

    @Test
    fun `external bank accepts and the money sits on the clearing account`() {
        val alice = openAccount()
        deposit(alice, 100_00)
        val clearingBefore = balance(SystemAccounts.EXTERNAL_CLEARING.value)

        val transferId = postTransfer("""{"debtorAccountId":"$alice","creditorIban":"DE89 3704 0044 0532 0130 00","amountCents":4000}""")

        assertEquals(TransferStatus.COMPLETED, awaitResult(transferId).status)
        assertEquals(60_00, balance(alice))
        assertEquals(clearingBefore + 40_00, balance(SystemAccounts.EXTERNAL_CLEARING.value))
    }

    @Test
    fun `external bank rejects and the debit is refunded`() {
        val alice = openAccount()
        deposit(alice, 100_00)
        val clearingBefore = balance(SystemAccounts.EXTERNAL_CLEARING.value)

        val transferId =
            postTransfer("""{"debtorAccountId":"$alice","creditorIban":"${iban("DE", "370400440000000000")}","amountCents":4000}""")

        val result = awaitResult(transferId)
        assertEquals(TransferStatus.REFUNDED, result.status)
        assertEquals("Creditor account closed", result.reason)
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
    fun `a transfer above the limit awaits approval and is booked once approved`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 20_000_00)

        val transferId = postTransfer("""{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":1500000}""")
        awaitStatus(transferId, "AWAITING_APPROVAL")
        assertEquals(20_000_00, balance(alice), "nothing is booked before approval")

        mvc.post("/api/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":true}"""
        }.andExpect { status { isOk() } }

        assertEquals(TransferStatus.BOOKED, awaitResult(transferId).status)
        assertEquals(5_000_00, balance(alice))
        assertEquals(15_000_00, balance(bob))

        mvc.post("/api/transfers/$transferId/approval") {
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
        val waitingBefore = inFlight("PENDING")

        val transferId = postTransfer("""{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":1500000}""")
        awaitStatus(transferId, "AWAITING_APPROVAL")

        assertEquals(waitingBefore + 1, inFlight("PENDING"))
        assertTrue(registry.get("bank.transfers.in.flight.age.max").tag("status", "PENDING").gauge().value() >= 0.0)
        assertEquals(0.0, registry.get("bank.ledger.sum").gauge().value())

        mvc.post("/api/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":false}"""
        }.andExpect { status { isOk() } }
        awaitResult(transferId)
        assertEquals(waitingBefore, inFlight("PENDING"))
    }

    @Test
    fun `a denied approval books nothing`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 20_000_00)
        val transferId = postTransfer("""{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":1500000}""")
        awaitStatus(transferId, "AWAITING_APPROVAL")

        mvc.post("/api/transfers/$transferId/approval") {
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
        listOf(
            """{"debtorAccountId":"$alice","creditorAccountId":"${UUID.randomUUID()}","amountCents":0}""",
            """{"debtorAccountId":"$alice","amountCents":100}""",
            """{"debtorAccountId":"$alice","creditorIban":"DE00 1234","amountCents":100}""",
            """{"debtorAccountId":"$alice","creditorIban":"DE88370400440532013000","amountCents":100}""",
        ).forEach { body ->
            mvc.post("/api/transfers") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
                .andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `transfers are listed with their details, newest first`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 100_00)
        val first = postTransfer("""{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":1000}""")
        awaitResult(first)
        val second = postTransfer("""{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":999999}""")
        awaitResult(second)

        mvc.get("/api/transfers?accountId=$bob").andExpect {
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
        assertEquals(listOf(-1000, 1000), read<List<Int>>("/api/transfers/$first/postings", "$[*].amountCents"))
        mvc.get("/api/transfers?accountId=$alice").andExpect {
            jsonPath("$[2].kind") { value("DEPOSIT") }
        }
    }

    @Test
    fun `a transfer awaiting approval shows its live status in the list`() {
        val alice = openAccount()
        val bob = openAccount()
        deposit(alice, 20_000_00)
        val transferId = postTransfer("""{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":1500000}""")
        awaitStatus(transferId, "AWAITING_APPROVAL")

        mvc.get("/api/transfers?accountId=$bob").andExpect {
            jsonPath("$[0].status") { value("AWAITING_APPROVAL") }
            jsonPath("$[0].reason") { value("Amount exceeds approval limit") }
        }

        mvc.post("/api/transfers/$transferId/approval") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"approved":false}"""
        }
        awaitResult(transferId)
    }

    @Test
    fun `accounts and the ledger summary are listed`() {
        val alice = openAccount()
        deposit(alice, 5_00)

        assertEquals(listOf(500), read<List<Int>>("/api/accounts", "$[?(@.id == '$alice')].balanceCents"))
        assertEquals(listOf("SYSTEM"), read<List<String>>("/api/accounts", "$[?(@.id == '${SystemAccounts.CASH.value}')].kind"))
        mvc.get("/api/ledger/summary").andExpect {
            status { isOk() }
            jsonPath("$.sumOfAllPostingsCents") { value(0) }
        }
    }

    @Test
    fun `simulator settings can be changed at runtime and are validated`() {
        val original = mvc.get("/api/simulator/external-bank").andReturn().response.contentAsString
        try {
            mvc.put("/api/simulator/external-bank") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"minDelayMs":0,"maxDelayMs":0,"rejectionRate":1.0,"failureRate":0.0}"""
            }.andExpect { status { isOk() } }

            val alice = openAccount()
            deposit(alice, 100_00)
            val transferId = postTransfer("""{"debtorAccountId":"$alice","creditorIban":"DE89370400440532013000","amountCents":1000}""")
            val result = awaitResult(transferId)
            assertEquals(TransferStatus.REFUNDED, result.status)
            assertEquals("Rejected by the creditor bank", result.reason)

            mvc.put("/api/simulator/external-bank") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"minDelayMs":0,"maxDelayMs":0,"rejectionRate":1.5,"failureRate":0.0}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Rates must be between 0 and 1") }
            }
        } finally {
            mvc.put("/api/simulator/external-bank") {
                contentType = MediaType.APPLICATION_JSON
                content = original
            }
        }
    }

    @Test
    fun `the UI is served at the root`() {
        mvc.get("/").andExpect { status { isOk() } }
        mvc.get("/app.js").andExpect { status { isOk() } }
    }

    // --- helpers ---

    private fun openAccount(): UUID {
        val json = mvc.post("/api/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(json, "$.id"))
    }

    private fun deposit(account: UUID, cents: Long) {
        val json = mvc.post("/api/accounts/$account/deposits") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amountCents":$cents}"""
        }.andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        assertEquals(TransferStatus.BOOKED, awaitResult(UUID.fromString(JsonPath.read(json, "$.transferId"))).status)
    }

    private fun postTransfer(body: String): UUID {
        val json = mvc.post("/api/transfers") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
            .andExpect { status { isAccepted() } }
            .andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(json, "$.transferId"))
    }

    private fun awaitResult(transferId: UUID): TransferState =
        client.newUntypedWorkflowStub(transferWorkflowId(transferId)).getResult(TransferState::class.java)

    private fun awaitStatus(transferId: UUID, expected: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            val json = mvc.get("/api/transfers/$transferId").andReturn().response.contentAsString
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
