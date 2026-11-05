package com.example.clearing.domain

import com.example.payments.messages.ReasonCodes
import com.fraktalio.fmodel.domain.Decider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/**
 * A payment through the clearing house:
 *
 * ```
 * RECEIVED → RESERVED → FORWARDED → ACCEPTED → SETTLED
 *     ↘          ↘          ↘
 *      REJECTED (unknown creditor bank, no liquidity, creditor rejects, no answer in time)
 * ```
 */
enum class PaymentStatus(val finished: Boolean = false) {
    /** Received from the debtor bank. */
    RECEIVED,

    /** The amount is reserved on the debtor bank's settlement account. */
    RESERVED,

    /** Sent to the creditor bank; its answer is due within the deadline. */
    FORWARDED,

    /** The creditor bank accepted. */
    ACCEPTED,

    /** The money moved from the debtor bank's to the creditor bank's settlement account. */
    SETTLED(finished = true),

    /** Rejected; a reservation was released. */
    REJECTED(finished = true),
}

data class ClearingPayment(
    val status: PaymentStatus? = null,
    val debtorBank: String? = null,
    /** Null if the creditor's bank is not a participant. */
    val creditorBank: String? = null,
    val amountCents: Long = 0,
    val reasonCode: String? = null,
) {
    val exists: Boolean get() = status != null
}

sealed interface PaymentCommand {
    val uetr: UUID
}

data class ReceivePayment(override val uetr: UUID, val debtorBank: String, val creditorBank: String?, val amountCents: Long) :
    PaymentCommand

/** Reserve the amount, given the debtor bank's current settlement balance. */
data class ReserveLiquidity(override val uetr: UUID, val availableCents: Long) : PaymentCommand

data class MarkForwarded(override val uetr: UUID) : PaymentCommand

data class RecordCreditorAnswer(override val uetr: UUID, val accepted: Boolean, val reasonCode: String?) : PaymentCommand

/** The creditor bank did not answer within the deadline, or could not be reached. */
data class ExpirePayment(override val uetr: UUID) : PaymentCommand

data class SettlePayment(override val uetr: UUID) : PaymentCommand

sealed interface PaymentEvent {
    val uetr: UUID
}

/** An event that moves the payment to [status]. */
sealed interface PaymentStatusChanged : PaymentEvent {
    val status: PaymentStatus
    val reasonCode: String? get() = null
}

data class PaymentReceived(override val uetr: UUID, val debtorBank: String, val creditorBank: String?, val amountCents: Long) :
    PaymentStatusChanged {
    override val status get() = PaymentStatus.RECEIVED
}

/** Money event: debtor bank → reserved. */
data class LiquidityReserved(override val uetr: UUID) : PaymentStatusChanged {
    override val status get() = PaymentStatus.RESERVED
}

data class PaymentForwarded(override val uetr: UUID) : PaymentStatusChanged {
    override val status get() = PaymentStatus.FORWARDED
}

data class CreditorAccepted(override val uetr: UUID) : PaymentStatusChanged {
    override val status get() = PaymentStatus.ACCEPTED
}

/** Money event: reserved → creditor bank. */
data class PaymentSettled(override val uetr: UUID) : PaymentStatusChanged {
    override val status get() = PaymentStatus.SETTLED
}

/** Money event if [releasesReservation]: reserved → debtor bank. */
data class PaymentRejected(override val uetr: UUID, override val reasonCode: String, val releasesReservation: Boolean) :
    PaymentStatusChanged {
    override val status get() = PaymentStatus.REJECTED
}

/** The command is not allowed in the payment's status. Never stored. */
data class PaymentCommandRejected(override val uetr: UUID, val reason: String) : PaymentEvent

/** The rules of a payment through the clearing house. Pure functions, no I/O; repeating a command changes nothing. */
val paymentDecider: Decider<PaymentCommand, ClearingPayment, PaymentEvent> = Decider(
    initialState = ClearingPayment(),
    decide = { command, payment -> decide(command, payment) },
    evolve = { payment, event -> evolve(payment, event) },
)

internal fun decide(c: PaymentCommand, s: ClearingPayment): Flow<PaymentEvent> {
    if (c !is ReceivePayment && !s.exists) return reject(c, "Payment does not exist")
    return when (c) {
        is ReceivePayment -> if (s.exists) emptyFlow() else flowOf(PaymentReceived(c.uetr, c.debtorBank, c.creditorBank, c.amountCents))

        is ReserveLiquidity -> when {
            s.status == PaymentStatus.RESERVED -> emptyFlow()
            s.status != PaymentStatus.RECEIVED -> notAllowed(c, s, "reserved")
            s.creditorBank == null -> flowOf(PaymentRejected(c.uetr, ReasonCodes.UNKNOWN_CREDITOR_AGENT, releasesReservation = false))
            c.availableCents < s.amountCents -> flowOf(PaymentRejected(c.uetr, ReasonCodes.INSUFFICIENT_FUNDS, releasesReservation = false))
            else -> flowOf(LiquidityReserved(c.uetr))
        }

        is MarkForwarded -> when (s.status) {
            PaymentStatus.RESERVED -> flowOf(PaymentForwarded(c.uetr))
            PaymentStatus.FORWARDED -> emptyFlow()
            else -> notAllowed(c, s, "forwarded")
        }

        is RecordCreditorAnswer -> when (s.status) {
            PaymentStatus.FORWARDED -> flowOf(
                if (c.accepted) {
                    CreditorAccepted(c.uetr)
                } else {
                    PaymentRejected(c.uetr, c.reasonCode ?: ReasonCodes.INCORRECT_ACCOUNT, releasesReservation = true)
                },
            )
            PaymentStatus.ACCEPTED, PaymentStatus.REJECTED -> emptyFlow()
            else -> notAllowed(c, s, "answered")
        }

        is ExpirePayment -> when (s.status) {
            PaymentStatus.RESERVED, PaymentStatus.FORWARDED ->
                flowOf(PaymentRejected(c.uetr, ReasonCodes.TIMEOUT_CREDITOR_AGENT, releasesReservation = true))
            PaymentStatus.REJECTED -> emptyFlow()
            else -> notAllowed(c, s, "expired")
        }

        is SettlePayment -> when (s.status) {
            PaymentStatus.ACCEPTED -> flowOf(PaymentSettled(c.uetr))
            PaymentStatus.SETTLED -> emptyFlow()
            else -> notAllowed(c, s, "settled")
        }
    }
}

internal fun evolve(s: ClearingPayment, e: PaymentEvent): ClearingPayment = when (e) {
    is PaymentReceived -> ClearingPayment(PaymentStatus.RECEIVED, e.debtorBank, e.creditorBank, e.amountCents)
    is PaymentStatusChanged -> s.copy(status = e.status, reasonCode = e.reasonCode)
    is PaymentCommandRejected -> s
}

private fun notAllowed(c: PaymentCommand, s: ClearingPayment, action: String) =
    reject(c, "Payment in status ${s.status} cannot be $action")

private fun reject(c: PaymentCommand, reason: String): Flow<PaymentEvent> = flowOf(PaymentCommandRejected(c.uetr, reason))
