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
        fun `a waiting transfer cannot be sent to the external bank`() =
            transferDecider.given(external, waiting)
                .whenCommand(SendToExternalBank(t))
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
                .whenCommand(SendToExternalBank(t))
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
    inner class ExternalBank {
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
        fun `an accepted transfer can no longer be refunded`() =
            transferDecider.given(external, sent, TransferCompleted(t))
                .whenCommand(RefundTransfer(t, "too late"))
                .thenEvents(TransferCommandRejected(t, "Transfer in status COMPLETED cannot be refunded"))

        @Test
        fun `refunding twice changes nothing`() =
            transferDecider.given(external, sent, TransferRefunded(t, "rejected"))
                .whenCommand(RefundTransfer(t, "rejected"))
                .thenNothing()

        @Test
        fun `nothing is refunded that was never sent`() =
            transferDecider.given(external)
                .whenCommand(RefundTransfer(t, "rejected"))
                .thenEvents(TransferCommandRejected(t, "Transfer in status RECEIVED cannot be refunded"))
    }

    @Test
    fun `the state follows the events`() {
        val state = listOf(external, waiting, approved, TransferSentToExternalBank(t), TransferRefunded(t, "Creditor account closed"))
            .fold(transferDecider.initialState, transferDecider.evolve)

        assertEquals(Transfer(TransferStatus.REFUNDED, TransferKind.EXTERNAL, alice, null, 50_00, "Creditor account closed"), state)
    }
}
