package com.example.clearing.api

import com.example.clearing.activities.Chaos
import com.example.clearing.activities.ChaosSettings
import com.example.clearing.config.ClearingProperties
import com.example.clearing.domain.PaymentStatus
import com.example.clearing.ledger.ClearingLedger
import com.example.clearing.ledger.PaymentRecord
import com.example.clearing.ledger.SettlementBalance
import com.example.clearing.workflow.CLEARING_TASK_QUEUE
import com.example.clearing.workflow.ClearingRequest
import com.example.clearing.workflow.PaymentWorkflow
import com.example.clearing.workflow.paymentWorkflowId
import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
import com.example.payments.messages.Pacs028
import com.example.payments.messages.TxSts
import com.example.payments.messages.germanBankCode
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowUpdateException
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The clearing house's interface for the participants (the pacs messages) and for operations. Banks address it with
 * the message's UETR; a second pacs.008 with the same UETR is refused (409).
 */
@RestController
@RequestMapping("/api/clearing")
class ClearingController(
    private val client: WorkflowClient,
    private val ledger: ClearingLedger,
    private val properties: ClearingProperties,
    private val chaos: Chaos,
) {

    /** From the debtor bank: a payment to clear. */
    @PostMapping("/pacs008")
    fun pacs008(@RequestBody pacs008: Pacs008): ResponseEntity<Void> {
        val debtorBank = requireNotNull(properties.participant(germanBankCode(pacs008.debtorIban))) {
            "The debtor's bank is not a participant"
        }.bankCode
        val creditorBank = properties.participant(germanBankCode(pacs008.creditorIban))?.bankCode // null: rejected with RC01
        ledger.receive(pacs008.uetr, debtorBank, creditorBank, pacs008.amountCents)
        val workflow = client.newWorkflowStub(
            PaymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CLEARING_TASK_QUEUE)
                .setWorkflowId(paymentWorkflowId(pacs008.uetr))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build(),
        )
        WorkflowClient.start(workflow::clear, ClearingRequest(pacs008, debtorBank, creditorBank, properties.deadline.toMillis()))
        return ResponseEntity.accepted().build()
    }

    /** From the creditor bank: its answer to a forwarded payment. */
    @PostMapping("/pacs002")
    fun pacs002(@RequestBody pacs002: Pacs002): ResponseEntity<Void> {
        client.newWorkflowStub(PaymentWorkflow::class.java, paymentWorkflowId(pacs002.uetr)).creditorAnswer(pacs002)
        return ResponseEntity.accepted().build()
    }

    /** A status inquiry: answered from the clearing house's books, also long after the payment. */
    @PostMapping("/pacs028")
    fun pacs028(@RequestBody pacs028: Pacs028): ResponseEntity<Pacs002> {
        val payment = ledger.find(pacs028.uetr) ?: return ResponseEntity.notFound().build()
        val txSts = when (payment.status) {
            PaymentStatus.SETTLED -> TxSts.ACSC
            PaymentStatus.REJECTED -> TxSts.RJCT
            else -> TxSts.PDNG
        }
        return ResponseEntity.ok(Pacs002(UUID.randomUUID(), payment.uetr, txSts, payment.reasonCode))
    }

    @GetMapping("/payments")
    fun payments(@RequestParam(defaultValue = "50") limit: Int): List<PaymentRecord> = ledger.list(limit.coerceIn(1, MAX_LIMIT))

    @GetMapping("/settlement-accounts")
    fun settlementAccounts(): List<SettlementBalance> = ledger.balances()

    @GetMapping("/chaos")
    fun chaos() = chaos.settings

    @PutMapping("/chaos")
    fun chaos(@RequestBody settings: ChaosSettings): ChaosSettings = settings.also { chaos.settings = it }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(WorkflowExecutionAlreadyStarted::class)
    fun duplicate() = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A payment with this UETR was already received")

    /** An answer nobody waits for any more: too late, unknown, or a second one. */
    @ExceptionHandler(WorkflowUpdateException::class, WorkflowNotFoundException::class)
    fun notExpected() = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "No answer expected for this payment")

    companion object {
        private const val MAX_LIMIT = 200
    }
}

@Configuration(proxyBeanMethods = false)
class ClearingStartup {
    /** The participants' settlement accounts exist and are funded before the first payment. */
    @Bean
    fun fundParticipants(ledger: ClearingLedger) = ApplicationRunner { ledger.fundParticipants() }
}
