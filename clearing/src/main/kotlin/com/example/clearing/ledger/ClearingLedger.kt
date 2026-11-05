package com.example.clearing.ledger

import com.example.clearing.config.ClearingProperties
import com.example.clearing.domain.ClearingPayment
import com.example.clearing.domain.ExpirePayment
import com.example.clearing.domain.LiquidityReserved
import com.example.clearing.domain.MarkForwarded
import com.example.clearing.domain.PaymentCommand
import com.example.clearing.domain.PaymentCommandRejected
import com.example.clearing.domain.PaymentEvent
import com.example.clearing.domain.PaymentReceived
import com.example.clearing.domain.PaymentRejected
import com.example.clearing.domain.PaymentSettled
import com.example.clearing.domain.PaymentStatus
import com.example.clearing.domain.PaymentStatusChanged
import com.example.clearing.domain.ReceivePayment
import com.example.clearing.domain.RecordCreditorAnswer
import com.example.clearing.domain.ReserveLiquidity
import com.example.clearing.domain.SettlePayment
import com.example.clearing.domain.paymentDecider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** A payment's status after a ledger operation, as the payment decider decided it. */
data class PaymentResult(val status: PaymentStatus, val reasonCode: String?)

data class PaymentRecord(
    val uetr: UUID,
    val status: PaymentStatus,
    val reasonCode: String?,
    val debtorBank: String,
    val creditorBank: String?,
    val amountCents: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SettlementBalance(val account: String, val balanceCents: Long)

/** A step the payment's status doesn't allow: a bug in the caller, not a business outcome. */
class IllegalPaymentState(uetr: UUID, reason: String) : IllegalStateException("Payment $uetr: $reason")

/**
 * The clearing house's books: settlement accounts in central bank money and the payments it clears. Every method is
 * one transaction: the payment decider decides, the money events become postings, and the payment's status and history
 * are stored. Double-entry: the postings of every payment (and every funding) sum up to zero.
 */
@Component
class ClearingLedger(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val properties: ClearingProperties,
) {

    /** Opens a settlement account for every participant and funds it once from the central bank. Idempotent. */
    fun fundParticipants() = inTransaction {
        properties.participants.forEach { participant ->
            val account = bankAccount(participant.bankCode)
            jdbc.sql("INSERT INTO settlement_accounts (id) VALUES (:id) ON CONFLICT DO NOTHING").param("id", account).update()
            val reference = "funding:${participant.bankCode}"
            post(reference, "funding", CENTRAL_BANK, account, properties.liquidityCents)
        }
    }

    fun receive(uetr: UUID, debtorBank: String, creditorBank: String?, amountCents: Long): PaymentResult =
        handle(ReceivePayment(uetr, debtorBank, creditorBank, amountCents))

    /** Reserves the amount on the debtor bank's settlement account, if it covers it. */
    fun reserve(uetr: UUID): PaymentResult = inTransaction {
        val payment = load(uetr)
        val available = payment.debtorBank?.let { balanceLocked(bankAccount(it)) } ?: 0
        apply(uetr, ReserveLiquidity(uetr, available), payment)
    }

    fun markForwarded(uetr: UUID) = handle(MarkForwarded(uetr))

    fun recordAnswer(uetr: UUID, accepted: Boolean, reasonCode: String?) = handle(RecordCreditorAnswer(uetr, accepted, reasonCode))

    fun expire(uetr: UUID) = handle(ExpirePayment(uetr))

    fun settle(uetr: UUID) = handle(SettlePayment(uetr))

    fun find(uetr: UUID): PaymentRecord? =
        jdbc.sql("SELECT * FROM payments WHERE uetr = :uetr").param("uetr", uetr).query { rs, _ -> toRecord(rs) }.optional().orElse(null)

    fun list(limit: Int): List<PaymentRecord> =
        jdbc.sql("SELECT * FROM payments ORDER BY created_at DESC LIMIT :limit").param("limit", limit).query { rs, _ ->
            toRecord(rs)
        }.list()

    fun balances(): List<SettlementBalance> =
        jdbc.sql(
            """
            SELECT a.id, COALESCE(SUM(p.amount_cents), 0) AS balance
            FROM settlement_accounts a LEFT JOIN settlement_postings p ON p.account_id = a.id
            GROUP BY a.id ORDER BY a.id
            """,
        ).query { rs, _ -> SettlementBalance(rs.getString("id"), rs.getLong("balance")) }.list()

    private fun handle(command: PaymentCommand): PaymentResult = inTransaction { apply(command.uetr, command, load(command.uetr)) }

    private fun apply(uetr: UUID, command: PaymentCommand, payment: ClearingPayment): PaymentResult {
        val events = runBlocking { paymentDecider.decide(command, payment).toList() }
        events.filterIsInstance<PaymentCommandRejected>().firstOrNull()?.let { throw IllegalPaymentState(uetr, it.reason) }
        var state = payment
        events.forEach { event ->
            state = paymentDecider.evolve(state, event)
            persist(event, state)
        }
        return PaymentResult(checkNotNull(state.status), state.reasonCode)
    }

    private fun persist(event: PaymentEvent, state: ClearingPayment) {
        if (event is PaymentReceived) {
            jdbc.sql(
                """
                INSERT INTO payments (uetr, status, debtor_bank, creditor_bank, amount_cents)
                VALUES (:uetr, 'RECEIVED', :debtor, :creditor, :amount)
                """,
            )
                .param("uetr", event.uetr).param("debtor", event.debtorBank).param("creditor", event.creditorBank)
                .param("amount", event.amountCents).update()
        } else {
            jdbc.sql("UPDATE payments SET status = :status, reason_code = :reason, updated_at = now() WHERE uetr = :uetr")
                .param("status", checkNotNull(state.status).name).param("reason", state.reasonCode).param("uetr", event.uetr).update()
        }
        if (event is PaymentStatusChanged) {
            jdbc.sql("INSERT INTO payment_events (uetr, type, status, reason_code) VALUES (:uetr, :type, :status, :reason)")
                .param("uetr", event.uetr).param("type", event::class.simpleName).param("status", event.status.name)
                .param("reason", event.reasonCode).update()
        }
        val reference = event.uetr.toString()
        val debtor = state.debtorBank?.let(::bankAccount)
        when (event) {
            is LiquidityReserved -> post(reference, "reserve", checkNotNull(debtor), RESERVED, state.amountCents)
            is PaymentSettled -> post(reference, "settle", RESERVED, bankAccount(checkNotNull(state.creditorBank)), state.amountCents)
            is PaymentRejected -> if (event.releasesReservation) {
                post(
                    reference,
                    "release",
                    RESERVED,
                    checkNotNull(debtor),
                    state.amountCents,
                )
            }
            else -> Unit
        }
    }

    /** One posting pair: from → to. Never twice (unique per reference, account and leg). */
    private fun post(reference: String, leg: String, from: String, to: String, amountCents: Long) {
        listOf(from to -amountCents, to to amountCents).forEach { (account, amount) ->
            jdbc.sql(
                """
                INSERT INTO settlement_postings (reference, account_id, leg, amount_cents) VALUES (:reference, :account, :leg, :amount)
                ON CONFLICT (reference, account_id, leg) DO NOTHING
                """,
            ).param("reference", reference).param("account", account).param("leg", leg).param("amount", amount).update()
        }
    }

    /** Locks the payment row; an unknown payment is the decider's initial state. */
    private fun load(uetr: UUID): ClearingPayment =
        jdbc.sql("SELECT * FROM payments WHERE uetr = :uetr FOR UPDATE").param("uetr", uetr)
            .query { rs, _ ->
                ClearingPayment(
                    status = PaymentStatus.valueOf(rs.getString("status")),
                    debtorBank = rs.getString("debtor_bank"),
                    creditorBank = rs.getString("creditor_bank"),
                    amountCents = rs.getLong("amount_cents"),
                    reasonCode = rs.getString("reason_code"),
                )
            }
            .optional().orElse(paymentDecider.initialState)

    /** Locks the account, so two reservations can't both spend the same liquidity. */
    private fun balanceLocked(account: String): Long {
        jdbc.sql("SELECT id FROM settlement_accounts WHERE id = :id FOR UPDATE").param("id", account).query().listOfRows()
        return jdbc.sql("SELECT COALESCE(SUM(amount_cents), 0) FROM settlement_postings WHERE account_id = :id")
            .param("id", account).query(Long::class.java).single()
    }

    private fun toRecord(rs: ResultSet) = PaymentRecord(
        uetr = rs.getObject("uetr", UUID::class.java),
        status = PaymentStatus.valueOf(rs.getString("status")),
        reasonCode = rs.getString("reason_code"),
        debtorBank = rs.getString("debtor_bank"),
        creditorBank = rs.getString("creditor_bank"),
        amountCents = rs.getLong("amount_cents"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private fun <T> inTransaction(block: () -> T): T = tx.execute { block() }

    companion object {
        const val CENTRAL_BANK = "central-bank"
        const val RESERVED = "reserved"

        fun bankAccount(bankCode: String) = "bank:$bankCode"
    }
}
