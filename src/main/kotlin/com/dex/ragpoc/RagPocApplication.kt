package com.dex.ragpoc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class RagPocApplication

fun main(args: Array<String>) {
    runApplication<RagPocApplication>(*args)
}
