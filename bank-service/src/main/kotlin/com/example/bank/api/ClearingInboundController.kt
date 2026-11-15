package com.example.bank.api

import com.example.payments.messages.Pacs002
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Where the clearing house delivers its messages to this bank (#55). For now the confirmations of the bank's own
 * payments; incoming payments (pacs.008) follow with #60. 202: the bank has the message; 4xx: it refuses it for good.
 */
@RestController
@RequestMapping("/api/clearing/inbound")
class ClearingInboundController(private val transfers: TransferService) {

    /** The final status of a payment this bank sent: ACSC (settled) or RJCT (rejected, with a reason code). */
    @PostMapping("/pacs002")
    fun pacs002(@RequestBody confirmation: Pacs002): ResponseEntity<Void> {
        transfers.confirm(confirmation)
        return ResponseEntity.accepted().build()
    }
}
