package com.example.bank.domain.risk

import com.example.bank.domain.account.AccountKind

sealed interface RiskVerdict {
    data object Clear : RiskVerdict

    data class ReviewRequired(val reason: String) : RiskVerdict
}

/**
 * Fraud suspicion or limit: when does a human have to approve a transfer?
 * A pure function, so the rules are testable without Spring and Temporal.
 */
data class TransferRiskPolicy(
    /** From this amount on, every transfer needs manual approval. */
    val reviewThresholdCents: Long,
    /** Transfers to external banks have a lower limit: the money is harder to get back. */
    val externalReviewThresholdCents: Long = reviewThresholdCents,
) {
    fun assess(amountCents: Long, debtorKind: AccountKind?, external: Boolean): RiskVerdict = when {
        debtorKind == AccountKind.SYSTEM -> RiskVerdict.Clear
        amountCents >= reviewThresholdCents -> RiskVerdict.ReviewRequired("Amount exceeds approval limit")
        external && amountCents >= externalReviewThresholdCents ->
            RiskVerdict.ReviewRequired("Amount exceeds approval limit for external banks")
        else -> RiskVerdict.Clear
    }
}
