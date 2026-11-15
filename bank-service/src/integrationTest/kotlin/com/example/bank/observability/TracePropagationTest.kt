package com.example.bank.observability

import com.example.bank.IntegrationTest
import com.example.bank.workflow.TransferState
import com.example.bank.workflow.TransferStatus
import com.example.bank.workflow.transferWorkflowId
import com.jayway.jsonpath.JsonPath
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.temporal.client.WorkflowClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W3C trace context end to end: an incoming `traceparent` is continued through REST, Temporal (workflow header),
 * the activities and the SQL, and the trace ID is stored with the transfer.
 */
@IntegrationTest
class TracePropagationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val client: WorkflowClient,
    private val exporter: InMemorySpanExporter,
    private val tracerProvider: SdkTracerProvider,
) {

    @Test
    fun `an incoming traceparent is continued through REST, Temporal, activities and SQL`() {
        val (alice, bob) = fundedAccounts()
        val traceId = newTraceId()

        val transferId = postTransfer(
            """{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":1000}""",
            traceparent = "00-$traceId-00f067aa0ba902b7-01",
            expectedTraceId = traceId,
        )
        assertEquals(TransferStatus.BOOKED, awaitResult(transferId).status)

        val names = spanNames(traceId)
        assertTrue(names.any { it.startsWith("http post") }, "HTTP server span missing: $names")
        listOf(
            "StartWorkflow:TransferWorkflow",
            "RunWorkflow:TransferWorkflow",
            "RunActivity:RiskAssess",
            "RunActivity:LedgerBookInternalTransfer",
            "bank.ledger.lock",
            "bank.account.command",
        ).forEach { assertTrue(it in names, "span $it missing in trace $traceId: $names") }
        assertTrue("query" in names, "JDBC spans missing: $names")

        // The trace ID is stored with the transfer, so the UI can link to it.
        val listed = mvc.get("/api/transfers?accountId=$bob").andReturn().response.contentAsString
        assertEquals(traceId, JsonPath.read<String>(listed, "$[0].traceId"))
    }

    @Test
    fun `Temporal carries the trace context as W3C traceparent in the workflow header`() {
        val (alice, bob) = fundedAccounts()
        val traceId = newTraceId()
        val transferId = postTransfer(
            """{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":500}""",
            traceparent = "00-$traceId-00f067aa0ba902b7-01",
            expectedTraceId = traceId,
        )
        awaitResult(transferId)

        val started = client.fetchHistory(transferWorkflowId(transferId)).events.first().workflowExecutionStartedEventAttributes
        val tracerData = started.header.fieldsMap.getValue("_tracer-data").data.toStringUtf8()

        assertTrue(
            Regex(""""traceparent"\s*:\s*"00-$traceId-[0-9a-f]{16}-01"""").containsMatchIn(tracerData),
            "expected a W3C traceparent in the workflow header, got: $tracerData",
        )
    }

    @Test
    fun `without traceparent a new trace is started and returned`() {
        val (alice, bob) = fundedAccounts()

        val json = mvc.post("/api/transfers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":100}"""
        }.andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        val traceId = JsonPath.read<String>(json, "$.traceId")
        awaitResult(UUID.fromString(JsonPath.read(json, "$.transferId")))

        assertTrue(Regex("[0-9a-f]{32}").matches(traceId), traceId)
        assertTrue("RunWorkflow:TransferWorkflow" in spanNames(traceId))
    }

    @Test
    fun `an unsampled traceparent (the UI's polling) records nothing`() {
        val traceId = newTraceId()

        mvc.get("/api/accounts") { header("traceparent", "00-$traceId-00f067aa0ba902b7-00") }
            .andExpect { status { isOk() } }

        assertEquals(emptyList(), spanNames(traceId))
    }

    @Test
    fun `traces carry business IDs and can be searched by transfer and account`() {
        val (alice, bob) = fundedAccounts()
        val traceId = newTraceId()
        val transferId = postTransfer(
            """{"debtorAccountId":"$alice","creditorAccountId":"$bob","amountCents":700}""",
            traceparent = "00-$traceId-00f067aa0ba902b7-01",
            expectedTraceId = traceId,
        )
        awaitResult(transferId)
        val spans = spans(traceId)

        val http = spans.single { it.name.startsWith("http post") }
        assertEquals(transferId.toString(), http.attr("bank.transfer.id"))
        assertEquals("INTERNAL", http.attr("bank.transfer.kind"))
        assertEquals(alice.toString(), http.attr("bank.debtor.account.id"))
        assertEquals(bob.toString(), http.attr("bank.creditor.account.id"))

        assertEquals(transferId.toString(), spans.single { it.name == "RunActivity:LedgerBookInternalTransfer" }.attr("bank.transfer.id"))

        // Every booking touching an account has a command span with that account: { span.bank.account.id = "<id>" }
        val commands = spans.filter { it.name == "bank.account.command" }
        assertEquals(setOf(alice.toString(), bob.toString()), commands.map { it.attr("bank.account.id") }.toSet())
        assertTrue(commands.all { it.attr("bank.transfer.id") == transferId.toString() })
        assertEquals(listOf(alice, bob).sorted().joinToString(","), spans.single { it.name == "bank.ledger.lock" }.attr("bank.account.ids"))
    }

    @Test
    fun `the call to the clearing house is its own span with message, outcome and attempt`() {
        val (alice, _) = fundedAccounts()
        val traceId = newTraceId()
        val transferId = postTransfer(
            """{"debtorAccountId":"$alice","creditorIban":"DE89370400440532013000","amountCents":2500}""",
            traceparent = "00-$traceId-00f067aa0ba902b7-01",
            expectedTraceId = traceId,
        )
        awaitSent(transferId)
        mvc.post("/api/clearing/inbound/pacs002") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"msgId":"${UUID.randomUUID()}","uetr":"$transferId","txSts":"ACSC"}"""
        }.andExpect { status { isAccepted() } }
        assertEquals(TransferStatus.COMPLETED, awaitResult(transferId).status)

        val call = spans(traceId).single { it.name == "bank.clearing.call" }
        assertEquals("pacs.008", call.attr("message"))
        assertEquals("received", call.attr("outcome"))
        assertEquals(transferId.toString(), call.attr("bank.transfer.id"))
        assertEquals("1", call.attr("temporal.activity.attempt"))
        assertTrue(
            spans(traceId).any {
                it.name.startsWith("http post") && it.parentSpanId == call.spanId
            },
            "the HTTP call to the clearing house",
        )
    }

    @Test
    fun `static UI files are not traced`() {
        val traceId = newTraceId()

        mvc.get("/app.js") { header("traceparent", "00-$traceId-00f067aa0ba902b7-01") }
            .andExpect { status { isOk() } }

        assertEquals(emptyList(), spanNames(traceId))
    }

    @Test
    fun `the browser's own telemetry passing through the proxy is not traced again`() {
        val traceId = newTraceId()

        mvc.post("/api/otel/v1/traces") {
            contentType = MediaType.APPLICATION_JSON
            header("traceparent", "00-$traceId-00f067aa0ba902b7-01")
            content = """{"resourceSpans":[]}"""
        }

        assertEquals(emptyList(), spanNames(traceId))
    }

    // --- helpers ---

    private fun awaitSent(transferId: UUID) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val json = mvc.get("/api/transfers/$transferId").andReturn().response.contentAsString
            if (json.isNotEmpty() && JsonPath.read<String>(json, "$.status") == "SENT_TO_EXTERNAL_BANK") return
            Thread.sleep(50)
        }
        error("Transfer $transferId not sent")
    }

    private fun newTraceId() = Random.nextBytes(16).joinToString("") { "%02x".format(it) }

    private fun spans(traceId: String): List<SpanData> {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS)
        return exporter.finishedSpanItems.filter { it.traceId == traceId }
    }

    private fun spanNames(traceId: String): List<String> = spans(traceId).map { it.name }

    private fun SpanData.attr(key: String): String? = attributes.get(AttributeKey.stringKey(key))

    private fun fundedAccounts(): Pair<UUID, UUID> {
        val alice = openAccount()
        val bob = openAccount()
        val json = mvc.post("/api/accounts/$alice/deposits") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amountCents":100000}"""
        }.andReturn().response.contentAsString
        awaitResult(UUID.fromString(JsonPath.read(json, "$.transferId")))
        return alice to bob
    }

    private fun openAccount(): UUID {
        val json = mvc.post("/api/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }
            .andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(json, "$.id"))
    }

    private fun postTransfer(body: String, traceparent: String, expectedTraceId: String): UUID {
        val json = mvc.post("/api/transfers") {
            contentType = MediaType.APPLICATION_JSON
            header("traceparent", traceparent)
            content = body
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.traceId") { value(expectedTraceId) }
        }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(json, "$.transferId"))
    }

    private fun awaitResult(transferId: UUID): TransferState =
        client.newUntypedWorkflowStub(transferWorkflowId(transferId)).getResult(TransferState::class.java)
}
