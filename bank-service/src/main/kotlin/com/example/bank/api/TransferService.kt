package com.example.bank.api

import com.example.bank.domain.TransferId
import com.example.bank.domain.transfer.TransferKind
import com.example.bank.domain.transfer.TransferStatus
import com.example.bank.ledger.Ledger
import com.example.bank.ledger.SystemAccounts
import com.example.bank.ledger.TransferDetails
import com.example.bank.ledger.TransferHistoryEntry
import com.example.bank.ledger.TransferRecord
import com.example.bank.ledger.TransferRecords
import com.example.bank.observability.BankSpans
import com.example.bank.workflow.ApprovalDecision
import com.example.bank.workflow.BANK_TASK_QUEUE
import com.example.bank.workflow.TransferRequest
import com.example.bank.workflow.TransferState
import com.example.bank.workflow.TransferWorkflow
import com.example.bank.workflow.transferWorkflowId
import com.example.payments.messages.Pacs002
import com.example.payments.messages.TxSts
import io.opentelemetry.api.trace.Span
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowUpdateException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import com.example.bank.workflow.TransferStatus as WorkflowTransferStatus

/** A transfer as shown in listings: details and business status from the ledger. */
data class TransferView(
    val transferId: UUID,
    val kind: TransferKind?,
    val debtorAccountId: UUID?,
    val creditorAccountId: UUID?,
    val amountCents: Long?,
    val status: TransferStatus,
    val reason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** W3C trace ID of the request that accepted the transfer; links to the trace in Tempo. */
    val traceId: String?,
)

/**
 * Client side: receives transfers in the ledger, starts their workflows and sends approvals as updates.
 * Status, listings and history come from the ledger, the source of truth; the workflow isn't asked.
 */
@Service
class TransferService(
    private val client: WorkflowClient,
    private val ledger: Ledger,
    private val records: TransferRecords,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Receives the transfer in the ledger and starts the workflow. The transfer ID is the workflow ID: a second start
     * with the same ID throws [WorkflowExecutionAlreadyStarted], even if the first workflow has already finished.
     */
    fun start(request: TransferRequest): String? {
        val traceId = currentTraceId()
        val kind = when {
            request.creditorIban != null -> TransferKind.EXTERNAL
            request.debtorAccountId == SystemAccounts.CASH.value -> TransferKind.DEPOSIT
            else -> TransferKind.INTERNAL
        }
        BankSpans.tag(
            BankSpans.TRANSFER_ID to request.transferId,
            BankSpans.TRANSFER_KIND to kind,
            BankSpans.DEBTOR_ACCOUNT_ID to request.debtorAccountId,
            BankSpans.CREDITOR_ACCOUNT_ID to request.creditorAccountId,
        )
        ledger.receiveTransfer(
            TransferDetails(
                id = request.transferId,
                kind = kind,
                debtorAccountId = request.debtorAccountId,
                creditorAccountId = request.creditorAccountId,
                amountCents = request.amountCents,
                traceId = traceId,
            ),
        )
        val workflow = client.newWorkflowStub(
            TransferWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(BANK_TASK_QUEUE)
                .setWorkflowId(transferWorkflowId(request.transferId))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build(),
        )
        try {
            // Temporal's tracing interceptor puts the current trace context (W3C traceparent) into the workflow
            // header, so the workflow and all its activities continue this trace.
            WorkflowClient.start(workflow::transfer, request)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            throw e // the ledger entry belongs to the first submission
        } catch (e: RuntimeException) { // whatever failed: clean up, then rethrow
            records.discardIfReceived(TransferId(request.transferId))
            throw e
        }
        return traceId
    }

    /** The business status from the ledger: also while the workflow waits, and long after Temporal forgot it. */
    fun status(transferId: UUID): TransferState? = records.find(TransferId(transferId))?.toState()

    /** Newest transfers first. */
    fun list(accountId: UUID?, limit: Int): List<TransferView> =
        records.list(accountId, limit).map { record ->
            TransferView(
                transferId = record.id,
                kind = record.details?.kind,
                debtorAccountId = record.details?.debtorAccountId,
                creditorAccountId = record.details?.creditorAccountId,
                amountCents = record.details?.amountCents,
                status = record.status,
                reason = record.reason,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
                traceId = record.details?.traceId,
            )
        }

    /** The business history: every status the transfer went through, oldest first. */
    fun history(transferId: UUID): List<TransferHistoryEntry> = records.history(TransferId(transferId))

    /** Synchronous update: returns as soon as the workflow has taken over the decision. */
    fun approve(transferId: UUID, decision: ApprovalDecision): TransferState = try {
        BankSpans.tag(BankSpans.TRANSFER_ID to transferId)
        stub(transferId).approve(decision)
    } catch (e: WorkflowNotFoundException) {
        // Completed workflows don't accept updates either.
        val state = status(transferId) ?: throw e
        throw ConflictException("Transfer is not awaiting approval (status ${state.status})")
    }

    /**
     * The clearing house's final status of a payment this bank sent, handed to the transfer's workflow as an update.
     * The UETR is the transfer ID. A repeated confirmation of a transfer that already has this final status is fine;
     * one that contradicts it, or that nobody expects, is refused.
     */
    fun confirm(confirmation: Pacs002) {
        BankSpans.tag(BankSpans.TRANSFER_ID to confirmation.uetr)
        val id = TransferId(confirmation.uetr)
        records.find(id) ?: throw NotFoundException("No payment with this UETR")
        try {
            stub(confirmation.uetr).confirm(confirmation)
        } catch (e: WorkflowUpdateException) {
            alreadyConfirmed(id, confirmation, e)
        } catch (e: WorkflowNotFoundException) { // completed workflows don't accept updates
            alreadyConfirmed(id, confirmation, e)
        }
    }

    private fun alreadyConfirmed(id: TransferId, confirmation: Pacs002, cause: RuntimeException) {
        val status = records.find(id)?.status
        val expected = if (confirmation.txSts == TxSts.ACSC) TransferStatus.COMPLETED else TransferStatus.REFUNDED
        if (status == expected) return
        log.warn("Transfer {}: confirmation {} not taken, transfer is {}", id, confirmation.txSts, status)
        throw ConflictException("No confirmation expected for this payment (status $status)").apply { initCause(cause) }
    }

    /** The trace this request belongs to, if it is recorded (sampled). Comes from an incoming `traceparent` or a new trace. */
    private fun currentTraceId(): String? =
        Span.current().spanContext.takeIf { it.isValid && it.isSampled }?.traceId

    private fun stub(transferId: UUID): TransferWorkflow =
        client.newWorkflowStub(TransferWorkflow::class.java, transferWorkflowId(transferId))

    private fun TransferRecord.toState() = TransferState(
        transferId = id,
        status = WorkflowTransferStatus.valueOf(status.name),
        amountCents = details?.amountCents ?: 0,
        external = details?.kind == TransferKind.EXTERNAL,
        reason = reason,
    )
}
