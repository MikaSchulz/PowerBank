package com.example.bank.api

import com.example.bank.domain.TransferId
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.PostingView
import com.example.bank.workflow.ApprovalDecision
import com.example.bank.workflow.TransferRequest
import com.example.bank.workflow.TransferState
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class TransferBody(
    /** Client-assigned ID; protects against duplicate submission. Generated if missing. */
    val transferId: UUID? = null,
    val debtorAccountId: UUID,
    val amountCents: Long,
    val creditorAccountId: UUID? = null,
    val creditorIban: String? = null,
)

/** [traceId] is the W3C trace ID of this request (null if not sampled): the whole transfer is one trace in Tempo. */
data class TransferAccepted(val transferId: UUID, val statusUrl: String, val traceId: String?)

@RestController
@RequestMapping("/api/transfers")
class TransferController(
    private val transfers: TransferService,
    private val queries: LedgerQueries,
) {

    @PostMapping
    fun create(@RequestBody body: TransferBody): ResponseEntity<TransferAccepted> {
        require(body.amountCents > 0) { "Amount must be positive" }
        require((body.creditorAccountId == null) != (body.creditorIban == null)) {
            "Specify exactly one of creditorAccountId (internal) or creditorIban (external bank)"
        }
        val request = TransferRequest(
            transferId = body.transferId ?: UUID.randomUUID(),
            debtorAccountId = body.debtorAccountId,
            amountCents = body.amountCents,
            creditorAccountId = body.creditorAccountId,
            creditorIban = body.creditorIban?.let(Iban::normalize),
        )
        val traceId = transfers.start(request)
        return accepted(request.transferId, traceId)
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<TransferView> = transfers.list(accountId, limit.coerceIn(1, MAX_LIMIT))

    @GetMapping("/{transferId}/postings")
    fun postings(@PathVariable transferId: UUID): List<PostingView> = queries.postingsOfTransfer(TransferId(transferId))

    @GetMapping("/{transferId}")
    fun status(@PathVariable transferId: UUID): ResponseEntity<TransferState> =
        transfers.status(transferId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PostMapping("/{transferId}/approval")
    fun approve(@PathVariable transferId: UUID, @RequestBody decision: ApprovalDecision): TransferState =
        transfers.approve(transferId, decision)

    companion object {
        private const val MAX_LIMIT = 200

        fun accepted(transferId: UUID, traceId: String?): ResponseEntity<TransferAccepted> {
            val url = "/api/transfers/$transferId"
            return ResponseEntity.status(HttpStatus.ACCEPTED).header("Location", url).body(TransferAccepted(transferId, url, traceId))
        }
    }
}
