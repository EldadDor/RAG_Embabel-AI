package com.dex.ragpoc.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/** Fixed operation and outcome vocabulary prevents user input from becoming metric labels. */
enum class RagOperation {
    CHAT,
    CHAT_STREAM,
    RETRIEVAL,
    SEMANTIC_SEARCH,
    LEXICAL_SEARCH,
    RRF,
    RERANK,
    EMBEDDING,
    LOADER,
    CHUNKER,
    INGESTION,
    SESSION,
    ASSET_READ,
    SCHEMA_VALIDATION,
}

enum class RagOutcome { SUCCESS, ERROR, CANCELLED }

@Component
class RagTelemetry(
    private val meters: MeterRegistry,
    private val observations: ObservationRegistry,
) {
    fun <T> observe(
        operation: RagOperation,
        block: () -> T,
    ): T {
        val observation = start(operation)
        val started = System.nanoTime()
        observation.openScope().use {
            try {
                val result = block()
                finish(operation, RagOutcome.SUCCESS, started)
                return result
            } catch (error: RuntimeException) {
                observation.error(error)
                finish(operation, RagOutcome.ERROR, started)
                throw error
            } finally {
                observation.stop()
            }
        }
    }

    fun start(operation: RagOperation): Observation =
        Observation.start("rag.operation", observations).lowCardinalityKeyValue("operation", operation.name.lowercase())

    fun finish(
        operation: RagOperation,
        outcome: RagOutcome,
        startedNanos: Long,
    ) {
        val tags = arrayOf("operation", operation.name.lowercase(), "outcome", outcome.name.lowercase())
        meters.counter("rag_operations_total", *tags).increment()
        Timer
            .builder("rag_operation_duration")
            .tags(*tags)
            .publishPercentileHistogram()
            .register(meters)
            .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
    }

    fun count(
        name: String,
        amount: Double = 1.0,
    ) {
        require(name in FIXED_COUNTS) { "Unknown telemetry counter" }
        meters.counter(name).increment(amount)
    }

    fun firstToken(startedNanos: Long) {
        Timer.builder("rag_chat_time_to_first_token").register(meters).record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
    }

    private companion object {
        val FIXED_COUNTS =
            setOf(
                "rag_chat_stream_deltas_total",
                "rag_retrieval_candidates_total",
                "rag_embedding_cache_hits_total",
                "rag_embedding_dimension_failures_total",
                "rag_ingestion_documents_total",
                "rag_ingestion_chunks_total",
                "rag_ingestion_assets_total",
                "rag_ingestion_skips_total",
                "rag_ingestion_failures_total",
            )
    }
}
