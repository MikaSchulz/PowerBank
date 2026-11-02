package com.example.bank.api

/** Normalizes an IBAN (no spaces, upper case) and checks format and check digits (ISO 13616, mod 97). */
object Iban {
    private val format = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")

    fun normalize(raw: String): String {
        val iban = raw.replace(" ", "").uppercase()
        require(format.matches(iban)) { "Invalid IBAN format" }
        val rearranged = iban.drop(4) + iban.take(4)
        val numeric = rearranged.map { if (it.isLetter()) (it - 'A' + 10).toString() else it.toString() }.joinToString("")
        require(numeric.toBigInteger().mod(97.toBigInteger()) == 1.toBigInteger()) { "Invalid IBAN check digits" }
        return iban
    }
}
