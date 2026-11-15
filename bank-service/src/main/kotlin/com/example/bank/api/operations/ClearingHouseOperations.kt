package com.example.bank.api.operations

import com.example.bank.activities.ClearingHouseProperties
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/** The clearing house can't be reached, or failed. */
class ClearingHouseUnavailable(cause: Exception) : RuntimeException("Clearing house not reachable", cause)

/**
 * The clearing house's operator endpoints, reached through this bank (the browser can't call another origin). Its
 * answers are passed through as they are; a 4xx (invalid settings) becomes the bank's 400.
 */
@Component
class ClearingHouseOperations(restClient: RestClient.Builder, properties: ClearingHouseProperties) {

    private val http = restClient.baseUrl("${properties.url}/api/clearing").build()

    fun chaos(): Map<String, Any?> = call { http.get().uri("/chaos").retrieve().body(MAP) }

    fun chaos(settings: Map<String, Any?>): Map<String, Any?> = call { http.put().uri("/chaos").body(settings).retrieve().body(MAP) }

    fun settlementAccounts(): List<Map<String, Any?>> = call { http.get().uri("/settlement-accounts").retrieve().body(LIST) }

    private fun <T : Any> call(request: () -> T?): T = try {
        checkNotNull(request()) { "Empty answer from the clearing house" }
    } catch (e: HttpClientErrorException) {
        throw IllegalArgumentException(e.getResponseBodyAs(ProblemDetail::class.java)?.detail ?: "Refused by the clearing house", e)
    } catch (e: RestClientResponseException) {
        throw ClearingHouseUnavailable(e)
    } catch (e: ResourceAccessException) {
        throw ClearingHouseUnavailable(e)
    }

    private companion object {
        val MAP = object : ParameterizedTypeReference<Map<String, Any?>>() {}
        val LIST = object : ParameterizedTypeReference<List<Map<String, Any?>>>() {}
    }
}

@RestControllerAdvice
class ClearingHouseUnavailableHandler {
    @ExceptionHandler(ClearingHouseUnavailable::class)
    fun unavailable(e: ClearingHouseUnavailable) = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.message)
}
