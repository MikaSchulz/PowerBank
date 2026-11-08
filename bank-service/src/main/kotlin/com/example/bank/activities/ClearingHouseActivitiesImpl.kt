package com.example.bank.activities

import com.example.bank.config.BankIdentity
import com.example.bank.domain.AccountId
import com.example.bank.ledger.LedgerQueries
import com.example.bank.observability.BankSpans
import com.example.bank.workflow.BANK_TASK_QUEUE
import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
import com.example.payments.messages.Pacs028
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.temporal.activity.Activity
import io.temporal.spring.boot.ActivityImpl
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

@ConfigurationProperties("bank.clearing")
data class ClearingHouseProperties(
    /** The clearing house: pacs.008 and pacs.028 go to `<url>/api/clearing/…`. */
    val url: String = "http://localhost:8090",
)

/**
 * The messages to the clearing house over HTTP. Every call is its own span and timer `bank.clearing.call` with the
 * `message` (pacs.008, pacs.028) and its `outcome`; the RestClient's observation puts the trace context on the request,
 * so the clearing house continues the payment's trace.
 */
@Component
@ActivityImpl(taskQueues = [BANK_TASK_QUEUE])
class ClearingHouseActivitiesImpl(
    restClient: RestClient.Builder,
    properties: ClearingHouseProperties,
    private val identity: BankIdentity,
    private val queries: LedgerQueries,
    private val observations: ObservationRegistry,
) : ClearingHouseActivities {

    private val http = restClient
        .baseUrl("${properties.url}/api/clearing")
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build()).apply { setReadTimeout(TIMEOUT) },
        )
        .build()

    override fun submit(payment: InstantPayment): Submission = observe("pacs.008", payment.transferId, Submission::outcome) {
        val debtorIban = queries.account(AccountId(payment.debtorAccountId))?.iban
            ?: return@observe Submission(received = false, reason = "The debtor account has no IBAN")
        val pacs008 = Pacs008(
            // The same message on every attempt: a retry is a repetition, not a second payment.
            msgId = UUID.nameUUIDFromBytes("pacs.008:${payment.transferId}".toByteArray()),
            uetr = payment.transferId,
            endToEndId = payment.transferId.toString().replace("-", ""), // ISO 20022 allows 35 characters
            acceptanceDateTime = payment.acceptedAt,
            amountCents = payment.amountCents,
            debtorIban = debtorIban,
            debtorAgentBic = identity.bic,
            creditorIban = payment.creditorIban,
        )
        try {
            http.post().uri("/pacs008").body(pacs008).retrieve().toBodilessEntity()
            Submission(received = true)
        } catch (e: HttpClientErrorException) {
            // 409: an earlier attempt got through, its answer was lost. Any other 4xx: refused, nothing recorded.
            if (e.statusCode.value() == HttpStatus.CONFLICT.value()) {
                Submission(received = true)
            } else {
                Submission(received = false, reason = "Refused by the clearing house (HTTP ${e.statusCode.value()})")
            }
        }
    }

    override fun inquire(transferId: UUID): StatusReport =
        observe("pacs.028", transferId, StatusReport::outcome) {
            try {
                StatusReport(http.post().uri("/pacs028").body(Pacs028(UUID.randomUUID(), transferId)).retrieve().body(Pacs002::class.java))
            } catch (_: HttpClientErrorException.NotFound) {
                StatusReport(status = null)
            }
        }

    private fun <T : Any> observe(message: String, transferId: UUID, outcome: (T) -> String, call: () -> T): T {
        BankSpans.tag(BankSpans.TRANSFER_ID to transferId)
        val observation = Observation.createNotStarted("bank.clearing.call", observations)
            .lowCardinalityKeyValue("message", message)
            .highCardinalityKeyValue(BankSpans.TRANSFER_ID, transferId.toString())
            .highCardinalityKeyValue("temporal.activity.attempt", Activity.getExecutionContext().info.attempt.toString())
            .start()
        return try {
            observation.openScope().use { call() }.also { observation.lowCardinalityKeyValue("outcome", outcome(it)) }
        } catch (e: RuntimeException) { // records any failure, then rethrows it: Temporal retries
            observation.lowCardinalityKeyValue("outcome", "failure")
            observation.error(e)
            throw e
        } finally {
            observation.stop()
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}

private val Submission.outcome: String get() = if (received) "received" else "refused"

private val StatusReport.outcome: String get() = status?.txSts?.name ?: "unknown"
