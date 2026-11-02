package com.example.bank.workflow

/** Task queue for the workflows and activities of the bank-service. */
const val BANK_TASK_QUEUE = "bank"

/** Workflow IDs only contain pseudonymous IDs, never names or IBANs. */
fun transferWorkflowId(transferId: java.util.UUID) = "transfer-$transferId"
