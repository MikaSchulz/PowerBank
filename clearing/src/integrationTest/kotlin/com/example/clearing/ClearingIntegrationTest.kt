package com.example.clearing

import com.example.clearing.domain.PaymentStatus
import com.example.clearing.ledger.ClearingLedger
import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
import com.example.payments.messages.Pacs028
import com.example.payments.messages.ReasonCodes
import com.example.payments.messages.TxSts
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clearing house against real Postgres and Temporal, with two fake banks as HTTP servers: bank A sends, bank B
 * answers the way a test tells it to (accept, reject or stay silent).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class ClearingIntegrationTest @Autowired constructor(private val ledger: ClearingLedger) {

    @LocalServerPort private var port = 0

    private val clearing by lazy { RestClient.create("http://localhost:$port/api/clearing") }

    @Test
    fun `an accepted payment is settled between the banks and both get ACSC`() {
        bankB.behaviour = Behaviour.ACCEPT
        val before = balances()

        val payment = send(amountCents = 123_45)

        assertEquals(PaymentStatus.SETTLED, awaitFinished(payment.uetr).status)
        val after = balances()
        assertEquals(before.getValue(BANK_A) - 123_45, after.getValue(BANK_A))
        assertEquals(before.getValue(BANK_B) + 123_45, after.getValue(BANK_B))
        assertEquals(before.getValue(ClearingLedger.RESERVED), after.getValue(ClearingLedger.RESERVED))
        awaitConfirmation(bankA, payment.uetr, TxSts.ACSC)
        awaitConfirmation(bankB, payment.uetr, TxSts.ACSC)
        assertEquals(TxSts.ACSC, inquiry(payment.uetr).txSts)
    }

    @Test
    fun `a rejection by the creditor bank releases the reservation`() {
        bankB.behaviour = Behaviour.REJECT
        val before = balances()

        val payment = send()

        val finished = awaitFinished(payment.uetr)
        assertEquals(PaymentStatus.REJECTED, finished.status)
        assertEquals(ReasonCodes.CLOSED_ACCOUNT, finished.reasonCode)
        assertEquals(before, balances())
        awaitConfirmation(bankA, payment.uetr, TxSts.RJCT)
    }

    @Test
    fun `no answer within the deadline rejects the payment on both sides`() {
        bankB.behaviour = Behaviour.SILENT
        val before = balances()

        val payment = send()

        assertEquals(ReasonCodes.TIMEOUT_CREDITOR_AGENT, awaitFinished(payment.uetr).reasonCode)
        assertEquals(before, balances())
        awaitConfirmation(bankA, payment.uetr, TxSts.RJCT)
        awaitConfirmation(bankB, payment.uetr, TxSts.RJCT)
    }

    @Test
    fun `a payment to a bank that is not a participant is rejected without reserving anything`() {
        val payment = send(creditorIban = "DE13370400440532013000") // a real bank code, not a participant

        val finished = awaitFinished(payment.uetr)
        assertEquals(ReasonCodes.UNKNOWN_CREDITOR_AGENT, finished.reasonCode)
        awaitConfirmation(bankA, payment.uetr, TxSts.RJCT)
    }

    @Test
    fun `the same payment is cleared only once`() {
        bankB.behaviour = Behaviour.ACCEPT
        val payment = send()

        val error = runCatching { post("/pacs008", payment) }.exceptionOrNull()
        assertEquals(HttpStatus.CONFLICT, (error as? HttpClientErrorException)?.statusCode)
        awaitFinished(payment.uetr)
    }

    @Test
    fun `the settlement accounts always add up`() {
        assertEquals(0, balances().values.sum())
        assertTrue(balances().getValue(BANK_A) != 0L, "the participants are funded")
    }

    // --- helpers ---

    private fun send(amountCents: Long = 10_00, creditorIban: String = "DE10990000021000000001"): Pacs008 {
        val payment =
            Pacs008(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "transfer-1",
                amountCents,
                "EUR",
                "DE38990000011000000001",
                "PWRBDEFFXXX",
                creditorIban,
            )
        post("/pacs008", payment)
        return payment
    }

    private fun post(path: String, body: Any) {
        clearing.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity()
    }

    private fun inquiry(uetr: UUID): Pacs002 =
        checkNotNull(
            clearing.post().uri(
                "/pacs028",
            ).contentType(MediaType.APPLICATION_JSON).body(Pacs028(UUID.randomUUID(), uetr)).retrieve().body(Pacs002::class.java),
        )

    private fun balances() = ledger.balances().associate { it.account to it.balanceCents }

    private fun awaitFinished(uetr: UUID) = await("payment $uetr finished") { ledger.find(uetr)?.takeIf { it.status.finished } }

    private fun awaitConfirmation(bank: FakeBank, uetr: UUID, txSts: TxSts) =
        await("${bank.name} got $txSts for $uetr") { bank.confirmations.firstOrNull { it.uetr == uetr && it.txSts == txSts } }

    private fun <T> await(what: String, probe: () -> T?): T {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        error("Timed out waiting for: $what")
    }

    enum class Behaviour { ACCEPT, REJECT, SILENT }

    /** A participant: receives pacs.008 and pacs.002 and answers forwarded payments asynchronously, like a real bank. */
    class FakeBank(val name: String) {
        @Volatile var behaviour = Behaviour.ACCEPT
        val confirmations = CopyOnWriteArrayList<Pacs002>()
        var clearingUrl: () -> String = { error("clearing not started") }
        private val json = JsonMapper.builder().addModule(kotlinModule()).build()
        private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/api/clearing/inbound/pacs008") { exchange ->
                val payment = json.readValue<Pacs008>(exchange.requestBody)
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
                val answer = when (behaviour) {
                    Behaviour.ACCEPT -> Pacs002(UUID.randomUUID(), payment.uetr, TxSts.ACCP)
                    Behaviour.REJECT -> Pacs002(UUID.randomUUID(), payment.uetr, TxSts.RJCT, ReasonCodes.CLOSED_ACCOUNT)
                    Behaviour.SILENT -> null
                }
                if (answer != null) {
                    Thread {
                        RestClient.create(clearingUrl()).post().uri("/api/clearing/pacs002").contentType(MediaType.APPLICATION_JSON)
                            .body(answer).retrieve().toBodilessEntity()
                    }.start()
                }
            }
            createContext("/api/clearing/inbound/pacs002") { exchange ->
                confirmations += json.readValue<Pacs002>(exchange.requestBody)
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            start()
        }

        val url get() = "http://localhost:${server.address.port}"

        fun stop() = server.stop(0)
    }

    companion object {
        private const val BANK_A = "bank:99000001"
        private const val BANK_B = "bank:99000002"
        private val bankA = FakeBank("bank A")
        private val bankB = FakeBank("bank B")

        @JvmStatic
        @DynamicPropertySource
        fun participants(registry: DynamicPropertyRegistry) {
            // A list from another property source replaces the whole list, so every field is set here.
            listOf(Triple("99000001", "PWRBDEFFXXX", bankA), Triple("99000002", "PTNRDEFFXXX", bankB)).forEachIndexed {
                    i,
                    (code, bic, bank),
                ->
                registry.add("clearing.participants[$i].bank-code") { code }
                registry.add("clearing.participants[$i].bic") { bic }
                registry.add("clearing.participants[$i].url") { bank.url }
            }
        }

        @JvmStatic
        @AfterAll
        fun stopBanks() {
            bankA.stop()
            bankB.stop()
        }
    }

    init {
        // The port is known only once the context runs: the banks read it when they answer.
        bankA.clearingUrl = { "http://localhost:$port" }
        bankB.clearingUrl = { "http://localhost:$port" }
    }
}
