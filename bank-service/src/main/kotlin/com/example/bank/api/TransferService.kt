package com.example.bank.api

import com.example.bank.domain.TransferId
import com.example.bank.ledger.SystemAccounts
import com.example.bank.ledger.TransferDetails
import com.example.bank.ledger.TransferKind
import com.example.bank.ledger.TransferRecordStatus
import com.example.bank.ledger.TransferRecords
import com.example.bank.workflow.ApprovalDecision
import com.example.bank.workflow.BANK_TASK_QUEUE
import com.example.bank.workflow.TransferRequest
import com.example.bank.workflow.TransferState
import com.example.bank.workflow.TransferStatus
import com.example.bank.workflow.TransferWorkflow
import com.example.bank.workflow.transferWorkflowId
import io.opentelemetry.api.trace.Span
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** A transfer as shown in listings: details from the ledger, live status from the workflow. */
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

/** Client side: starts transfer workflows, queries them and sends updates. */
@Service
class TransferService(
    private val client: WorkflowClient,
    private val records: TransferRecords,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Registers the transfer and starts the workflow. The transfer ID is the workflow ID: a second start with the
     * same ID throws [WorkflowExecutionAlreadyStarted], even if the first workflow has already finished.
     */
    fun start(request: TransferRequest): String? {
        val traceId = currentTraceId()
        records.register(
            TransferDetails(
                id = request.transferId,
                kind = when {
                    request.creditorIban != null -> TransferKind.EXTERNAL
                    request.debtorAccountId == SystemAccounts.CASH.value -> TransferKind.DEPOSIT
                    else -> TransferKind.INTERNAL
                },
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
            throw e // the registration belongs to the first submission
        } catch (e: RuntimeException) {
            records.discardIfPending(TransferId(request.transferId))
            throw e
        }
        return traceId
    }

    /** Status via query; if the workflow is no longer in Temporal (retention expired), from the ledger. */
    fun status(transferId: UUID): TransferState? = try {
        stub(transferId).status()
    } catch (_: WorkflowNotFoundException) {
        records.find(TransferId(transferId))?.let { record ->
            TransferState(
                transferId = transferId,
                status = record.status.toTransferStatus(),
                amountCents = 0, // only available in the postings or the workflow
                external = record.status in setOf(TransferRecordStatus.DEBITED, TransferRecordStatus.COMPLETED, TransferRecordStatus.REFUNDED),
                reason = record.reason,
            )
        }
    }

    /**
     * Newest transfers first. Finished transfers come straight from the ledger; for transfers still in flight,
     * the workflow is queried, because states like "awaiting approval" only exist there.
     */
    fun list(accountId: UUID?, limit: Int): List<TransferView> =
        records.list(accountId, limit).map { item ->
            val live = if (item.status in IN_FLIGHT) liveStatus(item.id) else null
            TransferView(
                transferId = item.id,
                kind = item.details?.kind,
                debtorAccountId = item.details?.debtorAccountId,
                creditorAccountId = item.details?.creditorAccountId,
                amountCents = item.details?.amountCents,
                status = live?.status ?: item.status.toTransferStatus(),
                reason = live?.reason ?: item.reason,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                traceId = item.details?.traceId,
            )
        }

    /** Synchronous update: returns as soon as the workflow has taken over the decision. */
    fun approve(transferId: UUID, decision: ApprovalDecision): TransferState = try {
        stub(transferId).approve(decision)
    } catch (e: WorkflowNotFoundException) {
        // Completed workflows don't accept updates either.
        val state = status(transferId) ?: throw e
        throw ConflictException("Transfer is not awaiting approval (status ${state.status})")
    }

    private fun liveStatus(transferId: UUID): TransferState? = try {
        stub(transferId).status()
    } catch (e: RuntimeException) {
        log.debug("Could not query transfer {}: {}", transferId, e.message)
        null
    }

    /** The trace this request belongs to, if it is recorded (sampled). Comes from an incoming `traceparent` or a new trace. */
    private fun currentTraceId(): String? =
        Span.current().spanContext.takeIf { it.isValid && it.isSampled }?.traceId

    private fun stub(transferId: UUID): TransferWorkflow =
        client.newWorkflowStub(TransferWorkflow::class.java, transferWorkflowId(transferId))

    private fun TransferRecordStatus.toTransferStatus() = when (this) {
        TransferRecordStatus.PENDING -> TransferStatus.RECEIVED
        TransferRecordStatus.BOOKED -> TransferStatus.BOOKED
        TransferRecordStatus.DEBITED -> TransferStatus.SENT_TO_EXTERNAL_BANK
        TransferRecordStatus.COMPLETED -> TransferStatus.COMPLETED
        TransferRecordStatus.REFUNDED -> TransferStatus.REFUNDED
        TransferRecordStatus.REJECTED -> TransferStatus.REJECTED
    }

    companion object {
        private val IN_FLIGHT = setOf(TransferRecordStatus.PENDING, TransferRecordStatus.DEBITED)
    }
}
