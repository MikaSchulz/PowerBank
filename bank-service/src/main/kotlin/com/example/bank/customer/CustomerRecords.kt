package com.example.bank.customer

import com.example.bank.domain.CustomerId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Customer master data. Until stage 9 only a placeholder without personal data:
 * `key_id` will later point to the customer's key in the key store.
 */
@Component
class CustomerRecords(private val jdbc: JdbcClient) {

    fun createPlaceholder(): CustomerId {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO customers (id, key_id) VALUES (:id, :keyId)")
            .param("id", id)
            .param("keyId", "unassigned")
            .update()
        return CustomerId(id)
    }
}
