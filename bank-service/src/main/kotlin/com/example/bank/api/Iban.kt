package com.example.bank.api

/** Normalizes an IBAN (no spaces, upper case) and checks format and check digits (ISO 13616, mod 97). */
object Iban {
    private val format = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")

    fun normalize(raw: String): String {
        val iban = raw.replace(" ", "").uppercase()
        require(format.matches(iban)) { "Invalid IBAN format" }
        val rearranged = iban.drop(COUNTRY_AND_CHECK_DIGITS) + iban.take(COUNTRY_AND_CHECK_DIGITS)
        val numeric = rearranged.map { if (it.isLetter()) (it - 'A' + LETTER_BASE).toString() else it.toString() }.joinToString("")
        require(numeric.toBigInteger().mod(MODULUS.toBigInteger()) == 1.toBigInteger()) { "Invalid IBAN check digits" }
        return iban
    }

    /** Country code and check digits move to the end before the mod-97 check. */
    private const val COUNTRY_AND_CHECK_DIGITS = 4

    /** Letters count as numbers: A = 10, B = 11, … Z = 35. */
    private const val LETTER_BASE = 10
    private const val MODULUS = 97
}
