package com.example.bank.domain

import java.util.UUID

/** Pseudonymous IDs: the ledger only knows these, never names or IBANs. */
@JvmInline
value class AccountId(val value: UUID) {
    override fun toString() = value.toString()
}

@JvmInline
value class TransferId(val value: UUID) {
    override fun toString() = value.toString()
}

@JvmInline
value class CustomerId(val value: UUID) {
    override fun toString() = value.toString()
}
