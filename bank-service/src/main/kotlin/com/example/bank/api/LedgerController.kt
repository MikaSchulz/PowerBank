package com.example.bank.api

import com.example.bank.externalbank.ExternalBankSettings
import com.example.bank.externalbank.ExternalBankSimulator
import com.example.bank.ledger.LedgerQueries
import com.example.bank.ledger.LedgerSummary
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class LedgerController(
    private val queries: LedgerQueries,
    private val simulator: ExternalBankSimulator,
) {

    /** Ledger health: the sum of all postings must always be zero. */
    @GetMapping("/ledger/summary")
    fun summary(): LedgerSummary = queries.summary()

    @GetMapping("/simulator/external-bank")
    fun simulatorSettings(): ExternalBankSettings = simulator.settings

    /** Dev tool: make the simulated external bank slower, flakier or stricter while the app is running. */
    @PutMapping("/simulator/external-bank")
    fun updateSimulatorSettings(@RequestBody settings: ExternalBankSettings): ExternalBankSettings {
        simulator.settings = settings
        return settings
    }
}
