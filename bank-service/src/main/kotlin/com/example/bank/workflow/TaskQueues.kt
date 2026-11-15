package com.example.bank.workflow

import java.util.UUID

/** Task queue for the workflows and activities of the bank-service. */
const val BANK_TASK_QUEUE = "bank"

/** Workflow IDs only contain pseudonymous IDs, never names or IBANs. */
fun transferWorkflowId(transferId: UUID) = "transfer-$transferId"

/**
 * One workflow per incoming payment, by its transfer ID ([incomingPaymentId], derived from the UETR): the same payment
 * forwarded twice starts it only once.
 */
fun incomingPaymentWorkflowId(transferId: UUID) = "incoming-payment-$transferId"

/**
 * The ledger's transfer ID for an incoming payment, derived from its UETR: the same for a repeated message, and never
 * one of the bank's own transfer IDs, which are the UETRs of its outgoing payments (a payment to itself has both).
 */
fun incomingPaymentId(uetr: UUID): UUID = UUID.nameUUIDFromBytes("incoming:$uetr".toByteArray())
