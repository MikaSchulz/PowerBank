package com.example.bank.domain.risk

import com.example.bank.domain.account.AccountKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TransferRiskPolicyTest {

    private val policy = TransferRiskPolicy(reviewThresholdCents = 10_000_00, externalReviewThresholdCents = 5_000_00)

    @Test
    fun `small amounts pass`() =
        assertEquals(RiskVerdict.Clear, policy.assess(4_999_99, AccountKind.CUSTOMER, external = true))

    @Test
    fun `from the limit on an approval is required`() =
        assertEquals(
            RiskVerdict.ReviewRequired("Amount exceeds approval limit"),
            policy.assess(10_000_00, AccountKind.CUSTOMER, external = false),
        )

    @Test
    fun `external banks have a lower limit`() {
        assertEquals(RiskVerdict.Clear, policy.assess(6_000_00, AccountKind.CUSTOMER, external = false))
        assertEquals(
            RiskVerdict.ReviewRequired("Amount exceeds approval limit for external banks"),
            policy.assess(6_000_00, AccountKind.CUSTOMER, external = true),
        )
    }

    @Test
    fun `postings from system accounts are not checked`() =
        assertEquals(RiskVerdict.Clear, policy.assess(1_000_000_00, AccountKind.SYSTEM, external = false))
}
