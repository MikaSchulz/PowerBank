package com.example.bank.domain

import java.math.BigInteger

/**
 * An IBAN (ISO 13616): normalized (no spaces, upper case), with valid check digits.
 *
 * Personal data: [toString] masks it, so an IBAN inside a logged command or event never shows up in logs or spans.
 * Use [value] where the IBAN itself is needed (database, messages to other banks).
 */
@JvmInline
value class Iban private constructor(val value: String) {

    override fun toString() = "Iban(${value.take(COUNTRY_AND_CHECK_DIGITS)}…)"

    companion object {
        private val format = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")

        /** Country code and check digits move to the end before the mod-97 check. */
        private const val COUNTRY_AND_CHECK_DIGITS = 4

        /** Letters count as numbers: A = 10, B = 11, … Z = 35. */
        private const val LETTER_BASE = 10
        private val MODULUS = 97.toBigInteger()
        private const val GERMAN_BANK_CODE_LENGTH = 8
        private const val GERMAN_ACCOUNT_NUMBER_LENGTH = 10

        /** Parses an IBAN as typed by a person; throws [IllegalArgumentException] on a wrong format or check digits. */
        fun parse(raw: String): Iban {
            val iban = raw.replace(" ", "").uppercase()
            require(format.matches(iban)) { "Invalid IBAN format" }
            require(mod97(iban.drop(COUNTRY_AND_CHECK_DIGITS) + iban.take(COUNTRY_AND_CHECK_DIGITS)) == BigInteger.ONE) {
                "Invalid IBAN check digits"
            }
            return Iban(iban)
        }

        /** A German IBAN: DE, check digits, the 8-digit bank code and the 10-digit account number. */
        fun german(bankCode: String, accountNumber: Long): Iban {
            require(bankCode.length == GERMAN_BANK_CODE_LENGTH && bankCode.all(Char::isDigit)) { "Bank code must have 8 digits" }
            val number = accountNumber.toString()
            require(accountNumber > 0 && number.length <= GERMAN_ACCOUNT_NUMBER_LENGTH) { "Account number must have 1 to 10 digits" }
            val bban = bankCode + number.padStart(GERMAN_ACCOUNT_NUMBER_LENGTH, '0')
            val check = 98.toBigInteger() - mod97(bban + "DE00")
            return parse("DE" + check.toString().padStart(2, '0') + bban)
        }

        private fun mod97(rearranged: String): BigInteger =
            rearranged.map { if (it.isLetter()) (it - 'A' + LETTER_BASE).toString() else it.toString() }
                .joinToString("")
                .toBigInteger()
                .mod(MODULUS)
    }
}
