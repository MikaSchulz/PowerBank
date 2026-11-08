package com.example.bank.workflow

import com.example.payments.messages.Pacs002
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

/**
 * A transfer. Exactly one of [creditorAccountId] (internal) and [creditorIban] (external bank) is set.
 * Workflows only get IDs and amounts; the external IBAN is the exception and gets encrypted in stage 9.
 */
data class TransferRequest(
    val transferId: UUID,
    val debtorAccountId: UUID,
    val amountCents: Long,
    val creditorAccountId: UUID? = null,
    val creditorIban: String? = null,
)

enum class TransferStatus(val terminal: Boolean = false) {
    RECEIVED,
    AWAITING_APPROVAL,
    APPROVED,
    RESERVED,
    SENT_TO_EXTERNAL_BANK,
    UNDER_INVESTIGATION,
    BOOKED(terminal = true),
    COMPLETED(terminal = true),
    REFUNDED(terminal = true),
    REJECTED(terminal = true),
}

data class TransferState(
    val transferId: UUID,
    val status: TransferStatus,
    val amountCents: Long,
    val external: Boolean,
    val reason: String? = null,
)

data class ApprovalDecision(val approved: Boolean, val comment: String? = null)

@WorkflowInterface
interface TransferWorkflow {

    /** Runs under the workflow ID `transfer-<transferId>`; a second start with the same ID is rejected. */
    @WorkflowMethod
    fun transfer(request: TransferRequest): TransferState

    @QueryMethod
    fun status(): TransferState

    /** Manual approval on suspected fraud. Returns only once the workflow has taken over the decision. */
    @UpdateMethod
    fun approve(decision: ApprovalDecision): TransferState

    /** Rejects the update before it reaches the history if no approval is currently expected. */
    @UpdateValidatorMethod(updateName = "approve")
    fun validateApprove(decision: ApprovalDecision)

    /** The clearing house's confirmation of an external transfer: a pacs.002 with ACSC (settled) or RJCT. */
    @UpdateMethod
    fun confirm(confirmation: Pacs002)

    /** Rejects a confirmation nobody waits for: none expected, a second one, or one for another payment. */
    @UpdateValidatorMethod(updateName = "confirm")
    fun validateConfirm(confirmation: Pacs002)
}
