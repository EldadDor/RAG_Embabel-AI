package com.dex.ragpoc.persistence

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationSummary
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Explicit, idempotent fixture writer for the Python/Kotlin RDP-05 compatibility check. */
@Component
@Profile("rdp05-fixture-write")
class Rdp05FixtureWriter(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val conversationRepository: JdbcConversationRepository,
    private val chunkRepository: JdbcChunkRepository,
    private val documentAssetRepository: JdbcDocumentAssetRepository,
    private val embeddingCacheRepository: JdbcEmbeddingCacheRepository,
    private val modelProfileRepository: JdbcModelProfileRepository,
    private val sourceDocumentRepository: JdbcSourceDocumentRepository,
    private val workspaceRepository: JdbcWorkspaceRepository,
    properties: AppProperties,
) : ApplicationRunner {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)
    private val chunkTable = requireIdentifier("PG_CHUNK_TABLE", properties.database.chunkTable)
    private val dimensions = properties.database.vectorDimension

    @Transactional
    override fun run(args: ApplicationArguments) {
        val workspaceId = "kotlin-rdp05-fixture-v1"
        val profile = "bge-m3"
        val documentId = "kotlin-rdp05-fixture-document-v1"
        val chunkId = "$documentId:0"
        val assetId = "kotlin-rdp05-fixture-asset-v1"
        val ownerId = "kotlin-rdp05-fixture-owner"
        val sessionId = "kotlin-rdp05-fixture-session-v1"
        val databaseId = PostgresInteropCodec.chunkUuid(chunkId)
        val metadata =
            objectMapper.writeValueAsString(
                mapOf(
                    "workspace_id" to workspaceId,
                    "chunking_profile" to "fixture-v1",
                    "doc_id" to documentId,
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
            ownerId,
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
        documentAssetRepository.upsert(
            StoredDocumentAsset(
                assetId = assetId,
                workspaceId = workspaceId,
                chunkingProfile = "fixture-v1",
                documentId = documentId,
                storageKey = "fixtures/kotlin-rdp05-fixture-asset-v1.png",
                contentHash = "kotlin-rdp05-fixture-asset-sha256-v1",
                mediaType = "image/png",
                byteSize = 67,
                originalName = "fixture.png",
                relationshipId = "rIdFixture",
                ordinal = 0,
                width = 1,
                height = 1,
                altText = "Kotlin RDP-05 fixture asset",
                caption = "Synthetic interoperability asset",
                anchorBlockId = "fixture-block-0",
            ),
        )
        documentAssetRepository.linkToChunk(workspaceId, "fixture-v1", documentId, chunkId, assetId, 0)

        jdbcTemplate.update("DELETE FROM $schema.conversation_summaries WHERE session_id = ?", sessionId)
        jdbcTemplate.update("DELETE FROM $schema.conversation_turns WHERE session_id = ?", sessionId)
        jdbcTemplate.update("DELETE FROM $schema.chat_sessions WHERE session_id = ?", sessionId)
        conversationRepository.create(
            ChatSession(sessionId, ownerId, workspaceId, "Kotlin RDP-05 fixture session"),
        )
        conversationRepository.appendTurn(sessionId, "user", "Kotlin fixture question", "Kotlin fixture question")
        conversationRepository.appendTurn(sessionId, "assistant", "Kotlin fixture answer", "Kotlin fixture answer")
        val lastTurnId =
            jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM $schema.conversation_turns WHERE session_id = ?",
                Long::class.java,
                sessionId,
            ) ?: error("RDP-05 fixture conversation did not create turns")
        conversationRepository.upsertSummary(
            ConversationSummary(sessionId, "Kotlin fixture conversation summary", lastTurnId),
        )

        val modelProfile = modelProfileRepository.get(profile) ?: error("RDP-05 fixture model profile is missing")
        require(modelProfile.dimensions == dimensions) { "RDP-05 fixture model profile dimensions do not match configuration" }
        val cacheKey = PostgresInteropCodec.embeddingCacheKey(modelProfile, "Kotlin RDP-05 cache fixture", "document")
        embeddingCacheRepository.put(cacheKey, modelProfile.provider, modelProfile.model, dimensions, List(dimensions) { 0.25f })
        assertPythonFixture(modelProfile)
        println(
            "RDP-05 fixture ready: workspace=$workspaceId document=$documentId chunk_id=$chunkId asset=$assetId session=$sessionId cache_key=$cacheKey database_id=$databaseId profile=$profile",
        )
    }

    private fun assertPythonFixture(modelProfile: com.dex.ragpoc.domain.ModelProfile) {
        val workspaceId = "python-rdp05-fixture-v1"
        val documentId = "python-rdp05-fixture-document-v1"
        val chunkId = "$documentId:0"
        val assetId = "python-rdp05-fixture-asset-v1"
        val ownerId = "python-rdp05-fixture-owner"
        val sessionId = "python-rdp05-fixture-session-v1"
        val cacheKey = PostgresInteropCodec.embeddingCacheKey(modelProfile, "Python RDP-05 cache fixture", "document")

        require(workspaceRepository.listForSubject(ownerId).any { it.workspaceId == workspaceId && it.role == "owner" })
        require(sourceDocumentRepository.find(workspaceId, "fixture-v1", documentId)?.contentHash == "python-rdp05-fixture-v1")
        require(chunkRepository.find(PostgresInteropCodec.chunkUuid(chunkId))?.content == "Python RDP-05 synthetic fixture.")
        require(documentAssetRepository.find(assetId)?.mediaType == "image/png")
        require(documentAssetRepository.listForChunk(workspaceId, "fixture-v1", chunkId).single().assetId == assetId)
        require(conversationRepository.findActiveOwned(sessionId, ownerId)?.workspaceId == workspaceId)
        require(
            conversationRepository.recentTurns(sessionId, 10).map { it.content } ==
                listOf("Python fixture question", "Python fixture answer"),
        )
        require(conversationRepository.summary(sessionId)?.summary == "Python fixture conversation summary")
        require(embeddingCacheRepository.get(cacheKey, dimensions) == List(dimensions) { 0.5f })
    }
}
