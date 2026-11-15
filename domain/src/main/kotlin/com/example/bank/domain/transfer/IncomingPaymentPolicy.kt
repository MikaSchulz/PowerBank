package com.example.bank.domain.transfer

import com.example.bank.domain.account.AccountKind
import com.example.bank.domain.account.AccountStatus

/** Why an account can't receive a payment from another bank; the bank-service answers it with an ISO 20022 code. */
enum class IncomingRejection {
    /** No customer account with this IBAN at this bank. */
    UNKNOWN_ACCOUNT,
    ACCOUNT_CLOSED,

    /** Stricter than an internal credit, which a blocked account still receives: no new money from outside. */
    ACCOUNT_BLOCKED,
}

/**
 * What the creditor bank checks before it answers the clearing house. A pure function of the creditor account's state:
 * the ledger supplies it and records the answer with the transfer decider, in one transaction.
 */
object IncomingPaymentPolicy {
    /** Null: the account can receive the payment. */
    fun check(status: AccountStatus?, kind: AccountKind?): IncomingRejection? = when {
        status == null || kind != AccountKind.CUSTOMER -> IncomingRejection.UNKNOWN_ACCOUNT
        status == AccountStatus.CLOSED -> IncomingRejection.ACCOUNT_CLOSED
        status == AccountStatus.BLOCKED -> IncomingRejection.ACCOUNT_BLOCKED
        else -> null
    }
}
