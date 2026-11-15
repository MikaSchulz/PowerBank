package com.example.bank.api

import com.example.payments.messages.Pacs002
import com.example.payments.messages.Pacs008
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Where the clearing house delivers its messages to this bank (#55). 202: the bank has the message; 4xx: it refuses
 * it for good. The answer to a payment comes later, as its own message to the clearing house.
 */
@RestController
@RequestMapping("/api/clearing/inbound")
class ClearingInboundController(private val inbound: ClearingInboundService) {

    /** A payment from another bank: the bank checks the account and answers the clearing house within the deadline. */
    @PostMapping("/pacs008")
    fun pacs008(@RequestBody payment: Pacs008): ResponseEntity<Void> {
        inbound.receive(payment)
        return ResponseEntity.accepted().build()
    }

    /** The final status of a payment: ACSC (settled) or RJCT (rejected, with a reason code). */
    @PostMapping("/pacs002")
    fun pacs002(@RequestBody confirmation: Pacs002): ResponseEntity<Void> {
        inbound.confirm(confirmation)
        return ResponseEntity.accepted().build()
    }
}
