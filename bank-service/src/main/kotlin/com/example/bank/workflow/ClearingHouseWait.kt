package com.example.bank.workflow

import com.example.bank.activities.ClearingHouseActivities
import com.example.bank.activities.StatusReport
import com.example.payments.messages.Pacs002
import com.example.payments.messages.TxSts
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import org.slf4j.Logger
import java.time.Duration
import java.util.UUID

/**
 * Waiting for a payment's final status at the clearing house, the same for an outgoing transfer and an incoming
 * payment: the clearing house's confirmation (the workflow's update `confirm`), or, without it, status inquiries
 * (pacs.028). Workflow code: created by a workflow and run on its thread, only Temporal building blocks.
 */
internal class ClearingHouseWait(private val log: Logger) {

    /** Status inquiries: retried with backoff for a minute, then the wait asks again later. */
    private val inquiries = Workflow.newActivityStub(
        ClearingHouseActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(5))
            .setScheduleToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(20))
                    .build(),
            )
            .build(),
    )

    /** The clearing house's confirmation (pacs.002 ACSC or RJCT); null until it arrives. */
    var confirmation: Pacs002? = null
        private set

    /**
     * True while the workflow waits for a confirmation. Set it before the message that makes the clearing house
     * confirm: its confirmation may arrive before that message's activity has returned.
     */
    var expected = false

    /** For the workflow's update validator: rejects a confirmation nobody waits for, before it reaches the history. */
    fun validate(confirmation: Pacs002, uetr: UUID?) {
        check(expected && this.confirmation == null) { "No confirmation expected for this payment" }
        check(confirmation.uetr == uetr) { "Confirmation for another payment" }
        check(confirmation.isFinal) { "A confirmation is ACSC or RJCT" }
    }

    /** For the workflow's update handler: only records the confirmation; [await] acts on it. */
    fun confirm(confirmation: Pacs002) {
        this.confirmation = confirmation
    }

    /**
     * Waits for the confirmation until [CONFIRMATION_TIMEOUT] after the acceptance, then asks with status inquiries
     * every [INQUIRY_INTERVAL]. After [INQUIRY_PERIOD] without a final status it calls [investigate] and keeps asking
     * every [INVESTIGATION_INTERVAL]; a late confirmation ends the wait at any time.
     *
     * [unknownIsNotReceived]: the bank's own payment that the clearing house never acknowledged. Then "unknown" after
     * the deadline is final, because the clearing house refuses a payment that arrives late. Otherwise it is not.
     */
    fun await(
        uetr: UUID,
        acceptedAt: Long,
        unknownIsNotReceived: Boolean,
        investigating: () -> Boolean,
        investigate: () -> Unit,
    ): FinalStatus {
        Workflow.await(until(acceptedAt + CONFIRMATION_TIMEOUT.toMillis())) { confirmation != null }
        val inquiringSince = Workflow.currentTimeMillis()
        while (true) {
            confirmation?.let { return it.finalStatus(resolvedBy = "confirmation") }
            val report = inquire(uetr)
            val status = report?.status
            when {
                status != null && status.isFinal -> return status.finalStatus(resolvedBy = "inquiry")
                report != null && status == null && unknownIsNotReceived -> return FinalStatus.NotReceived
                // Otherwise not final yet (PDNG), not reachable, or unknown although it confirmed receipt: ask again.
            }
            if (!investigating() && Workflow.currentTimeMillis() - inquiringSince >= INQUIRY_PERIOD.toMillis()) {
                investigate()
                log.warn("Payment {}: no final status from the clearing house, under investigation", uetr)
            }
            Workflow.await(if (investigating()) INVESTIGATION_INTERVAL else INQUIRY_INTERVAL) { confirmation != null }
        }
    }

    private fun inquire(uetr: UUID): StatusReport? = try {
        inquiries.inquire(uetr)
    } catch (e: ActivityFailure) {
        log.warn("Payment {}: status inquiry failed", uetr, e)
        null
    }

    private val Pacs002.isFinal get() = txSts == TxSts.ACSC || txSts == TxSts.RJCT

    private fun Pacs002.finalStatus(resolvedBy: String) =
        if (txSts == TxSts.ACSC) FinalStatus.Settled(resolvedBy) else FinalStatus.Rejected(reasonCode, resolvedBy)

    companion object {
        /** SCT Inst: the payment must be through within 10 seconds of its acceptance at the debtor bank. */
        val SCHEME_DEADLINE: Duration = Duration.ofSeconds(10)

        /** After this, counted from the acceptance, the bank stops waiting for the confirmation and asks. */
        val CONFIRMATION_TIMEOUT: Duration = Duration.ofSeconds(20)
        val INQUIRY_INTERVAL: Duration = Duration.ofSeconds(30)

        /** Without a final status for this long, the payment goes under investigation. */
        val INQUIRY_PERIOD: Duration = Duration.ofMinutes(5)
        val INVESTIGATION_INTERVAL: Duration = Duration.ofMinutes(10)
    }
}

/** What the clearing house's books say in the end; not serialized, only for the workflows' branching. */
internal sealed interface FinalStatus {
    /** How the bank learned it: "confirmation" or "inquiry" (metric tag). */
    val resolvedBy: String

    data class Settled(override val resolvedBy: String) : FinalStatus

    data class Rejected(val reasonCode: String?, override val resolvedBy: String) : FinalStatus

    /** The bank's own payment never reached the clearing house. */
    data object NotReceived : FinalStatus {
        override val resolvedBy = "inquiry"
    }
}

/** How long until [epochMillis] in workflow time; zero if it has passed. */
internal fun until(epochMillis: Long): Duration = Duration.ofMillis((epochMillis - Workflow.currentTimeMillis()).coerceAtLeast(0))

/** A stub for a message that must reach the clearing house before the scheme's deadline, counted from [acceptedAt]. */
internal fun beforeDeadline(acceptedAt: Long): ClearingHouseActivities = Workflow.newActivityStub(
    ClearingHouseActivities::class.java,
    ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(5))
        .setScheduleToCloseTimeout(until(acceptedAt + ClearingHouseWait.SCHEME_DEADLINE.toMillis()).coerceAtLeast(Duration.ofSeconds(1)))
        .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofMillis(500)).build())
        .build(),
)
