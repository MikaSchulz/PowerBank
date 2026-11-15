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

/** External transfer: the customer is debited, the amount is reserved on the clearing account (same transaction). */
data class ReserveAmount(override val transferId: TransferId) : TransferCommand

/** External transfer: the payment is with the clearing house (or, before SEPA Instant, the simulated external bank). */
data class SendToExternalBank(override val transferId: TransferId) : TransferCommand

/** The clearing house confirmed the settlement (pacs.002 ACSC): the reserved amount goes to the settlement account. */
data class SettleTransfer(override val transferId: TransferId) : TransferCommand

/** The simulated external bank accepted (transfers started before SEPA Instant). */
data class CompleteTransfer(override val transferId: TransferId) : TransferCommand

/** Rejected after the reservation, or never received by the clearing house: the debit is booked back. */
data class RefundTransfer(override val transferId: TransferId, val reason: String) : TransferCommand

/** No final status from the clearing house: the amount stays reserved until there is one. */
data class StartInvestigation(override val transferId: TransferId, val reason: String) : TransferCommand

/** Rejected before anything was booked, e.g. insufficient funds. */
data class RejectTransfer(override val transferId: TransferId, val reason: String) : TransferCommand
