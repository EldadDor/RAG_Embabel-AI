package com.dex.ragpoc.persistence

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationSummary
import com.dex.ragpoc.domain.ConversationTurn
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.domain.SourceDocument
import com.dex.ragpoc.domain.SourceType
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class AuthorizedWorkspace(
    val workspaceId: String,
    val displayName: String,
    val role: String,
)

data class StoredChunk(
    val id: UUID,
    val content: String,
    val metadata: Map<String, Any?>,
    val source: String? = null,
    val pageNumber: Int? = null,
    val chunkIndex: Int? = null,
)

data class StoredDocumentAsset(
    val assetId: String,
    val workspaceId: String,
    val chunkingProfile: String,
    val documentId: String,
    val storageKey: String,
    val contentHash: String,
    val mediaType: String,
    val byteSize: Long,
    val originalName: String? = null,
    val relationshipId: String? = null,
    val ordinal: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val altText: String? = null,
    val caption: String? = null,
    val anchorBlockId: String? = null,
)

@Repository
class JdbcWorkspaceRepository(
    private val jdbcTemplate: JdbcTemplate,
    properties: AppProperties,
) {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)

    fun listForSubject(subject: String): List<AuthorizedWorkspace> =
        jdbcTemplate.query(
            """
            SELECT workspace.workspace_id, workspace.display_name, member.role
            FROM $schema.workspace_members member
            JOIN $schema.workspaces workspace USING (workspace_id)
            WHERE member.subject = ?
            ORDER BY workspace.display_name, workspace.workspace_id
            """.trimIndent(),
            { resultSet, _ ->
                AuthorizedWorkspace(
                    workspaceId = resultSet.getString("workspace_id"),
                    displayName = resultSet.getString("display_name"),
                    role = resultSet.getString("role"),
                )
            },
            subject,
        )

    fun isAuthorized(
        subject: String,
        workspaceId: String,
    ): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM $schema.workspace_members WHERE subject = ? AND workspace_id = ?)",
            Boolean::class.java,
            subject,
            workspaceId,
        ) ?: false
}

@Repository
class JdbcModelProfileRepository(
    private val jdbcTemplate: JdbcTemplate,
    properties: AppProperties,
) {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)

    fun get(profileName: String): ModelProfile? =
        jdbcTemplate
            .query(
                """
                SELECT profile_name, provider, model, dimensions, storage_target,
                       query_prefix, document_prefix, status
                FROM $schema.model_profiles
                WHERE profile_name = ?
                """.trimIndent(),
                { resultSet, _ ->
                    ModelProfile(
                        profileName = resultSet.getString("profile_name"),
                        provider = resultSet.getString("provider"),
                        model = resultSet.getString("model"),
                        dimensions = resultSet.getInt("dimensions"),
                        storageTarget = resultSet.getString("storage_target"),
                        queryPrefix = resultSet.getString("query_prefix"),
                        documentPrefix = resultSet.getString("document_prefix"),
                        status = resultSet.getString("status"),
                    )
                },
                profileName,
            ).firstOrNull()

    fun setStatus(
        profileName: String,
        status: String,
    ) {
        require(status in setOf("draft", "warming", "ready", "archived")) {
            "Invalid model profile status: $status"
        }
        val updated =
            jdbcTemplate.update(
                "UPDATE $schema.model_profiles SET status = ?, updated_at = now() WHERE profile_name = ?",
                status,
                profileName,
            )
        require(updated == 1) { "Unknown model profile: $profileName" }
    }
}

