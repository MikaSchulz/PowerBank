package com.example.bank.domain.transfer

import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId

/** Everything that can happen to a transfer, asked as a question: is it allowed in its current status? */
sealed interface TransferCommand {
    val transferId: TransferId
}

/** The bank accepts a transfer. A second receive of the same transfer changes nothing. */
data class ReceiveTransfer(
    override val transferId: TransferId,
    val kind: TransferKind,
    val debtorAccountId: AccountId,
    val creditorAccountId: AccountId?,
    val amountCents: Long,
) : TransferCommand

/** The risk check wants a person to look at it before anything is booked. */
data class RequireApproval(override val transferId: TransferId, val reason: String) : TransferCommand

data class Approve(override val transferId: TransferId, val comment: String? = null) : TransferCommand

data class DenyApproval(override val transferId: TransferId, val comment: String? = null) : TransferCommand

/** Nobody decided within the deadline. */
data class ExpireApproval(override val transferId: TransferId) : TransferCommand

/** Internal transfer or deposit: debit and credit are booked (in the same transaction as this command). */
data class BookTransfer(override val transferId: TransferId) : TransferCommand

/** External transfer: the customer is debited and the payment goes to the external bank. */
data class SendToExternalBank(override val transferId: TransferId) : TransferCommand

/** The external bank accepted. */
data class CompleteTransfer(override val transferId: TransferId) : TransferCommand

/** The external bank rejected or stayed unreachable: the debit is booked back (saga compensation). */
data class RefundTransfer(override val transferId: TransferId, val reason: String) : TransferCommand

/** Rejected before anything was booked, e.g. insufficient funds. */
data class RejectTransfer(override val transferId: TransferId, val reason: String) : TransferCommand
