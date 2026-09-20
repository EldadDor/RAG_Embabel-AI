package com.dex.ragpoc.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class AppPropertiesTest {
    @Test
    fun `binds Python-compatible settings from standard Spring property names`() {
        val source =
            MapConfigurationPropertySource(
                mapOf(
                    "app.database.schema" to "rag",
                    "app.database.chunk-table" to "document_chunks",
                    "app.database.vector-dimension" to "768",
                    "app.rag.chunk-size" to "800",
                    "app.rag.chunk-overlap" to "120",
                    "app.assets.storage-root" to ".rag-assets",
                    "app.observability.langfuse-enabled" to "false",
                ),
            )

        val properties = Binder(source).bind("app", AppProperties::class.java).get()

        assertEquals("rag", properties.database.schema)
        assertEquals("document_chunks", properties.database.chunkTable)
        assertEquals(768, properties.database.vectorDimension)
        assertEquals(800, properties.rag.chunkSize)
        assertEquals(120, properties.rag.chunkOverlap)
        assertEquals(".rag-assets", properties.assets.storageRoot.toString())
        assertFalse(properties.observability.langfuseEnabled)
    }
}
