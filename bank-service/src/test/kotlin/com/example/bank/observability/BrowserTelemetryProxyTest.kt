package com.example.bank.observability

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The OTLP proxy for the browser: forwards each signal unchanged to its collector endpoint. */
@WebMvcTest(controllers = [BrowserTelemetryController::class, UiConfigController::class])
@Import(BrowserTelemetryProxyTest.Properties::class)
class BrowserTelemetryProxyTest @Autowired constructor(private val mvc: MockMvc) {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UiProperties::class)
    class Properties

    @BeforeEach
    fun reset() {
        collector.received.clear()
        collector.status = 200
    }

    @Test
    fun `each signal is forwarded unchanged to its endpoint`() {
        listOf("traces", "logs", "metrics").forEach { signal ->
            val body = """{"resource$signal":[]}""".toByteArray()

            mvc.post("/api/otel/v1/$signal") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isAccepted() } }

            val received = collector.received.last()
            assertEquals("/v1/$signal", received.path)
            assertEquals("application/json", received.mediaType)
            assertContentEquals(body, received.body)
        }
    }

    @Test
    fun `protobuf payloads keep their content type`() {
        val body = byteArrayOf(0x0a, 0x00, 0x7f, -1)

        mvc.post("/api/otel/v1/traces") {
            contentType = MediaType.parseMediaType("application/x-protobuf")
            content = body
        }.andExpect { status { isAccepted() } }

        assertEquals("application/x-protobuf", collector.received.single().mediaType)
        assertContentEquals(body, collector.received.single().body)
    }

    @Test
    fun `unknown signals are rejected and not forwarded`() {
        mvc.post("/api/otel/v1/profiles") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isNotFound() } }

        assertTrue(collector.received.isEmpty())
    }

    @Test
    fun `payloads over 2 MB are rejected`() {
        mvc.post("/api/otel/v1/logs") {
            contentType = MediaType.APPLICATION_JSON
            content = ByteArray(2 * 1024 * 1024 + 1)
        }.andExpect { status { isContentTooLarge() } }

        assertTrue(collector.received.isEmpty())
    }

    @Test
    fun `a failing collector is reported as bad gateway`() {
        collector.status = 503

        mvc.post("/api/otel/v1/metrics") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isBadGateway() } }
    }

    @Test
    fun `the UI gets the tool URLs and the telemetry switch`() {
        mvc.get("/api/ui-config").andExpect {
            status { isOk() }
            jsonPath("$.grafanaUrl") { value("http://localhost:3000") }
            jsonPath("$.temporalUiUrl") { value("http://localhost:8233") }
            jsonPath("$.browserTelemetry") { value(true) }
        }
    }

    /** A stand-in for the collector's OTLP/HTTP receiver. */
    class FakeCollector {
        data class Received(val path: String, val contentType: String?, val body: ByteArray) {
            /** Without parameters: MockMvc adds `;charset=UTF-8` (Boot's encoding filter), a real Tomcat doesn't. */
            val mediaType get() = contentType?.substringBefore(';')
        }

        val received = CopyOnWriteArrayList<Received>()

        @Volatile var status = 200

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    received += Received(it.requestURI.path, it.requestHeaders.getFirst("Content-Type"), it.requestBody.readAllBytes())
                    it.sendResponseHeaders(status, -1)
                }
            }
            start()
        }

        fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

        fun stop() = server.stop(0)
    }

    companion object {
        private val collector = FakeCollector()

        @JvmStatic
        @DynamicPropertySource
        fun collectorEndpoints(registry: DynamicPropertyRegistry) {
            registry.add("bank.ui.otlp.traces-url") { collector.url("/v1/traces") }
            registry.add("bank.ui.otlp.logs-url") { collector.url("/v1/logs") }
            registry.add("bank.ui.otlp.metrics-url") { collector.url("/v1/metrics") }
        }

        @JvmStatic
        @AfterAll
        fun stopCollector() = collector.stop()
    }
}
