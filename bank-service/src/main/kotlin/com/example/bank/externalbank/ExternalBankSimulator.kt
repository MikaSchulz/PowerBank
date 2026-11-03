package com.example.bank.externalbank

import com.example.bank.activities.ExternalBankAnswer
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

@ConfigurationProperties("bank.external-bank")
data class ExternalBankProperties(
    val minDelay: Duration = Duration.ofSeconds(1),
    val maxDelay: Duration = Duration.ofSeconds(4),
    /** Share of payments the external bank rejects for business reasons. */
    val rejectionRate: Double = 0.2,
    /** Share of calls that fail technically (timeout, 503, …) and must be retried. */
    val failureRate: Double = 0.1,
)

/** Simulator behavior, adjustable at runtime (e.g. from the UI). */
data class ExternalBankSettings(
    val minDelayMs: Long,
    val maxDelayMs: Long,
    val rejectionRate: Double,
    val failureRate: Double,
) {
    init {
        require(minDelayMs >= 0 && maxDelayMs >= minDelayMs) { "Delays must satisfy 0 <= minDelayMs <= maxDelayMs" }
        require(maxDelayMs <= MAX_DELAY_MS) { "maxDelayMs must not exceed $MAX_DELAY_MS (the activity's start-to-close timeout is 30 s)" }
        require(rejectionRate in 0.0..1.0 && failureRate in 0.0..1.0) { "Rates must be between 0 and 1" }
    }

    companion object {
        const val MAX_DELAY_MS = 25_000L
    }
}

class ExternalBankUnavailable : RuntimeException("External bank unavailable (simulated)")

/**
 * Simulated external bank: slow, sometimes fails technically and sometimes rejects.
 *
 * Like a real bank it is idempotent per transfer ID: a repeated call returns the same decision.
 * (Its memory is volatile; after a restart it decides anew.)
 * It always rejects IBANs ending in `000000`, which is handy for trying things out.
 */
@Component
class ExternalBankSimulator(properties: ExternalBankProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val decisions = ConcurrentHashMap<UUID, ExternalBankAnswer>()

    @Volatile
    var settings = ExternalBankSettings(
        minDelayMs = properties.minDelay.toMillis(),
        maxDelayMs = properties.maxDelay.toMillis(),
        rejectionRate = properties.rejectionRate,
        failureRate = properties.failureRate,
    )

    @Suppress("UNUSED_PARAMETER") // part of the payment like at a real bank; the simulator doesn't need it
    fun receive(transferId: UUID, creditorIban: String, amountCents: Long): ExternalBankAnswer {
        val current = settings
        sleepRandomly(current)
        if (ThreadLocalRandom.current().nextDouble() < current.failureRate) {
            log.info("External bank: technical error for transfer {}", transferId)
            throw ExternalBankUnavailable()
        }
        return decisions.computeIfAbsent(transferId) { decide(creditorIban, current) }
            .also { log.info("External bank: transfer {} -> {}", transferId, it) }
    }

    private fun decide(creditorIban: String, current: ExternalBankSettings): ExternalBankAnswer = when {
        creditorIban.endsWith("000000") -> ExternalBankAnswer(accepted = false, reason = "Creditor account closed")
        ThreadLocalRandom.current().nextDouble() < current.rejectionRate ->
            ExternalBankAnswer(accepted = false, reason = "Rejected by the creditor bank")
        else -> ExternalBankAnswer(accepted = true)
    }

    private fun sleepRandomly(current: ExternalBankSettings) {
        val delay = if (current.maxDelayMs > current.minDelayMs) {
            ThreadLocalRandom.current().nextLong(current.minDelayMs, current.maxDelayMs)
        } else {
            current.minDelayMs
        }
        if (delay > 0) Thread.sleep(delay)
    }
}
