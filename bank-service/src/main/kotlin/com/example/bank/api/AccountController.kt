package com.example.bank.api

import com.example.bank.customer.CustomerRecords
import com.example.bank.domain.AccountId
import com.example.bank.ledger.AccountView
import com.example.bank.ledger.Ledger
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.PostingView
import com.example.bank.ledger.SystemAccounts
import com.example.bank.observability.BankSpans
import com.example.bank.workflow.TransferRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class OpenAccountBody(val overdraftLimitCents: Long = 0)

data class DepositBody(val amountCents: Long, val transferId: UUID? = null)

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val ledger: Ledger,
    private val queries: LedgerQueries,
    private val customers: CustomerRecords,
    private val transfers: TransferService,
) {

    /** Shortcut until stage 7: open the account right away with a placeholder customer. Later via the AccountOpeningWorkflow. */
    @PostMapping
    fun open(@RequestBody(required = false) body: OpenAccountBody?): ResponseEntity<AccountView> {
        val id = AccountId(UUID.randomUUID())
        val result = ledger.openAccount(id, customers.createPlaceholder(), body?.overdraftLimitCents ?: 0)
        require(result.booked) { result.reason ?: "Account could not be opened" }
        BankSpans.tag(BankSpans.ACCOUNT_ID to id)
        return ResponseEntity.status(HttpStatus.CREATED).body(queries.account(id))
    }

    @GetMapping
    fun list(): List<AccountView> = queries.accounts()

    @GetMapping("/{accountId}")
    fun get(@PathVariable accountId: UUID): ResponseEntity<AccountView> =
        queries.account(AccountId(accountId))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/{accountId}/postings")
    fun postings(@PathVariable accountId: UUID): List<PostingView> = queries.postingsOfAccount(AccountId(accountId))

    /** Cash deposit: an internal transfer from the cash account, so the same workflow. */
    @PostMapping("/{accountId}/deposits")
    fun deposit(@PathVariable accountId: UUID, @RequestBody body: DepositBody): ResponseEntity<TransferAccepted> {
        require(body.amountCents > 0) { "Amount must be positive" }
        val transferId = body.transferId ?: UUID.randomUUID()
        val traceId = transfers.start(
            TransferRequest(
                transferId = transferId,
                debtorAccountId = SystemAccounts.CASH.value,
                amountCents = body.amountCents,
                creditorAccountId = accountId,
            ),
        )
        return TransferController.accepted(transferId, traceId)
    }

    @PostMapping("/{accountId}/block")
    fun block(@PathVariable accountId: UUID): AccountView = afterCommand(accountId) { ledger.blockAccount(it) }

    @PostMapping("/{accountId}/unblock")
    fun unblock(@PathVariable accountId: UUID): AccountView = afterCommand(accountId) { ledger.unblockAccount(it) }

    private fun afterCommand(accountId: UUID, command: (AccountId) -> com.example.bank.ledger.BookingResult): AccountView {
        BankSpans.tag(BankSpans.ACCOUNT_ID to accountId)
        val id = AccountId(accountId)
        val result = command(id)
        if (!result.booked) throw ConflictException(result.reason ?: "rejected")
        return queries.account(id) ?: throw NotFoundException("Account $accountId does not exist")
    }
}
