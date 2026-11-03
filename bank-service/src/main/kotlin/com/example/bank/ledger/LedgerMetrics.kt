package com.example.bank.ledger

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * The ledger's state as gauges, for dashboards and alerts. Each gauge runs a small query whenever the metrics are
 * exported (every 10 s); if the database can't be reached, the gauge reports NaN.
 *
 * - `bank_ledger_sum_cents`: the sum of all postings. Double-entry bookkeeping keeps it at 0, anything else is a bug.
 * - `bank_transfers_in_flight{status}`: transfers still waiting (PENDING, e.g. for an approval) or on their way to
 *   an external bank (DEBITED).
 * - `bank_transfers_in_flight_age_max_seconds{status}`: how long the oldest of them has been waiting.
 */
@Component
class LedgerMetrics(private val queries: LedgerQueries) : MeterBinder {

    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder("bank.ledger.sum", queries) { it.sumOfAllPostings().toDouble() }
            .description("Sum of all postings; must always be 0")
            .baseUnit("cents")
            .register(registry)

        IN_FLIGHT.forEach { status ->
            Gauge.builder("bank.transfers.in.flight", queries) { it.countTransfers(status).toDouble() }
                .description("Transfers that are not finished yet")
                .tag("status", status.name)
                .register(registry)
            Gauge.builder("bank.transfers.in.flight.age.max", queries) { it.oldestTransferAgeSeconds(status) }
                .description("Age of the oldest transfer that is not finished yet")
                .tag("status", status.name)
                .baseUnit("seconds")
                .register(registry)
        }
    }

    companion object {
        private val IN_FLIGHT = listOf(TransferRecordStatus.PENDING, TransferRecordStatus.DEBITED)
    }
}
