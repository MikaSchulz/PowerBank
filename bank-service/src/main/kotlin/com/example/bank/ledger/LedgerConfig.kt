package com.example.bank.ledger

import com.example.bank.domain.account.Account
import com.example.bank.domain.account.AccountCommand
import com.example.bank.domain.account.AccountEvent
import com.example.bank.domain.account.accountDecider
import com.example.bank.domain.transfer.Transfer
import com.example.bank.domain.transfer.TransferCommand
import com.example.bank.domain.transfer.TransferEvent
import com.example.bank.domain.transfer.transferDecider
import com.fraktalio.fmodel.application.EventSourcingAggregate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class LedgerConfig {

    /** The account as an event-sourced aggregate: decider from `domain` plus a repository over the postings. */
    @Bean
    fun accountAggregate(repository: AccountEventRepository): EventSourcingAggregate<AccountCommand, Account, AccountEvent> =
        EventSourcingAggregate(accountDecider, repository)

    /** The transfer's lifecycle: decider from `domain` plus a repository over `transfers` and `transfer_events`. */
    @Bean
    fun transferAggregate(repository: TransferEventRepository): EventSourcingAggregate<TransferCommand, Transfer, TransferEvent> =
        EventSourcingAggregate(transferDecider, repository)
}
