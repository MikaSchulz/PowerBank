package com.example.bank.domain.account

import com.example.bank.domain.CustomerId

enum class AccountKind {
    /** Customer account: needs sufficient funds (balance plus overdraft). */
    CUSTOMER,

    /** Bank-internal account such as cash or clearing: may go negative. */
    SYSTEM,
}

enum class AccountStatus { OPEN, BLOCKED, CLOSED }

/** State of an account, computed from its events. `status == null` means the account does not exist. */
data class Account(
    val status: AccountStatus? = null,
    val customerId: CustomerId? = null,
    val kind: AccountKind = AccountKind.CUSTOMER,
    val balanceCents: Long = 0,
    val overdraftLimitCents: Long = 0,
) {
    val exists: Boolean get() = status != null

    /** Available amount: balance plus overdraft. */
    val availableCents: Long get() = balanceCents + overdraftLimitCents
}