@Repository
class JdbcEmbeddingCacheRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val namedJdbcTemplate: NamedParameterJdbcTemplate,
    properties: AppProperties,
) {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)

    fun contains(
        cacheKey: String,
        dimensions: Int,
    ): Boolean {
        val storedDimensions = dimensionsFor(cacheKey) ?: return false
        require(storedDimensions == dimensions) { "Cached embedding dimension does not match the model profile" }
        return true
    }

    fun containsMany(
        cacheKeys: Collection<String>,
        dimensions: Int,
    ): Set<String> {
        if (cacheKeys.isEmpty()) return emptySet()
        val rows =
            namedJdbcTemplate.query(
                "SELECT cache_key, dimensions FROM $schema.embedding_cache WHERE cache_key IN (:cacheKeys)",
                MapSqlParameterSource("cacheKeys", cacheKeys),
            ) { resultSet, _ -> resultSet.getString("cache_key") to resultSet.getInt("dimensions") }
        require(rows.all { (_, storedDimensions) -> storedDimensions == dimensions }) {
            "Cached embedding dimension does not match the model profile"
        }
        return rows.mapTo(linkedSetOf()) { (cacheKey, _) -> cacheKey }
    }

    @Transactional
    fun get(
        cacheKey: String,
        dimensions: Int,
    ): List<Float>? {
        val row =
            jdbcTemplate
                .query(
                    "SELECT embedding, dimensions FROM $schema.embedding_cache WHERE cache_key = ?",
                    { resultSet, _ -> resultSet.getBytes("embedding") to resultSet.getInt("dimensions") },
                    cacheKey,
                ).firstOrNull() ?: return null
        require(row.second == dimensions) { "Cached embedding dimension does not match the model profile" }
        jdbcTemplate.update(
            "UPDATE $schema.embedding_cache SET hit_count = hit_count + 1, last_hit_at = now() WHERE cache_key = ?",
            cacheKey,
        )
        return PostgresInteropCodec.decodeEmbedding(row.first, dimensions)
    }

    fun put(
        cacheKey: String,
        provider: String,
        model: String,
        dimensions: Int,
        embedding: List<Float>,
    ) {
        require(embedding.size == dimensions) { "Embedding dimension does not match the model profile" }
        jdbcTemplate.update(
            """
            INSERT INTO $schema.embedding_cache (cache_key, provider, model, dimensions, embedding)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (cache_key) DO NOTHING
            """.trimIndent(),
            cacheKey,
            provider,
            model,
            dimensions,
            PostgresInteropCodec.encodeEmbedding(embedding),
        )
    }

    private fun dimensionsFor(cacheKey: String): Int? =
        jdbcTemplate
            .query(
                "SELECT dimensions FROM $schema.embedding_cache WHERE cache_key = ?",
                { resultSet, _ -> resultSet.getInt("dimensions") },
                cacheKey,
            ).firstOrNull()
}

