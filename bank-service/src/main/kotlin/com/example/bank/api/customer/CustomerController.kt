package com.example.bank.api.customer

import com.example.bank.api.NotFoundException
import com.example.bank.api.TransferAccepted
import com.example.bank.api.TransferService
import com.example.bank.api.TransferView
import com.example.bank.domain.AccountId
import com.example.bank.domain.Iban
import com.example.bank.domain.account.AccountKind
import com.example.bank.ledger.AccountView
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.PostingView
import com.example.bank.ledger.TransferHistoryEntry
import com.example.bank.observability.BankSpans
import com.example.bank.workflow.TransferState
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Money to an IBAN, as a customer enters it. [transferId] protects against submitting twice; generated if missing. */
data class SendMoneyBody(val creditorIban: String, val amountCents: Long, val transferId: UUID? = null)

/**
 * Online banking (#61): what a customer sees of their account and does with it. Everything is scoped to the account
 * in the path. Until login (#36) and account opening (#13), the UI acts as the customer of one account; with login,
 * the account must belong to the logged-in customer, and that check fits in here without moving any endpoint.
 */
@RestController
@RequestMapping("/api/customer/accounts/{accountId}")
class CustomerController(
    private val transfers: TransferService,
    private val queries: LedgerQueries,
) {

    @GetMapping
    fun account(@PathVariable accountId: UUID): AccountView = customerAccount(accountId)

    /** The account's bookings: the balance is their sum. */
    @GetMapping("/postings")
    fun postings(@PathVariable accountId: UUID): List<PostingView> =
        queries.postingsOfAccount(AccountId(customerAccount(accountId).id))

    /** Money in and out of the account, newest first. */
    @GetMapping("/transfers")
    fun transfers(@PathVariable accountId: UUID, @RequestParam(defaultValue = "50") limit: Int): List<TransferView> =
        transfers.list(customerAccount(accountId).id, limit.coerceIn(1, MAX_LIMIT))

    /** To an IBAN of this bank: booked internally. To another bank: SEPA Instant through the clearing house. */
    @PostMapping("/transfers")
    fun send(@PathVariable accountId: UUID, @RequestBody body: SendMoneyBody): ResponseEntity<TransferAccepted> {
        require(body.amountCents > 0) { "Amount must be positive" }
        customerAccount(accountId)
        val transferId = body.transferId ?: UUID.randomUUID()
        val traceId = transfers.send(accountId, Iban.parse(body.creditorIban), body.amountCents, transferId)
        return TransferAccepted.response(transferId, "/api/customer/accounts/$accountId/transfers/$transferId", traceId)
    }

    @GetMapping("/transfers/{transferId}")
    fun transfer(@PathVariable accountId: UUID, @PathVariable transferId: UUID): TransferState {
        ownTransfer(accountId, transferId)
        return transfers.status(transferId) ?: throw NotFoundException("No such transfer")
    }

    /** Every status the transfer went through, with times and reasons. */
    @GetMapping("/transfers/{transferId}/history")
    fun history(@PathVariable accountId: UUID, @PathVariable transferId: UUID): List<TransferHistoryEntry> {
        ownTransfer(accountId, transferId)
        return transfers.history(transferId)
    }

    private fun customerAccount(accountId: UUID): AccountView {
        BankSpans.tag(BankSpans.ACCOUNT_ID to accountId)
        return queries.account(AccountId(accountId))?.takeIf { it.kind == AccountKind.CUSTOMER.name }
            ?: throw NotFoundException("No such account")
    }

    /** A transfer of another account is as unknown to a customer as one that doesn't exist. */
    private fun ownTransfer(accountId: UUID, transferId: UUID) {
        customerAccount(accountId)
        if (!transfers.involves(transferId, accountId)) throw NotFoundException("No such transfer")
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
