package com.example.clearing.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** The clearing house's rules and its participants (the banks). */
@ConfigurationProperties("clearing")
data class ClearingProperties(
    /** The creditor bank has to answer within it (SCT Inst: 10 seconds). */
    val deadline: Duration = Duration.ofSeconds(10),
    /** What every participant starts with on its settlement account, funded by the central bank. */
    val liquidityCents: Long = 100_000_000,
    val participants: List<Participant> = emptyList(),
) {
    data class Participant(val bankCode: String, val bic: String, val url: String)

    fun participant(bankCode: String): Participant? = participants.firstOrNull { it.bankCode == bankCode }
}
