package com.yourorg.ragpoc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RagPocApplication

fun main(args: Array<String>) {
    runApplication<RagPocApplication>(*args)
}
