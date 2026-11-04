package com.example.bank.domain.transfer

import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId

/**
 * What happened to a transfer: its business history. Every event except [TransferCommandRejected] is stored, so the
 * ledger can show when a transfer waited for an approval, who approved it and why it was rejected or refunded.
 */
sealed interface TransferEvent {
    val transferId: TransferId
}

/** An event that moves the transfer to [status]. */
sealed interface TransferStatusChanged : TransferEvent {
    val status: TransferStatus

    /** Why it is waiting, rejected or refunded, or the approver's comment; null otherwise. */
    val reason: String?
}

data class TransferReceived(
    override val transferId: TransferId,
    val kind: TransferKind?,
    val debtorAccountId: AccountId?,
    val creditorAccountId: AccountId?,
    val amountCents: Long,
) : TransferStatusChanged {
    override val status get() = TransferStatus.RECEIVED
    override val reason: String? get() = null
}

data class ApprovalRequired(override val transferId: TransferId, override val reason: String) : TransferStatusChanged {
    override val status get() = TransferStatus.AWAITING_APPROVAL
}

data class TransferApproved(override val transferId: TransferId, val comment: String?) : TransferStatusChanged {
    override val status get() = TransferStatus.APPROVED
    override val reason get() = comment
}

data class TransferBooked(override val transferId: TransferId) : TransferStatusChanged {
    override val status get() = TransferStatus.BOOKED
    override val reason: String? get() = null
}

data class TransferSentToExternalBank(override val transferId: TransferId) : TransferStatusChanged {
    override val status get() = TransferStatus.SENT_TO_EXTERNAL_BANK
    override val reason: String? get() = null
}

data class TransferCompleted(override val transferId: TransferId) : TransferStatusChanged {
    override val status get() = TransferStatus.COMPLETED
    override val reason: String? get() = null
}

data class TransferRefunded(override val transferId: TransferId, override val reason: String) : TransferStatusChanged {
    override val status get() = TransferStatus.REFUNDED
}

data class TransferRejected(override val transferId: TransferId, override val reason: String) : TransferStatusChanged {
    override val status get() = TransferStatus.REJECTED
}

/** The command is not allowed in the transfer's current status. Never stored; only returned to the caller. */
data class TransferCommandRejected(override val transferId: TransferId, val reason: String) : TransferEvent
