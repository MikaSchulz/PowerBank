package com.example.clearing.activities

import com.example.clearing.config.ClearingProperties
import com.example.clearing.workflow.CLEARING_TASK_QUEUE
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.util.concurrent.ThreadLocalRandom

/** Chaos for demos (`PUT /api/clearing/chaos`): delayed or lost forwards, so the creditor bank misses the deadline. */
data class ChaosSettings(val forwardDelayMs: Long = 0, val dropForwardRate: Double = 0.0) {
    init {
        require(forwardDelayMs in 0..MAX_DELAY_MS) { "forwardDelayMs must be between 0 and $MAX_DELAY_MS" }
        require(dropForwardRate in 0.0..1.0) { "dropForwardRate must be between 0 and 1" }
    }

    companion object {
        const val MAX_DELAY_MS = 30_000L
    }
}

@Component
class Chaos {
    @Volatile var settings = ChaosSettings()
}

/**
 * Sends the messages to the banks: `POST <bank url>/api/clearing/inbound/pacs008` and `…/pacs002`. The RestClient's
 * observation puts the current trace context (`traceparent`) on every request, so the bank continues the payment's trace.
 */
@Component
@ActivityImpl(taskQueues = [CLEARING_TASK_QUEUE])
class BankMessagingActivitiesImpl(
    restClient: RestClient.Builder,
    private val properties: ClearingProperties,
    private val chaos: Chaos,
) : BankMessagingActivities {

    private val http = restClient.build()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun forward(forward: Forward) {
        val settings = chaos.settings
        if (settings.forwardDelayMs > 0) Thread.sleep(settings.forwardDelayMs)
        if (ThreadLocalRandom.current().nextDouble() < settings.dropForwardRate) {
            log.info("Payment {}: forward dropped (chaos)", forward.pacs008.uetr)
            return
        }
        send(forward.creditorBank, "pacs008", forward.pacs008)
    }

    override fun confirm(confirmation: Confirmation) = send(confirmation.bank, "pacs002", confirmation.pacs002)

    private fun send(bankCode: String, message: String, body: Any) {
        val participant = properties.participant(bankCode)
            ?: throw ApplicationFailure.newNonRetryableFailure("Bank $bankCode is not a participant", "UnknownParticipant")
        try {
            http.post().uri("${participant.url}/api/clearing/inbound/$message").body(body).retrieve().toBodilessEntity()
        } catch (e: HttpClientErrorException) {
            // 4xx: the bank refuses this message; retrying won't change it. 5xx and connection errors are retried.
            throw ApplicationFailure.newNonRetryableFailureWithCause("Bank $bankCode refused $message: ${e.statusCode}", "Refused", e)
        }
    }
}
