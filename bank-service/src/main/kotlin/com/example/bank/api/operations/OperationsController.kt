package com.example.bank.api.operations

import com.example.bank.api.NotFoundException
import com.example.bank.domain.AccountId
import com.example.bank.domain.account.AccountKind
import com.example.bank.ledger.AccountView
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.LedgerSummary
import com.example.bank.ledger.PostingView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Operations (#61): the bank's own books and the systems around them. The ledger check, the system accounts (cash,
 * clearing, settlement), and the clearing house's chaos settings for demos. Grafana and Temporal are linked in the UI.
 */
@RestController
@RequestMapping("/api/operations")
class OperationsController(
    private val queries: LedgerQueries,
    private val clearingHouse: ClearingHouseOperations,
) {

    /** Ledger health: the sum of all postings must always be zero. */
    @GetMapping("/ledger/summary")
    fun summary(): LedgerSummary = queries.summary()

    /** The bank's own accounts: they may go negative and have no IBAN. */
    @GetMapping("/system-accounts")
    fun systemAccounts(): List<AccountView> = queries.accounts().filter { it.kind == AccountKind.SYSTEM.name }

    @GetMapping("/system-accounts/{accountId}/postings")
    fun postings(@PathVariable accountId: UUID): List<PostingView> {
        queries.account(AccountId(accountId))?.takeIf { it.kind == AccountKind.SYSTEM.name }
            ?: throw NotFoundException("No such system account")
        return queries.postingsOfAccount(AccountId(accountId))
    }

    /** The clearing house's chaos settings (delayed or lost forwards), passed through to it. */
    @GetMapping("/clearing-house/chaos")
    fun chaos(): Map<String, Any?> = clearingHouse.chaos()

    @PutMapping("/clearing-house/chaos")
    fun chaos(@RequestBody settings: Map<String, Any?>): Map<String, Any?> = clearingHouse.chaos(settings)

    /** The participants' settlement accounts as the clearing house books them: ours mirrors our settlement account. */
    @GetMapping("/clearing-house/settlement-accounts")
    fun settlementAccounts(): List<Map<String, Any?>> = clearingHouse.settlementAccounts()
}
