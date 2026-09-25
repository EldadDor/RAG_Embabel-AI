package com.dex.ragpoc.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JsonConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().findAndRegisterModules()
}
