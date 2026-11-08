package com.example.bank.domain.transfer

import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId
import com.example.bank.domain.account.given
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class TransferDeciderTest {

    private val t = TransferId(UUID.randomUUID())
    private val alice = AccountId(UUID.randomUUID())
    private val bob = AccountId(UUID.randomUUID())

    private val internal = TransferReceived(t, TransferKind.INTERNAL, alice, bob, 50_00)
    private val external = TransferReceived(t, TransferKind.EXTERNAL, alice, null, 50_00)
    private val waiting = ApprovalRequired(t, "Amount exceeds approval limit")
    private val approved = TransferApproved(t, null)

    @Nested
    inner class Receiving {
        @Test
        fun `a new transfer is received`() =
            transferDecider.given()
                .whenCommand(ReceiveTransfer(t, TransferKind.INTERNAL, alice, bob, 50_00))
                .thenEvents(internal)

        @Test
        fun `receiving it again changes nothing`() =
            transferDecider.given(internal)
                .whenCommand(ReceiveTransfer(t, TransferKind.INTERNAL, alice, bob, 50_00))
                .thenNothing()

        @Test
        fun `the amount must be positive`() =
            transferDecider.given()
                .whenCommand(ReceiveTransfer(t, TransferKind.INTERNAL, alice, bob, 0))
                .thenEvents(TransferCommandRejected(t, "Amount must be positive"))

        @Test
        fun `nothing happens to a transfer that was never received`() =
            transferDecider.given()
                .whenCommand(BookTransfer(t))
                .thenEvents(TransferCommandRejected(t, "Transfer does not exist"))
    }

    @Nested
    inner class Approval {
        @Test
        fun `a received transfer waits for an approval`() =
            transferDecider.given(internal)
                .whenCommand(RequireApproval(t, "Amount exceeds approval limit"))
                .thenEvents(waiting)

        @Test
        fun `a waiting transfer cannot be booked`() =
            transferDecider.given(internal, waiting)
                .whenCommand(BookTransfer(t))
                .thenEvents(TransferCommandRejected(t, "Transfer is awaiting approval"))

        @Test
        fun `nothing is reserved for a waiting transfer`() =
            transferDecider.given(external, waiting)
                .whenCommand(ReserveAmount(t))
                .thenEvents(TransferCommandRejected(t, "Transfer is awaiting approval"))

        @Test
        fun `approved, it can be booked`() {
            transferDecider.given(internal, waiting)
                .whenCommand(Approve(t, "checked with the customer"))
                .thenEvents(TransferApproved(t, "checked with the customer"))
            transferDecider.given(internal, waiting, approved)
                .whenCommand(BookTransfer(t))
                .thenEvents(TransferBooked(t))
        }

        @Test
        fun `denied, it is rejected with the comment`() =
            transferDecider.given(internal, waiting)
                .whenCommand(DenyApproval(t, "phishing"))
                .thenEvents(TransferRejected(t, "Approval denied: phishing"))

        @Test
        fun `without a decision in time, it is rejected`() =
            transferDecider.given(internal, waiting)
                .whenCommand(ExpireApproval(t))
                .thenEvents(TransferRejected(t, "No approval within the deadline"))

        @Test
        fun `only a waiting transfer can be approved`() =
            transferDecider.given(internal, TransferBooked(t))
                .whenCommand(Approve(t))
                .thenEvents(TransferCommandRejected(t, "Transfer is not awaiting approval (status BOOKED)"))

        @Test
        fun `approving twice changes nothing`() =
            transferDecider.given(internal, waiting, approved)
                .whenCommand(Approve(t))
                .thenNothing()
    }

    @Nested
    inner class Booking {
        @Test
        fun `an internal transfer is booked`() =
            transferDecider.given(internal)
                .whenCommand(BookTransfer(t))
                .thenEvents(TransferBooked(t))

        @Test
        fun `booking twice changes nothing`() =
            transferDecider.given(internal, TransferBooked(t))
                .whenCommand(BookTransfer(t))
                .thenNothing()

        @Test
        fun `a transfer to the same account is rejected when it should be booked`() =
            transferDecider.given(TransferReceived(t, TransferKind.INTERNAL, alice, alice, 50_00))
                .whenCommand(BookTransfer(t))
                .thenEvents(TransferRejected(t, "Debtor and creditor account are identical"))

        @Test
        fun `an external transfer is not booked internally`() =
            transferDecider.given(external)
                .whenCommand(BookTransfer(t))
                .thenEvents(TransferCommandRejected(t, "External transfers are sent to the external bank, not booked"))

        @Test
        fun `an internal transfer does not go to an external bank`() =
            transferDecider.given(internal)
                .whenCommand(ReserveAmount(t))
                .thenEvents(TransferCommandRejected(t, "Only external transfers go to an external bank"))

        @Test
        fun `insufficient funds reject it`() =
            transferDecider.given(internal)
                .whenCommand(RejectTransfer(t, "Insufficient funds"))
                .thenEvents(TransferRejected(t, "Insufficient funds"))

        @Test
        fun `a booked transfer cannot be rejected any more`() =
            transferDecider.given(internal, TransferBooked(t))
                .whenCommand(RejectTransfer(t, "too late"))
                .thenEvents(TransferCommandRejected(t, "Transfer in status BOOKED cannot be rejected"))
    }

    @Nested
    inner class InstantPayment {
        private val reserved = AmountReserved(t)
        private val sent = TransferSentToExternalBank(t)
        private val investigating = InvestigationStarted(t, "No final status from the clearing house")

        @Test
        fun `the amount is reserved before the payment is sent`() {
            transferDecider.given(external)
                .whenCommand(SendToExternalBank(t))
                .thenEvents(TransferCommandRejected(t, "Transfer in status RECEIVED cannot be sent to the external bank"))
            transferDecider.given(external)
                .whenCommand(ReserveAmount(t))
                .thenEvents(reserved)
            transferDecider.given(external, reserved)
                .whenCommand(SendToExternalBank(t))
                .thenEvents(sent)
        }

        @Test
        fun `an approved transfer is reserved`() =
            transferDecider.given(external, waiting, approved)
                .whenCommand(ReserveAmount(t))
                .thenEvents(reserved)

        @Test
        fun `reserving again changes nothing, also after the payment went on`() {
            transferDecider.given(external, reserved).whenCommand(ReserveAmount(t)).thenNothing()
            transferDecider.given(external, reserved, sent).whenCommand(ReserveAmount(t)).thenNothing()
            transferDecider.given(external, reserved, sent, TransferSettled(t)).whenCommand(ReserveAmount(t)).thenNothing()
        }

        @Test
        fun `settled, the transfer is completed`() =
            transferDecider.given(external, reserved, sent)
                .whenCommand(SettleTransfer(t))
                .thenEvents(TransferSettled(t))

        @Test
        fun `settled although sending seemed to fail`() =
            transferDecider.given(external, reserved)
                .whenCommand(SettleTransfer(t))
                .thenEvents(TransferSettled(t))

        @Test
        fun `settling twice changes nothing`() =
            transferDecider.given(external, reserved, sent, TransferSettled(t))
                .whenCommand(SettleTransfer(t))
                .thenNothing()

        @Test
        fun `rejected, the reservation is refunded`() =
            transferDecider.given(external, reserved, sent)
                .whenCommand(RefundTransfer(t, "Account closed (AC04)"))
                .thenEvents(TransferRefunded(t, "Account closed (AC04)"))

        @Test
        fun `never received by the clearing house, the reservation is refunded`() =
            transferDecider.given(external, reserved)
                .whenCommand(RefundTransfer(t, "Not received by the clearing house"))
                .thenEvents(TransferRefunded(t, "Not received by the clearing house"))

        @Test
        fun `a settled transfer can no longer be refunded`() =
            transferDecider.given(external, reserved, sent, TransferSettled(t))
                .whenCommand(RefundTransfer(t, "too late"))
                .thenEvents(TransferCommandRejected(t, "Transfer in status COMPLETED cannot be refunded"))

        @Test
        fun `a refunded transfer can no longer be settled`() =
            transferDecider.given(external, reserved, sent, TransferRefunded(t, "rejected"))
                .whenCommand(SettleTransfer(t))
                .thenEvents(TransferCommandRejected(t, "Transfer in status REFUNDED cannot be settled"))

        @Test
        fun `refunding twice changes nothing`() =
            transferDecider.given(external, reserved, sent, TransferRefunded(t, "rejected"))
                .whenCommand(RefundTransfer(t, "rejected"))
                .thenNothing()

        @Test
        fun `nothing is refunded that was never reserved`() =
            transferDecider.given(external)
                .whenCommand(RefundTransfer(t, "rejected"))
                .thenEvents(TransferCommandRejected(t, "Transfer in status RECEIVED cannot be refunded"))

        @Test
        fun `without a final status the transfer goes under investigation`() {
            transferDecider.given(external, reserved, sent)
                .whenCommand(StartInvestigation(t, "No final status from the clearing house"))
                .thenEvents(investigating)
            transferDecider.given(external, reserved)
                .whenCommand(StartInvestigation(t, "No final status from the clearing house"))
                .thenEvents(investigating)
        }

        @Test
        fun `under investigation, the final status still decides`() {
            transferDecider.given(external, reserved, sent, investigating)
                .whenCommand(SettleTransfer(t))
                .thenEvents(TransferSettled(t))
            transferDecider.given(external, reserved, sent, investigating)
                .whenCommand(RefundTransfer(t, "Account closed (AC04)"))
                .thenEvents(TransferRefunded(t, "Account closed (AC04)"))
            transferDecider.given(external, reserved, sent, investigating)
                .whenCommand(StartInvestigation(t, "again"))
                .thenNothing()
        }

        @Test
        fun `a finished transfer is not investigated`() =
            transferDecider.given(external, reserved, sent, TransferSettled(t))
                .whenCommand(StartInvestigation(t, "late"))
                .thenEvents(TransferCommandRejected(t, "Transfer in status COMPLETED cannot be investigated"))
    }

    /** Transfers started before SEPA Instant: the simulated external bank answers, no money moves on completion. */
    @Nested
    inner class SimulatedExternalBank {
        private val sent = TransferSentToExternalBank(t)

        @Test
        fun `the external bank accepts`() =
            transferDecider.given(external, sent)
                .whenCommand(CompleteTransfer(t))
                .thenEvents(TransferCompleted(t))

        @Test
        fun `the external bank rejects and the transfer is refunded`() =
            transferDecider.given(external, sent)
                .whenCommand(RefundTransfer(t, "Creditor account closed"))
                .thenEvents(TransferRefunded(t, "Creditor account closed"))

        @Test
        fun `only a sent transfer is completed`() =
            transferDecider.given(external, AmountReserved(t))
                .whenCommand(CompleteTransfer(t))
                .thenEvents(TransferCommandRejected(t, "Transfer in status RESERVED cannot be completed"))
    }

    @Test
    fun `the state follows the events`() {
        val state = listOf(external, waiting, approved, AmountReserved(t), TransferSentToExternalBank(t), TransferRefunded(t, "Closed"))
            .fold(transferDecider.initialState, transferDecider.evolve)

        assertEquals(Transfer(TransferStatus.REFUNDED, TransferKind.EXTERNAL, alice, null, 50_00, "Closed"), state)
    }
}
