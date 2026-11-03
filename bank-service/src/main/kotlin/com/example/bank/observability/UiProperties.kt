package com.example.bank.observability

import org.springframework.boot.context.properties.ConfigurationProperties

/** Settings for the browser UI. Defaults fit the local Docker Compose setup; override them per environment. */
@ConfigurationProperties("bank.ui")
data class UiProperties(
    val grafanaUrl: String = "http://localhost:3000",
    val temporalUiUrl: String = "http://localhost:8233",
    /** Whether the UI records its own traces, logs and metrics (OpenTelemetry in the browser). */
    val browserTelemetry: Boolean = true,
    /** OTLP/HTTP endpoints for the browser's telemetry. Default: the endpoints Spring Boot uses for the backend. */
    val otlp: Otlp = Otlp(),
) {
    data class Otlp(val tracesUrl: String? = null, val logsUrl: String? = null, val metricsUrl: String? = null)
}
