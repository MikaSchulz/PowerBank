package com.example.bank.config

import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class TemporalConfig {

    /**
     * Temporal's default Jackson setup can't handle Kotlin data classes without a default constructor,
     * so we use Temporal's default ObjectMapper plus the Kotlin module.
     * (`KotlinObjectMapperFactory` from temporal-kotlin doesn't match Spring Boot 4's Jackson version.)
     * Stage 9 adds the payload codec for encryption here.
     */
    @Bean
    fun mainDataConverter(): DataConverter = kotlinDataConverter()

    companion object {
        fun kotlinDataConverter(): DataConverter {
            val mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper().registerModule(kotlinModule())
            return DefaultDataConverter.newDefaultInstance()
                .withPayloadConverterOverrides(JacksonJsonPayloadConverter(mapper))
        }
    }
}
