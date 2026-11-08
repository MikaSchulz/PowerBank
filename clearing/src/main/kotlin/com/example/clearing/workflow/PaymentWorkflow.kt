package com.example.clearing.workflow

import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

const val CLEARING_TASK_QUEUE = "clearing"

/** Workflow IDs only contain the payment's UETR, never IBANs. */
fun paymentWorkflowId(uetr: java.util.UUID) = "payment-$uetr"

/**
 * What the clearing house received, with the routing it worked out (bank codes; [creditorBank] null if it's not a
 * participant) and the deadline for the creditor bank's answer (`clearing.deadline`, part of the input so a replay
 * uses the same value).
 */
data class ClearingRequest(val pacs008: Pacs008, val debtorBank: String, val creditorBank: String?, val deadlineMillis: Long)

/** The workflow's contract: mirrors the domain's PaymentStatus by name (ClearingStatusContractTest). */
enum class ClearingStatus { RECEIVED, RESERVED, FORWARDED, ACCEPTED, SETTLED, REJECTED }

data class PaymentState(val uetr: java.util.UUID, val status: ClearingStatus, val reasonCode: String? = null)

/** One payment through the clearing house, workflow ID `payment-<uetr>`: reserve, forward, wait, settle, confirm. */
@WorkflowInterface
interface PaymentWorkflow {

    @WorkflowMethod
    fun clear(request: ClearingRequest): PaymentState

    /** The creditor bank's pacs.002: accepted or rejected. */
    @UpdateMethod
    fun creditorAnswer(answer: Pacs002)

    @UpdateValidatorMethod(updateName = "creditorAnswer")
    fun validateCreditorAnswer(answer: Pacs002)

    @QueryMethod
    fun status(): PaymentState
}
