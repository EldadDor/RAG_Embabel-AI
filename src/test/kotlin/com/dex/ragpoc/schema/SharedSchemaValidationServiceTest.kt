package com.dex.ragpoc.schema

import com.dex.ragpoc.config.AppProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SharedSchemaValidationServiceTest {
    @Test
    fun `accepts the configured Python schema and ready model profile`() {
        assertDoesNotThrow {
            SharedSchemaValidationService(FakeSchemaMetadataReader(), AppProperties()).validate()
        }
    }

    @Test
    fun `reports a missing Python migration precisely`() {
        val error =
            assertThrows<SharedSchemaValidationException> {
                SharedSchemaValidationService(
                    FakeSchemaMetadataReader(appliedVersions = migrationVersions - "005_model_profiles"),
                    AppProperties(),
                ).validate()
            }

        assertTrue(error.message!!.contains("005_model_profiles"))
    }

    @Test
    fun `reports an embedding dimension mismatch precisely`() {
        val error =
            assertThrows<SharedSchemaValidationException> {
                SharedSchemaValidationService(
                    FakeSchemaMetadataReader(embeddingType = "vector(768)"),
                    AppProperties(),
                ).validate()
            }

        assertTrue(error.message!!.contains("vector(768)"))
        assertTrue(error.message!!.contains("vector(1024)"))
    }

    @Test
    fun `rejects a model profile that targets another table`() {
        val error =
            assertThrows<SharedSchemaValidationException> {
                SharedSchemaValidationService(
                    FakeSchemaMetadataReader(profile = SchemaModelProfile("bge-m3", 1024, "document_chunks", "ready")),
                    AppProperties(),
                ).validate()
            }

        assertTrue(error.message!!.contains("document_chunks"))
    }

    private class FakeSchemaMetadataReader(
        private val appliedVersions: Set<String> = migrationVersions,
        private val embeddingType: String = "vector(1024)",
        private val profile: SchemaModelProfile = SchemaModelProfile("bge-m3", 1024, "document_chunks_bge_m3", "ready"),
    ) : SchemaMetadataReader {
        override fun missingRelations(relations: Set<String>): Set<String> = emptySet()

        override fun appliedVersions(schema: String): Set<String> = appliedVersions

        override fun columnType(
            relation: String,
            column: String,
        ): String = embeddingType

        override fun modelProfile(
            schema: String,
            profileName: String,
        ): SchemaModelProfile = profile
    }

    private companion object {
        val migrationVersions =
            setOf(
                "001_baseline",
                "002_workspace_authorization",
                "003_chunking_profiles",
                "004_document_assets",
                "005_model_profiles",
            )
    }
}
