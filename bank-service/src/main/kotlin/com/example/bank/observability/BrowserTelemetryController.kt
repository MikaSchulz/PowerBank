package com.example.bank.observability

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsConnectionDetails
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingConnectionDetails
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.Transport as TracingTransport
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.Transport as LoggingTransport

/**
 * Receives the browser's OpenTelemetry data (OTLP/HTTP, JSON or protobuf) and forwards it unchanged to the
 * collector. The browser can't reach the collector itself (other host and port, CORS), and in the cloud this is the
 * place to require a login (#36). The forwarding uses a plain HTTP client, so it creates no telemetry of its own.
 */
@RestController
@RequestMapping("/api/otel/v1")
class BrowserTelemetryController(
    private val properties: UiProperties,
    private val tracing: ObjectProvider<OtlpTracingConnectionDetails>,
    private val logging: ObjectProvider<OtlpLoggingConnectionDetails>,
    private val metrics: ObjectProvider<OtlpMetricsConnectionDetails>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @PostMapping("/{signal}")
    fun forward(
        @PathVariable signal: String,
        @RequestHeader(HttpHeaders.CONTENT_TYPE) contentType: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (signal !in SIGNALS) return ResponseEntity.notFound().build()
        if (request.contentLengthLong > MAX_BYTES) return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build()
        // Read at most one byte more than allowed, so a body without Content-Length can't fill the memory either.
        val body = request.inputStream.readNBytes(MAX_BYTES + 1)
        if (body.size > MAX_BYTES) return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build()
        if (!properties.browserTelemetry) return ResponseEntity.noContent().build()
        val target = targetFor(signal) ?: return ResponseEntity.noContent().build() // no collector configured: drop

        val upstream = HttpRequest.newBuilder(URI.create(target))
            .timeout(FORWARD_TIMEOUT)
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return try {
            val status = HttpStatusCode.valueOf(http.send(upstream, HttpResponse.BodyHandlers.discarding()).statusCode())
            if (status.is2xxSuccessful) ResponseEntity.accepted().build() else ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        } catch (e: IOException) {
            log.debug("Forwarding browser {} to {} failed: {}", signal, target, e.message)
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }
    }

    private fun targetFor(signal: String): String? = when (signal) {
        "traces" -> properties.otlp.tracesUrl ?: tracing.ifAvailable?.getUrl(TracingTransport.HTTP)
        "logs" -> properties.otlp.logsUrl ?: logging.ifAvailable?.getUrl(LoggingTransport.HTTP)
        "metrics" -> properties.otlp.metricsUrl ?: metrics.ifAvailable?.url
        else -> null
    }

    companion object {
        private val SIGNALS = setOf("traces", "logs", "metrics")
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val FORWARD_TIMEOUT = Duration.ofSeconds(5)
    }
}
