package com.dex.ragpoc.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RagTelemetryTest {
    @Test
    fun `records only fixed operation and outcome tags`() {
        val meters = SimpleMeterRegistry()
        val telemetry = RagTelemetry(meters, ObservationRegistry.create())

        telemetry.observe(RagOperation.RETRIEVAL) { "ok" }
        telemetry.count("rag_retrieval_candidates_total", 3.0)

        assertEquals(
            1.0,
            meters
                .get("rag_operations_total")
                .tag("operation", "retrieval")
                .tag("outcome", "success")
                .counter()
                .count(),
        )
        assertEquals(3.0, meters.get("rag_retrieval_candidates_total").counter().count())
        assertEquals(
            setOf("operation", "outcome"),
            meters
                .get("rag_operations_total")
                .counter()
                .id.tags
                .map { it.key }
                .toSet(),
        )
        assertFailsWith<IllegalArgumentException> { telemetry.count("workspace_id") }
    }
}
