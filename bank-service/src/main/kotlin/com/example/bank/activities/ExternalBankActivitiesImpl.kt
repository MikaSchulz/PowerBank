package com.example.bank.activities

import com.example.bank.externalbank.ExternalBankSimulator
import com.example.bank.workflow.BANK_TASK_QUEUE
import io.temporal.spring.boot.ActivityImpl
import org.springframework.stereotype.Component

@Component
@ActivityImpl(taskQueues = [BANK_TASK_QUEUE])
class ExternalBankActivitiesImpl(private val externalBank: ExternalBankSimulator) : ExternalBankActivities {

    override fun submitPayment(payment: ExternalPayment): ExternalBankAnswer =
        externalBank.receive(payment.transferId, payment.creditorIban, payment.amountCents)
}
