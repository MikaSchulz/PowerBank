package com.example.bank.observability

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class UiConfig(val grafanaUrl: String, val temporalUiUrl: String, val browserTelemetry: Boolean)

/** Lets the UI find Grafana and the Temporal UI without hard-coded URLs (local, staging, cloud). */
@RestController
class UiConfigController(private val properties: UiProperties) {

    @GetMapping("/api/ui-config")
    fun config() = UiConfig(properties.grafanaUrl, properties.temporalUiUrl, properties.browserTelemetry)
}
