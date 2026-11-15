package com.example.bank.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * A transfer the bank accepted; its workflow runs. [traceId] is the W3C trace ID of this request (null if not
 * sampled): the whole transfer is one trace in Tempo.
 */
data class TransferAccepted(val transferId: UUID, val statusUrl: String, val traceId: String?) {
    companion object {
        /** 202 with the URL where the area that started it follows the transfer. */
        fun response(transferId: UUID, statusUrl: String, traceId: String?): ResponseEntity<TransferAccepted> =
            ResponseEntity.status(HttpStatus.ACCEPTED).header("Location", statusUrl).body(TransferAccepted(transferId, statusUrl, traceId))
    }
}
