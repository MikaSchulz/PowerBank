package com.example.bank.api

import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.LedgerSummary
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class LedgerController(private val queries: LedgerQueries) {

    /** Ledger health: the sum of all postings must always be zero. */
    @GetMapping("/ledger/summary")
    fun summary(): LedgerSummary = queries.summary()
}
