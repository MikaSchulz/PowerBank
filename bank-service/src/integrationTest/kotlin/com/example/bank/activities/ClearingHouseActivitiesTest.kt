package com.example.bank.activities

import com.example.bank.FakeClearingHouse
import com.example.bank.IntegrationTest
import com.example.bank.config.BankIdentity
import com.example.bank.config.TemporalConfig
import com.example.bank.customer.CustomerRecords
import com.example.bank.domain.AccountId
import com.example.bank.ledger.Ledger
import com.example.bank.ledger.LedgerQueries
import com.example.payments.messages.Pacs002
import com.example.payments.messages.ReasonCodes
import com.example.payments.messages.TxSts
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.testing.TestActivityEnvironment
import io.temporal.testing.TestEnvironmentOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** The HTTP side of the clearing house activities: what the bank sends, and how it reads the answers. */
@IntegrationTest
class ClearingHouseActivitiesTest @Autowired constructor(
    activities: ClearingHouseActivitiesImpl,
    private val fake: FakeClearingHouse,
    private val ledger: Ledger,
    private val queries: LedgerQueries,
    private val customers: CustomerRecords,
    private val identity: BankIdentity,
) {

    private val env = TestActivityEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(TemporalConfig.kotlinDataConverter()).build())
            .build(),
    ).apply { registerActivitiesImplementations(activities) }

    // One attempt: a technical error shows up as an ActivityFailure instead of being retried.
    private val clearingHouse = env.newActivityStub(
        ClearingHouseActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build(),
    )

    @AfterEach
    fun tearDown() {
        fake.reset()
        env.close()
    }

    @Test
    fun `a payment goes to the clearing house as pacs008, the same message on every attempt`() {
        val (account, payment) = payment()

        repeat(2) { assertEquals(Submission(received = true), clearingHouse.submit(payment)) }

        val (first, second) = fake.payments
        assertEquals(payment.transferId, first.uetr)
        assertEquals(payment.transferId.toString().replace("-", ""), first.endToEndId)
        assertEquals(payment.acceptedAt, first.acceptanceDateTime)
        assertEquals(40_00, first.amountCents)
        assertEquals(queries.account(account)?.iban, first.debtorIban)
        assertEquals(identity.bic, first.debtorAgentBic)
        assertEquals("DE89370400440532013000", first.creditorIban)
        assertEquals(first.msgId, second.msgId, "a retry repeats the message")
    }

    @Test
    fun `409 means an earlier attempt got through, any other 4xx is a refusal`() {
        val (_, payment) = payment()

        fake.onPacs008 = { 409 }
        assertEquals(Submission(received = true), clearingHouse.submit(payment))

        fake.onPacs008 = { 422 }
        assertEquals(Submission(received = false, reason = "Refused by the clearing house (HTTP 422)"), clearingHouse.submit(payment))
    }

    @Test
    fun `a server error is left to Temporal's retries`() {
        val (_, payment) = payment()
        fake.onPacs008 = { 503 }

        assertThrows<ActivityFailure> { clearingHouse.submit(payment) }
    }

    @Test
    fun `a status inquiry returns the clearing house's pacs002, or nothing for an unknown payment`() {
        val uetr = UUID.randomUUID()

        assertEquals(StatusReport(status = null), clearingHouse.inquire(uetr))

        fake.onPacs028 = { Pacs002(UUID.randomUUID(), it.uetr, TxSts.RJCT, ReasonCodes.CLOSED_ACCOUNT) }
        val report = clearingHouse.inquire(uetr)
        assertEquals(TxSts.RJCT, report.status?.txSts)
        assertEquals(ReasonCodes.CLOSED_ACCOUNT, report.status?.reasonCode)
        assertEquals(uetr, fake.inquiries.last().uetr)
    }

    private fun payment(): Pair<AccountId, InstantPayment> {
        val account = AccountId(UUID.randomUUID())
        ledger.openAccount(account, customers.createPlaceholder())
        return account to
            InstantPayment(UUID.randomUUID(), account.value, "DE89370400440532013000", 40_00, Instant.parse("2026-11-08T10:00:00Z"))
    }
}
