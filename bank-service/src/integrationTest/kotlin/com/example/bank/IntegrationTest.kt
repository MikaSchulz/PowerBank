package com.example.bank

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * An integration test against the local stack from compose.yaml (`docker compose up -d --wait`), in its own Postgres
 * schema and Temporal namespace (profile "integration"), so it doesn't touch the data of the running app.
 *
 * All integration tests share this one Spring context: with a real Temporal server, a second context would poll the
 * same task queue and pick up the other context's workflows.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(IntegrationTestConfiguration::class)
annotation class IntegrationTest

@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfiguration {

    /** Spring Boot adds every SpanExporter bean to its span processor; TracePropagationTest reads the spans here. */
    @Bean
    fun inMemorySpanExporter(): InMemorySpanExporter = InMemorySpanExporter.create()
}
