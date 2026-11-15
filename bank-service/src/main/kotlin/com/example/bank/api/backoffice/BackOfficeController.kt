package com.example.bank.api.backoffice

import com.example.bank.api.ConflictException
import com.example.bank.api.NotFoundException
import com.example.bank.api.TransferAccepted
import com.example.bank.api.TransferService
import com.example.bank.api.TransferView
import com.example.bank.customer.CustomerRecords
import com.example.bank.domain.AccountId
import com.example.bank.domain.TransferId
import com.example.bank.domain.account.AccountKind
import com.example.bank.domain.transfer.TransferStatus
import com.example.bank.ledger.AccountView
import com.example.bank.ledger.BookingResult
import com.example.bank.ledger.Ledger
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.PostingView
import com.example.bank.ledger.SystemAccounts
import com.example.bank.ledger.TransferHistoryEntry
import com.example.bank.observability.BankSpans
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

data class OpenAccountBody(val overdraftLimitCents: Long = 0)

data class DepositBody(val amountCents: Long, val transferId: UUID? = null)

/**
 * The back office (#61): what bank employees work with. Customer accounts (open, cash deposits at the counter, block),
 * every transfer, and the work queues: approvals of suspicious transfers, payments under investigation.
 */
@RestController
@RequestMapping("/api/backoffice")
class BackOfficeController(
    private val ledger: Ledger,
    private val queries: LedgerQueries,
    private val customers: CustomerRecords,
    private val transfers: TransferService,
) {

    /** Customer accounts with their balances; the bank's own accounts are for operations. */
    @GetMapping("/accounts")
    fun accounts(): List<AccountView> = queries.accounts().filter { it.kind == AccountKind.CUSTOMER.name }

    /** Shortcut until stage 7 (#13): the account is opened right away, for a placeholder customer. */
    @PostMapping("/accounts")
    fun open(@RequestBody(required = false) body: OpenAccountBody?): ResponseEntity<AccountView> {
        val id = AccountId(UUID.randomUUID())
        val result = ledger.openAccount(id, customers.createPlaceholder(), body?.overdraftLimitCents ?: 0)
        require(result.booked) { result.reason ?: "Account could not be opened" }
        BankSpans.tag(BankSpans.ACCOUNT_ID to id)
        return ResponseEntity.status(HttpStatus.CREATED).body(queries.account(id))
    }

    @GetMapping("/accounts/{accountId}")
    fun account(@PathVariable accountId: UUID): AccountView = customerAccount(accountId)

    @GetMapping("/accounts/{accountId}/postings")
    fun postings(@PathVariable accountId: UUID): List<PostingView> =
        queries.postingsOfAccount(AccountId(customerAccount(accountId).id))

    /** Cash at the counter: an internal transfer from the cash account, so the same workflow as every transfer. */
    @PostMapping("/accounts/{accountId}/deposits")
    fun deposit(@PathVariable accountId: UUID, @RequestBody body: DepositBody): ResponseEntity<TransferAccepted> {
        require(body.amountCents > 0) { "Amount must be positive" }
        val transferId = body.transferId ?: UUID.randomUUID()
        val traceId = transfers.start(
            TransferRequest(transferId, SystemAccounts.CASH.value, body.amountCents, creditorAccountId = accountId),
        )
        return TransferAccepted.response(transferId, "/api/backoffice/transfers/$transferId", traceId)
    }

    @PostMapping("/accounts/{accountId}/block")
    fun block(@PathVariable accountId: UUID): AccountView = afterCommand(accountId) { ledger.blockAccount(it) }

    @PostMapping("/accounts/{accountId}/unblock")
    fun unblock(@PathVariable accountId: UUID): AccountView = afterCommand(accountId) { ledger.unblockAccount(it) }

    /** Every transfer, newest first; by account, or by status for the work queues (AWAITING_APPROVAL, UNDER_INVESTIGATION). */
    @GetMapping("/transfers")
    fun transfers(
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) status: TransferStatus?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<TransferView> = transfers.list(accountId, limit.coerceIn(1, MAX_LIMIT), status)

    @GetMapping("/transfers/{transferId}")
    fun transfer(@PathVariable transferId: UUID): TransferState =
        transfers.status(transferId) ?: throw NotFoundException("No such transfer")

    /** The business history from the ledger: received, waiting for approval, approved, booked, … with times and reasons. */
    @GetMapping("/transfers/{transferId}/history")
    fun history(@PathVariable transferId: UUID): List<TransferHistoryEntry> = transfers.history(transferId)

    /** The bookings of a transfer, on every account it touched: they always sum up to zero. */
    @GetMapping("/transfers/{transferId}/postings")
    fun transferPostings(@PathVariable transferId: UUID): List<PostingView> = queries.postingsOfTransfer(TransferId(transferId))

    /** Approves or denies a suspicious transfer: the workflow's `approve` update. */
    @PostMapping("/transfers/{transferId}/approval")
    fun approve(@PathVariable transferId: UUID, @RequestBody decision: ApprovalDecision): TransferState =
        transfers.approve(transferId, decision)

    private fun customerAccount(accountId: UUID): AccountView {
        BankSpans.tag(BankSpans.ACCOUNT_ID to accountId)
        return queries.account(AccountId(accountId))?.takeIf { it.kind == AccountKind.CUSTOMER.name }
            ?: throw NotFoundException("No such customer account")
    }

    private fun afterCommand(accountId: UUID, command: (AccountId) -> BookingResult): AccountView {
        val id = AccountId(customerAccount(accountId).id)
        val result = command(id)
        if (!result.booked) throw ConflictException(result.reason ?: "rejected")
        return customerAccount(accountId)
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
