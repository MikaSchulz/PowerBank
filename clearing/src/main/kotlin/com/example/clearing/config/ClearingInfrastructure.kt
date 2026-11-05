package com.example.clearing.config

import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.micrometer.observation.ObservationPredicate
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.observation.ServerRequestObservationContext

/** Temporal and OpenTelemetry, set up like in the bank-service. */
@Configuration(proxyBeanMethods = false)
class ClearingInfrastructure {

    /** Kotlin data classes as Temporal payloads (Temporal's default Jackson setup can't create them). */
    @Bean
    fun mainDataConverter(): DataConverter = kotlinDataConverter()

    @Bean
    fun installOpenTelemetryLogAppender(openTelemetry: OpenTelemetry) =
        InitializingBean { OpenTelemetryAppender.install(openTelemetry) }

    /** Only API requests become traces, not actuator calls. */
    @Bean
    fun traceOnlyApiRequests() = ObservationPredicate { _, context ->
        val path = (context as? ServerRequestObservationContext)?.carrier?.requestURI
        path == null || path.startsWith("/api/")
    }

    companion object {
        fun kotlinDataConverter(): DataConverter {
            val mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper().registerModule(kotlinModule())
            return DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(JacksonJsonPayloadConverter(mapper))
        }
    }
}
