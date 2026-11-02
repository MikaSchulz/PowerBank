package com.example.bank.ledger

import com.example.bank.domain.AccountId
import java.util.UUID

/** Bank-internal accounts, created by a Flyway migration. They may go negative. */
object SystemAccounts {
    /** Cash: counter account for deposits and withdrawals. */
    val CASH = AccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    /** Clearing account for transfers to external banks. */
    val EXTERNAL_CLEARING = AccountId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
}
