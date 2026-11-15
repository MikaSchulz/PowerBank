package com.example.bank.api

import com.example.bank.domain.Iban
import com.example.bank.domain.TransferId
import com.example.bank.domain.transfer.TransferKind
import com.example.bank.domain.transfer.TransferStatus
import com.example.bank.ledger.Ledger
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.SystemAccounts
import com.example.bank.ledger.TransferDetails
import com.example.bank.ledger.TransferRecords
import com.example.bank.observability.BankSpans
import com.example.bank.workflow.BANK_TASK_QUEUE
import com.example.bank.workflow.IncomingPaymentRequest
import com.example.bank.workflow.IncomingPaymentWorkflow
import com.example.bank.workflow.TransferWorkflow
import com.example.bank.workflow.incomingPaymentId
import com.example.bank.workflow.incomingPaymentWorkflowId
import com.example.bank.workflow.transferWorkflowId
import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
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
import java.util.UUID

/**
 * What the clearing house delivers to this bank: payments from other banks (pacs.008), and the final status of
 * payments (pacs.002), handed to their workflows. Only pseudonymous IDs in spans and logs; the IBAN of an incoming
 * payment is only used to find the account.
 */
@Service
class ClearingInboundService(
    private val client: WorkflowClient,
    private val ledger: Ledger,
    private val records: TransferRecords,
    private val queries: LedgerQueries,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A payment from another bank: recorded in the ledger with the creditor's account (none if the IBAN isn't one of
     * this bank's), then its workflow checks it and answers the clearing house. The same payment again is recognized
     * by its UETR and changes nothing.
     */
    fun receive(payment: Pacs008) {
        val id = incomingPaymentId(payment.uetr)
        val account = runCatching { Iban.parse(payment.creditorIban) }.getOrNull()?.let(queries::accountByIban)
        BankSpans.tag(
            BankSpans.UETR to payment.uetr,
            BankSpans.TRANSFER_ID to id,
            BankSpans.TRANSFER_KIND to TransferKind.INCOMING,
            BankSpans.CREDITOR_ACCOUNT_ID to account?.id,
        )
        ledger.receiveTransfer(
            TransferDetails(
                id = id,
                kind = TransferKind.INCOMING,
                debtorAccountId = SystemAccounts.SETTLEMENT.value, // the money comes through the clearing house
                creditorAccountId = account?.id,
                amountCents = payment.amountCents,
                traceId = Span.current().spanContext.takeIf { it.isValid && it.isSampled }?.traceId,
            ),
        )
        val workflow = client.newWorkflowStub(
            IncomingPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(BANK_TASK_QUEUE)
                .setWorkflowId(incomingPaymentWorkflowId(id))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build(),
        )
        try {
            WorkflowClient.start(
                workflow::receive,
                IncomingPaymentRequest(id, payment.uetr, payment.amountCents, payment.acceptanceDateTime),
            )
        } catch (_: WorkflowExecutionAlreadyStarted) {
            log.info("Incoming payment {}: received again, already in progress or done", id)
        }
    }

    /**
     * The clearing house's final status of a payment: of one this bank sent (its UETR is the transfer ID), of one it
     * received, or both, for a payment to itself. A repeated confirmation of a payment that already has this final
     * status is fine; one that contradicts it, or that nobody expects, is refused.
     */
    fun confirm(confirmation: Pacs002) {
        BankSpans.tag(BankSpans.UETR to confirmation.uetr)
        val outgoing = records.find(TransferId(confirmation.uetr))?.takeIf { it.details?.kind != TransferKind.INCOMING }
        val incoming = records.find(TransferId(incomingPaymentId(confirmation.uetr)))
        if (outgoing == null && incoming == null) throw NotFoundException("No payment with this UETR")

        val refused = buildList {
            if (outgoing != null) {
                deliver(outgoing.id, confirmation, TransferStatus.COMPLETED, TransferStatus.REFUNDED) {
                    client.newWorkflowStub(TransferWorkflow::class.java, transferWorkflowId(outgoing.id)).confirm(confirmation)
                }?.let(::add)
            }
            if (incoming != null) {
                deliver(incoming.id, confirmation, TransferStatus.BOOKED, TransferStatus.REJECTED) {
                    client.newWorkflowStub(IncomingPaymentWorkflow::class.java, incomingPaymentWorkflowId(incoming.id))
                        .confirm(confirmation)
                }?.let(::add)
            }
        }
        refused.firstOrNull()?.let { throw it }
    }

    /** Null if the workflow took it, or the payment already has this final status. */
    private fun deliver(
        id: UUID,
        confirmation: Pacs002,
        settled: TransferStatus,
        rejected: TransferStatus,
        update: () -> Unit,
    ): ConflictException? {
        val cause = try {
            update()
            return null
        } catch (e: WorkflowUpdateException) {
            e
        } catch (e: WorkflowNotFoundException) { // completed workflows don't accept updates
            e
        }
        val status = records.find(TransferId(id))?.status
        if (status == (if (confirmation.txSts == TxSts.ACSC) settled else rejected)) return null
        log.warn("Payment {}: confirmation {} not taken, status {}", id, confirmation.txSts, status)
        return ConflictException("No confirmation expected for this payment (status $status)").apply { initCause(cause) }
    }
}