@Repository
class JdbcConversationRepository(
    private val jdbcTemplate: JdbcTemplate,
    properties: AppProperties,
) {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)

    fun listActiveOwned(
        ownerId: String,
        workspaceId: String,
    ): List<ChatSession> =
        jdbcTemplate.query(
            """
            SELECT session_id, owner_id, workspace_id, title, last_preview, archived
            FROM $schema.chat_sessions
            WHERE owner_id = ? AND workspace_id = ? AND archived = false
            ORDER BY updated_at DESC, session_id DESC
            """.trimIndent(),
            ::mapSession,
            ownerId,
            workspaceId,
        )

    fun findActiveOwned(
        sessionId: String,
        ownerId: String,
    ): ChatSession? =
        jdbcTemplate
            .query(
                """
                SELECT session_id, owner_id, workspace_id, title, last_preview, archived
                FROM $schema.chat_sessions
                WHERE session_id = ? AND owner_id = ? AND archived = false
                """.trimIndent(),
                ::mapSession,
                sessionId,
                ownerId,
            ).firstOrNull()

    fun create(session: ChatSession) {
        require(!session.archived) { "A new chat session cannot be archived" }
        jdbcTemplate.update(
            """
            INSERT INTO $schema.chat_sessions (session_id, owner_id, workspace_id, title, last_preview, archived)
            VALUES (?, ?, ?, ?, ?, false)
            """.trimIndent(),
            session.sessionId,
            session.ownerId,
            session.workspaceId,
            session.title,
            session.lastPreview,
        )
    }

    fun renameActiveOwned(
        sessionId: String,
        ownerId: String,
        title: String,
    ): Boolean =
        jdbcTemplate.update(
            """
            UPDATE $schema.chat_sessions
            SET title = ?, updated_at = now()
            WHERE session_id = ? AND owner_id = ? AND archived = false
            """.trimIndent(),
            title,
            sessionId,
            ownerId,
        ) == 1

    fun archiveActiveOwned(
        sessionId: String,
        ownerId: String,
    ): Boolean =
        jdbcTemplate.update(
            """
            UPDATE $schema.chat_sessions
            SET archived = true, updated_at = now()
            WHERE session_id = ? AND owner_id = ? AND archived = false
            """.trimIndent(),
            sessionId,
            ownerId,
        ) == 1

    @Transactional
    fun appendTurn(
        sessionId: String,
        role: String,
        content: String,
        preview: String? = null,
    ) {
        require(role in setOf("user", "assistant")) { "Invalid conversation role: $role" }
        require(content.isNotBlank()) { "Conversation content must not be blank" }
        jdbcTemplate.update(
            "INSERT INTO $schema.conversation_turns (session_id, role, content) VALUES (?, ?, ?)",
            sessionId,
            role,
            content,
        )
        jdbcTemplate.update(
            "UPDATE $schema.chat_sessions SET last_preview = ?, updated_at = now() WHERE session_id = ?",
            preview ?: content.take(240),
            sessionId,
        )
    }

    fun recentTurns(
        sessionId: String,
        limit: Int,
    ): List<ConversationTurn> {
        require(limit in 1..100) { "Conversation turn limit must be between 1 and 100" }
        return jdbcTemplate
            .query(
                """
                SELECT id, session_id, role, content
                FROM $schema.conversation_turns
                WHERE session_id = ?
                ORDER BY id DESC
                LIMIT ?
                """.trimIndent(),
                { resultSet, _ ->
                    ConversationTurn(
                        id = resultSet.getLong("id"),
                        sessionId = resultSet.getString("session_id"),
                        role = resultSet.getString("role"),
                        content = resultSet.getString("content"),
                    )
                },
                sessionId,
                limit,
            ).asReversed()
    }

    fun summary(sessionId: String): ConversationSummary? =
        jdbcTemplate
            .query(
                "SELECT session_id, summary, last_turn_id FROM $schema.conversation_summaries WHERE session_id = ?",
                { resultSet, _ ->
                    ConversationSummary(
                        sessionId = resultSet.getString("session_id"),
                        summary = resultSet.getString("summary"),
                        lastTurnId = resultSet.getLong("last_turn_id"),
                    )
                },
                sessionId,
            ).firstOrNull()

    fun upsertSummary(summary: ConversationSummary) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.conversation_summaries (session_id, summary, last_turn_id)
            VALUES (?, ?, ?)
            ON CONFLICT (session_id) DO UPDATE
            SET summary = EXCLUDED.summary, last_turn_id = EXCLUDED.last_turn_id, updated_at = now()
            """.trimIndent(),
            summary.sessionId,
            summary.summary,
            summary.lastTurnId,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapSession(
        resultSet: java.sql.ResultSet,
        rowNumber: Int,
    ): ChatSession =
        ChatSession(
            sessionId = resultSet.getString("session_id"),
            ownerId = resultSet.getString("owner_id"),
            workspaceId = resultSet.getString("workspace_id"),
            title = resultSet.getString("title"),
            lastPreview = resultSet.getString("last_preview"),
            archived = resultSet.getBoolean("archived"),
        )
}

@Repository
class JdbcSourceDocumentRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    properties: AppProperties,
) {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)

    fun find(
        workspaceId: String,
        chunkingProfile: String,
        documentId: String,
    ): SourceDocument? =
        jdbcTemplate
            .query(
                """
                SELECT workspace_id, chunking_profile, doc_id, root_path, source_path, source_type,
                       content_hash, metadata
                FROM $schema.source_documents
                WHERE workspace_id = ? AND chunking_profile = ? AND doc_id = ?
                """.trimIndent(),
                ::mapSourceDocument,
                workspaceId,
                chunkingProfile,
                documentId,
            ).firstOrNull()

    fun upsert(document: SourceDocument) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.source_documents
                (workspace_id, chunking_profile, doc_id, root_path, source_path, source_type, content_hash, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (workspace_id, chunking_profile, doc_id) DO UPDATE
            SET root_path = EXCLUDED.root_path,
                source_path = EXCLUDED.source_path,
                source_type = EXCLUDED.source_type,
                content_hash = EXCLUDED.content_hash,
                metadata = EXCLUDED.metadata,
                updated_at = now()
            """.trimIndent(),
            document.workspaceId,
            document.chunkingProfile,
            document.documentId,
            document.rootPath,
            document.sourcePath,
            document.sourceType.name.lowercase(),
            document.contentHash,
            objectMapper.writeValueAsString(document.metadata),
        )
    }

    fun delete(
        workspaceId: String,
        chunkingProfile: String,
        documentId: String,
    ): Boolean =
        jdbcTemplate.update(
            "DELETE FROM $schema.source_documents WHERE workspace_id = ? AND chunking_profile = ? AND doc_id = ?",
            workspaceId,
            chunkingProfile,
            documentId,
        ) == 1

    private fun mapSourceDocument(
        resultSet: java.sql.ResultSet,
        @Suppress("UNUSED_PARAMETER")
        rowNumber: Int,
    ): SourceDocument {
        val metadata =
            objectMapper.readValue(
                resultSet.getString("metadata"),
                object : TypeReference<Map<String, Any?>>() {},
            )
        return SourceDocument(
            workspaceId = resultSet.getString("workspace_id"),
            chunkingProfile = resultSet.getString("chunking_profile"),
            documentId = resultSet.getString("doc_id"),
            rootPath = resultSet.getString("root_path"),
            sourcePath = resultSet.getString("source_path"),
            sourceType = SourceType.valueOf(resultSet.getString("source_type").uppercase()),
            contentHash = resultSet.getString("content_hash"),
            metadata = metadata,
        )
    }
}

