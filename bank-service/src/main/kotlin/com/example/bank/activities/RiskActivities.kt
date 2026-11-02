package com.example.bank.activities

import io.temporal.activity.ActivityInterface
import java.util.UUID

data class RiskCheck(val transferId: UUID, val debtorAccountId: UUID, val amountCents: Long, val external: Boolean)

data class RiskAssessment(val reviewRequired: Boolean, val reason: String? = null)

@ActivityInterface(namePrefix = "Risk")
interface RiskActivities {
    fun assess(check: RiskCheck): RiskAssessment
}
