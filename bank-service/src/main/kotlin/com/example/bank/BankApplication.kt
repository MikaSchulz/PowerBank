package com.example.bank

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BankApplication

fun main(args: Array<String>) {
    runApplication<BankApplication>(*args)
}
