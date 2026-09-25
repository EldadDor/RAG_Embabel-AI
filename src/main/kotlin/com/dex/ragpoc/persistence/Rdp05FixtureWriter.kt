package com.dex.ragpoc.persistence

import com.dex.ragpoc.config.AppProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Explicit, idempotent fixture writer for the Python/Kotlin RDP-05 compatibility check. */
@Component
@Profile("rdp05-fixture-write")
class Rdp05FixtureWriter(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    properties: AppProperties,
) : ApplicationRunner {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)
    private val chunkTable = requireIdentifier("PG_CHUNK_TABLE", properties.database.chunkTable)
    private val dimensions = properties.database.vectorDimension

    override fun run(args: ApplicationArguments) {
        val workspaceId = "kotlin-rdp05-fixture-v1"
        val profile = "bge-m3"
        val documentId = "kotlin-rdp05-fixture-document-v1"
        val chunkId = "$documentId:0"
        val databaseId = PostgresInteropCodec.chunkUuid(chunkId)
        val metadata =
            objectMapper.writeValueAsString(
                mapOf(
                    "workspace_id" to workspaceId,
                    "chunking_profile" to "fixture-v1",
                    "document_id" to documentId,
                    "chunk_id" to chunkId,
                    "fixture_owner" to "kotlin",
                ),
            )
        val vector = PostgresInteropCodec.vectorLiteral(List(dimensions) { 0.0f })

        jdbcTemplate.update(
            "INSERT INTO $schema.workspaces (workspace_id, display_name) VALUES (?, ?) ON CONFLICT (workspace_id) DO NOTHING",
            workspaceId,
            "Kotlin RDP-05 Fixture v1",
        )
        jdbcTemplate.update(
            """INSERT INTO $schema.workspace_members (workspace_id, subject, role) VALUES (?, ?, ?)
               ON CONFLICT (workspace_id, subject) DO UPDATE SET role = EXCLUDED.role""",
            workspaceId,
            "kotlin-rdp05-fixture-owner",
            "owner",
        )
        jdbcTemplate.update(
            """INSERT INTO $schema.source_documents
               (workspace_id, chunking_profile, doc_id, root_path, source_path, source_type, content_hash, metadata)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
               ON CONFLICT (workspace_id, chunking_profile, doc_id) DO UPDATE SET content_hash = EXCLUDED.content_hash, metadata = EXCLUDED.metadata, updated_at = now()""",
            workspaceId,
            "fixture-v1",
            documentId,
            "/fixtures",
            "fixture.md",
            "markdown",
            "kotlin-rdp05-fixture-v1",
            metadata,
        )
        jdbcTemplate.update(
            """INSERT INTO $schema.$chunkTable (id, content, metadata, embedding, source, chunk_index)
               VALUES (?, ?, ?::jsonb, ?::vector, ?, ?) ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding""",
            databaseId,
            "Kotlin RDP-05 synthetic fixture.",
            metadata,
            vector,
            "fixture.md",
            0,
        )
        println(
            "RDP-05 fixture ready: workspace=$workspaceId document=$documentId chunk_id=$chunkId database_id=$databaseId profile=$profile",
        )
    }
}
