package com.example.payments.messages

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals

class MessagesTest {

    @Test
    fun `the bank code is read from a German IBAN`() = assertEquals("37040044", germanBankCode("DE89370400440532013000"))

    @Test
    fun `a payment needs a positive amount in EUR`() {
        assertThrows<IllegalArgumentException> { pacs008(amountCents = 0) }
        assertThrows<IllegalArgumentException> { pacs008(currency = "USD") }
    }

    private fun pacs008(amountCents: Long = 100, currency: String = "EUR") = Pacs008(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "e2e",
        amountCents,
        currency,
        "DE89370400440532013000",
        "PWRBDEFFXXX",
        "DE89370400440532013000",
    )
}
