package com.example.bank.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Who this bank is. The same bank-service runs as several banks (#55), each with its own identity, database schema
 * and Temporal namespace. The defaults are bank A; `application-bank-b.yml` is the second bank.
 */
@ConfigurationProperties("bank.identity")
data class BankIdentity(
    val name: String = "PowerBank",
    /** Business Identifier Code, used to address the bank in payment messages. */
    val bic: String = "PWRBDEFFXXX",
    /** German bank code (BLZ), part of every IBAN of this bank. 99… is not assigned to real banks. */
    val bankCode: String = "99000001",
) {
    init {
        require(bankCode.length == 8 && bankCode.all(Char::isDigit)) { "bank.identity.bank-code must have 8 digits" }
    }
}
