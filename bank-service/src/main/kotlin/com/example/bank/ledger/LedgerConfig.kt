package com.example.bank.ledger

import com.example.bank.domain.account.Account
import com.example.bank.domain.account.AccountCommand
import com.example.bank.domain.account.AccountEvent
import com.example.bank.domain.account.accountDecider
import com.fraktalio.fmodel.application.EventSourcingAggregate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class LedgerConfig {

    /** The account as an event-sourced aggregate: decider from `domain` plus a repository over the postings. */
    @Bean
    fun accountAggregate(repository: AccountEventRepository): EventSourcingAggregate<AccountCommand, Account, AccountEvent> =
        EventSourcingAggregate(accountDecider, repository)
}
