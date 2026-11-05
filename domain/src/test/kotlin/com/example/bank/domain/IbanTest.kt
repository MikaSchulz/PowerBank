package com.example.bank.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IbanTest {

    @Test
    fun `a typed IBAN is normalized`() =
        assertEquals("DE89370400440532013000", Iban.parse("de89 3704 0044 0532 0130 00").value)

    @Test
    fun `wrong check digits or format are rejected`() {
        assertEquals("Invalid IBAN check digits", assertThrows<IllegalArgumentException> { Iban.parse("DE88370400440532013000") }.message)
        assertEquals("Invalid IBAN format", assertThrows<IllegalArgumentException> { Iban.parse("DE00 1234") }.message)
    }

    @Test
    fun `a German IBAN is built from bank code and account number`() {
        assertEquals("DE89370400440532013000", Iban.german("37040044", 532_013_000).value) // the standard example
        assertThrows<IllegalArgumentException> { Iban.german("1234567", 1) }
        assertThrows<IllegalArgumentException> { Iban.german("37040044", 12_345_678_901) }
    }

    @Test
    fun `toString never shows the whole IBAN`() {
        val text = Iban.german("37040044", 532_013_000).toString()
        assertEquals("Iban(DE89…)", text)
        assertFalse("0532013000" in text)
    }
}
