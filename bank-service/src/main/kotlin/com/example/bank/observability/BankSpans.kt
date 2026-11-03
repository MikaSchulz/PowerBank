package com.example.bank.observability

import io.opentelemetry.api.trace.Span

/**
 * Pseudonymous business IDs as span attributes, so traces can be searched in Tempo with TraceQL,
 * e.g. `{ span.bank.transfer.id = "<id>" }` or `{ span.bank.account.id = "<id>" }`. Never names or IBANs.
 */
object BankSpans {
    const val TRANSFER_ID = "bank.transfer.id"
    const val TRANSFER_KIND = "bank.transfer.kind"

    /** The account a ledger command works on (one per `bank.account.command` span). */
    const val ACCOUNT_ID = "bank.account.id"

    /** Both accounts locked by one booking, comma-separated, in lock order. */
    const val ACCOUNT_IDS = "bank.account.ids"
    const val DEBTOR_ACCOUNT_ID = "bank.debtor.account.id"
    const val CREDITOR_ACCOUNT_ID = "bank.creditor.account.id"

    /** Adds attributes to the current span (HTTP request, Temporal activity, …). Null values are skipped. */
    fun tag(vararg attributes: Pair<String, Any?>) {
        val span = Span.current()
        if (!span.spanContext.isValid) return
        attributes.forEach { (key, value) -> if (value != null) span.setAttribute(key, value.toString()) }
    }
}
