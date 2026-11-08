package com.example.bank

import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
import com.example.payments.messages.Pacs028
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The clearing house as an HTTP server for the integration tests: records every pacs.008 and pacs.028 and answers the
 * way a test tells it to. Its confirmations (pacs.002) are posted by the tests themselves to the bank's inbound
 * endpoint, so a test decides when and what the bank hears back.
 */
class FakeClearingHouse(port: Int) : AutoCloseable {

    /** The HTTP status for a pacs.008: 202 received, 409 already received, 422 too late, 5xx down. */
    @Volatile var onPacs008: (Pacs008) -> Int = { ACCEPTED }

    /** The answer to a pacs.028; null: unknown payment (404). */
    @Volatile var onPacs028: (Pacs028) -> Pacs002? = { null }

    val payments = CopyOnWriteArrayList<Pacs008>()
    val inquiries = CopyOnWriteArrayList<Pacs028>()

    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val server = HttpServer.create(InetSocketAddress("localhost", port), 0).apply {
        createContext("/api/clearing/pacs008") { exchange ->
            val payment = json.readValue<Pacs008>(exchange.requestBody)
            payments += payment
            respond(exchange, onPacs008(payment), null)
        }
        createContext("/api/clearing/pacs028") { exchange ->
            val inquiry = json.readValue<Pacs028>(exchange.requestBody)
            inquiries += inquiry
            val answer = onPacs028(inquiry)
            respond(exchange, if (answer == null) NOT_FOUND else OK, answer)
        }
        start()
    }

    fun reset() {
        onPacs008 = { ACCEPTED }
        onPacs028 = { null }
        payments.clear()
        inquiries.clear()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: Any?) {
        exchange.use {
            if (body == null) {
                it.sendResponseHeaders(status, -1)
            } else {
                val bytes = json.writeValueAsBytes(body)
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
    }

    override fun close() = server.stop(0)

    private companion object {
        const val OK = 200
        const val ACCEPTED = 202
        const val NOT_FOUND = 404
    }
}
