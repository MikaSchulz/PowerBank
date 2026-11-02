package com.example.bank.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Tracing and metrics need no code: Spring Boot creates the OpenTelemetry SDK, and the Temporal starter adds its
 * tracing interceptors (client and worker) as soon as `temporal-opentracing` and the OpenTracing shim are on the
 * classpath. Only the Logback appender has to be connected to the SDK by hand.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    @Bean
    fun installOpenTelemetryLogAppender(openTelemetry: OpenTelemetry) =
        InitializingBean { OpenTelemetryAppender.install(openTelemetry) }
}