@Repository
class JdbcChunkRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    properties: AppProperties,
) {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)
    private val chunkTable = requireIdentifier("PG_CHUNK_TABLE", properties.database.chunkTable)
    private val dimensions = properties.database.vectorDimension

    fun find(id: UUID): StoredChunk? =
        jdbcTemplate
            .query(
                "SELECT id, content, metadata, source, page_number, chunk_index FROM $schema.$chunkTable WHERE id = ?",
                ::mapChunk,
                id,
            ).firstOrNull()

    fun upsert(
        chunk: StoredChunk,
        embedding: List<Float>,
    ) {
        require(embedding.size == dimensions) { "Chunk embedding dimension does not match the configured model profile" }
        jdbcTemplate.update(
            """
            INSERT INTO $schema.$chunkTable (id, content, metadata, embedding, source, page_number, chunk_index)
            VALUES (?, ?, ?::jsonb, ?::vector, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
            SET content = EXCLUDED.content,
                metadata = EXCLUDED.metadata,
                embedding = EXCLUDED.embedding,
                source = EXCLUDED.source,
                page_number = EXCLUDED.page_number,
                chunk_index = EXCLUDED.chunk_index
            """.trimIndent(),
            chunk.id,
            chunk.content,
            objectMapper.writeValueAsString(chunk.metadata),
            PostgresInteropCodec.vectorLiteral(embedding),
            chunk.source,
            chunk.pageNumber,
            chunk.chunkIndex,
        )
    }

    fun delete(id: UUID): Boolean = jdbcTemplate.update("DELETE FROM $schema.$chunkTable WHERE id = ?", id) == 1

    private fun mapChunk(
        resultSet: java.sql.ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): StoredChunk =
        StoredChunk(
            id = resultSet.getObject("id", UUID::class.java),
            content = resultSet.getString("content"),
            metadata = objectMapper.readValue(resultSet.getString("metadata"), object : TypeReference<Map<String, Any?>>() {}),
            source = resultSet.getString("source"),
            pageNumber = resultSet.getObject("page_number", Int::class.java),
            chunkIndex = resultSet.getObject("chunk_index", Int::class.java),
        )
}

@Repository
class JdbcDocumentAssetRepository(
    private val jdbcTemplate: JdbcTemplate,
    properties: AppProperties,
) {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)

    fun upsert(asset: StoredDocumentAsset) {
        require(asset.byteSize >= 0) { "Asset byte size must not be negative" }
        jdbcTemplate.update(
            """
            INSERT INTO $schema.document_assets
                (asset_id, workspace_id, chunking_profile, doc_id, storage_key, content_hash, media_type,
                 byte_size, original_name, relationship_id, ordinal, width, height, alt_text, caption, anchor_block_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (asset_id) DO UPDATE
            SET storage_key = EXCLUDED.storage_key,
                content_hash = EXCLUDED.content_hash,
                media_type = EXCLUDED.media_type,
                byte_size = EXCLUDED.byte_size,
                original_name = EXCLUDED.original_name,
                relationship_id = EXCLUDED.relationship_id,
                ordinal = EXCLUDED.ordinal,
                width = EXCLUDED.width,
                height = EXCLUDED.height,
                alt_text = EXCLUDED.alt_text,
                caption = EXCLUDED.caption,
                anchor_block_id = EXCLUDED.anchor_block_id
            """.trimIndent(),
            asset.assetId,
            asset.workspaceId,
            asset.chunkingProfile,
            asset.documentId,
            asset.storageKey,
            asset.contentHash,
            asset.mediaType,
            asset.byteSize,
            asset.originalName,
            asset.relationshipId,
            asset.ordinal,
            asset.width,
            asset.height,
            asset.altText,
            asset.caption,
            asset.anchorBlockId,
        )
    }

    fun linkToChunk(
        workspaceId: String,
        chunkingProfile: String,
        documentId: String,
        chunkId: String,
        assetId: String,
        displayOrder: Int,
    ) {
        require(displayOrder >= 0) { "Asset display order must not be negative" }
        jdbcTemplate.update(
            """
            INSERT INTO $schema.chunk_assets (workspace_id, chunking_profile, doc_id, chunk_id, asset_id, display_order)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (workspace_id, chunking_profile, chunk_id, asset_id) DO UPDATE
            SET display_order = EXCLUDED.display_order
            """.trimIndent(),
            workspaceId,
            chunkingProfile,
            documentId,
            chunkId,
            assetId,
            displayOrder,
        )
    }

    fun deleteForDocument(
        workspaceId: String,
        chunkingProfile: String,
        documentId: String,
    ): Int =
        jdbcTemplate.update(
            "DELETE FROM $schema.document_assets WHERE workspace_id = ? AND chunking_profile = ? AND doc_id = ?",
            workspaceId,
            chunkingProfile,
            documentId,
        )
}

private fun requireIdentifier(
    propertyName: String,
    value: String,
): String {
    require(Regex("^[a-z_][a-z0-9_]*$").matches(value)) {
        "$propertyName must be a lowercase PostgreSQL identifier; received '$value'."
    }
    return value
}
