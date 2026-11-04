package com.example.bank.config

import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Bank A (application.yml) and bank B (application-bank-b.yml) share the containers but nothing else: each has its
 * own identity, Postgres schema, Temporal namespace, port and service name in the telemetry (#57).
 */
class BankInstancesTest {

    private val bankA = load("application.yml")
    private val bankB = bankA + load("application-bank-b.yml") // a profile overrides the defaults

    @Test
    fun `the two banks share nothing but the containers`() {
        listOf(
            "bank.identity.name",
            "bank.identity.bic",
            "bank.identity.bank-code",
            "spring.temporal.namespace",
            "spring.application.name",
        ).forEach { key -> assertNotEquals(bankA[key], bankB[key], key) }

        assertEquals("public", schemaOf(bankA), "bank A uses the default schema")
        assertEquals("bank_b", schemaOf(bankB))
        assertEquals("bank_b", bankB["spring.flyway.schemas"])
        assertEquals("8081", bankB["server.port"])
    }

    @Test
    fun `both identities are valid`() {
        listOf(bankA, bankB).forEach {
            BankIdentity(it.getValue("bank.identity.name"), it.getValue("bank.identity.bic"), it.getValue("bank.identity.bank-code"))
        }
    }

    private fun schemaOf(properties: Map<String, String>) =
        Regex("currentSchema=(\\w+)").find(properties.getValue("spring.datasource.url"))?.groupValues?.get(1) ?: "public"

    private fun load(file: String): Map<String, String> =
        YamlPropertySourceLoader().load(file, ClassPathResource(file))
            .flatMap { source -> (source.source as Map<*, *>).map { (key, value) -> key.toString() to value.toString() } }
            .toMap()
}
