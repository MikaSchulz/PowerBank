package com.example.bank.activities

import com.example.bank.domain.AccountId
import com.example.bank.domain.account.AccountKind
import com.example.bank.domain.risk.RiskVerdict
import com.example.bank.domain.risk.TransferRiskPolicy
import com.example.bank.ledger.LedgerQueries
import com.example.bank.workflow.BANK_TASK_QUEUE
import io.temporal.spring.boot.ActivityImpl
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties("bank.risk")
data class RiskProperties(
    /** From this amount (in cents) on, every transfer needs manual approval. Default: 10,000 €. */
    val reviewThresholdCents: Long = 10_000_00,
    /** Approval limit for transfers to external banks. Default: 5,000 €. */
    val externalReviewThresholdCents: Long = 5_000_00,
)

/** The rules live as a pure function in the `domain` module; the activity only supplies configuration and account data. */
@Component
@ActivityImpl(taskQueues = [BANK_TASK_QUEUE])
class RiskActivitiesImpl(
    private val properties: RiskProperties,
    private val queries: LedgerQueries,
) : RiskActivities {

    override fun assess(check: RiskCheck): RiskAssessment {
        val policy = TransferRiskPolicy(properties.reviewThresholdCents, properties.externalReviewThresholdCents)
        val debtorKind = queries.account(AccountId(check.debtorAccountId))?.kind?.let(AccountKind::valueOf)
        return when (val verdict = policy.assess(check.amountCents, debtorKind, check.external)) {
            RiskVerdict.Clear -> RiskAssessment(reviewRequired = false)
            is RiskVerdict.ReviewRequired -> RiskAssessment(reviewRequired = true, reason = verdict.reason)
        }
    }
}
