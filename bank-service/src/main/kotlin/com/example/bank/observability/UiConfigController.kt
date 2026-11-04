package com.example.bank.observability

import com.example.bank.config.BankIdentity
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * [bankName] and [bic] tell which bank this UI belongs to: bank A and bank B run side by side (#55), each with its
 * own Temporal namespace for the links into the Temporal UI.
 */
data class UiConfig(
    val grafanaUrl: String,
    val temporalUiUrl: String,
    val temporalNamespace: String,
    val browserTelemetry: Boolean,
    val bankName: String,
    val bic: String,
)

/** Lets the UI find Grafana and the Temporal UI without hard-coded URLs (local, staging, cloud). */
@RestController
class UiConfigController(
    private val properties: UiProperties,
    private val identity: BankIdentity,
    @param:Value("\${spring.temporal.namespace:default}") private val temporalNamespace: String,
) {

    @GetMapping("/api/ui-config")
    fun config() = UiConfig(
        grafanaUrl = properties.grafanaUrl,
        temporalUiUrl = properties.temporalUiUrl,
        temporalNamespace = temporalNamespace,
        browserTelemetry = properties.browserTelemetry,
        bankName = identity.name,
        bic = identity.bic,
    )
}
