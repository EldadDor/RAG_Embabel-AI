package com.dex.ragpoc.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class TelemetryConfiguration {
    @Bean
    fun meterRegistryCustomizer(appProperties: AppProperties): MeterRegistryCustomizer<MeterRegistry> =
        MeterRegistryCustomizer { registry ->
            registry
                .config()
                .commonTags(
                    "application",
                    appProperties.telemetry.serviceName,
                    "environment",
                    appProperties.environment,
                ).meterFilter(MeterFilter.maximumAllowableTags("http.server.requests", "uri", 100, MeterFilter.deny()))
        }
}
