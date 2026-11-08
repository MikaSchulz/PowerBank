package com.example.clearing.domain

import com.example.payments.messages.ReasonCodes
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class PaymentDeciderTest {

    private val uetr = UUID.randomUUID()
    private val received = PaymentReceived(uetr, "99000001", "99000002", 100_00)
    private val reserved = LiquidityReserved(uetr)
    private val forwarded = PaymentForwarded(uetr)

    private fun given(vararg events: PaymentEvent) = events.fold(paymentDecider.initialState, paymentDecider.evolve)

    private fun decide(command: PaymentCommand, vararg history: PaymentEvent) =
        runBlocking { paymentDecider.decide(command, given(*history)).toList() }

    @Test
    fun `liquidity is reserved if the debtor bank's balance covers the amount`() =
        assertEquals(listOf(reserved), decide(ReserveLiquidity(uetr, availableCents = 100_00), received))

    @Test
    fun `without enough liquidity the payment is rejected before it is forwarded`() = assertEquals(
        listOf(PaymentRejected(uetr, ReasonCodes.INSUFFICIENT_FUNDS, releasesReservation = false)),
        decide(ReserveLiquidity(uetr, availableCents = 99_99), received),
    )

    @Test
    fun `a payment to a bank that is not a participant is rejected`() = assertEquals(
        listOf(PaymentRejected(uetr, ReasonCodes.UNKNOWN_CREDITOR_AGENT, releasesReservation = false)),
        decide(ReserveLiquidity(uetr, availableCents = 1_000_00), PaymentReceived(uetr, "99000001", null, 100_00)),
    )

    @Test
    fun `the creditor bank accepts, then it is settled`() {
        assertEquals(listOf(CreditorAccepted(uetr)), decide(RecordCreditorAnswer(uetr, true, null), received, reserved, forwarded))
        assertEquals(listOf(PaymentSettled(uetr)), decide(SettlePayment(uetr), received, reserved, forwarded, CreditorAccepted(uetr)))
    }

    @Test
    fun `the creditor bank rejects, and the reservation is released`() = assertEquals(
        listOf(PaymentRejected(uetr, ReasonCodes.CLOSED_ACCOUNT, releasesReservation = true)),
        decide(RecordCreditorAnswer(uetr, false, ReasonCodes.CLOSED_ACCOUNT), received, reserved, forwarded),
    )

    @Test
    fun `no answer in time rejects with AB05`() = assertEquals(
        listOf(PaymentRejected(uetr, ReasonCodes.TIMEOUT_CREDITOR_AGENT, releasesReservation = true)),
        decide(ExpirePayment(uetr), received, reserved, forwarded),
    )

    @Test
    fun `a settled payment can't expire or be settled twice`() {
        val settled = arrayOf(received, reserved, forwarded, CreditorAccepted(uetr), PaymentSettled(uetr))
        assertEquals(
            listOf(PaymentCommandRejected(uetr, "Payment in status SETTLED cannot be expired")),
            decide(ExpirePayment(uetr), *settled),
        )
        assertEquals(emptyList(), decide(SettlePayment(uetr), *settled))
    }

    @Test
    fun `nothing is settled that the creditor bank didn't accept`() = assertEquals(
        listOf(PaymentCommandRejected(uetr, "Payment in status FORWARDED cannot be settled")),
        decide(SettlePayment(uetr), received, reserved, forwarded),
    )
}
