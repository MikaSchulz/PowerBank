package com.example.bank.workflow

import com.example.payments.messages.Pacs002
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Instant
import java.util.UUID

/**
 * A payment from another bank, as the clearing house forwarded it. Only IDs and the amount: the IBANs stay with the
 * inbound endpoint, which resolves the creditor's IBAN to an account and records the payment in the ledger.
 */
data class IncomingPaymentRequest(
    /** The ledger's transfer ID for the payment ([incomingPaymentId]). */
    val transferId: UUID,
    val uetr: UUID,
    val amountCents: Long,
    /** When the debtor bank accepted the payment: the scheme's deadline runs from here. */
    val acceptedAt: Instant,
)

/**
 * The bank as the creditor bank (#60): check the account, answer the clearing house within the deadline, and credit
 * the customer only once the clearing house confirms the settlement. Started by the incoming message, so nothing waits
 * for a payment that may never come (decision 0007).
 */
@WorkflowInterface
interface IncomingPaymentWorkflow {

    /** Runs under the workflow ID `incoming-payment-<transferId>`. */
    @WorkflowMethod
    fun receive(request: IncomingPaymentRequest): TransferState

    @QueryMethod
    fun status(): TransferState

    /** The clearing house's confirmation: a pacs.002 with ACSC (settled: credit the customer) or RJCT. */
    @UpdateMethod
    fun confirm(confirmation: Pacs002)

    @UpdateValidatorMethod(updateName = "confirm")
    fun validateConfirm(confirmation: Pacs002)
}
