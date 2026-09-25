package com.dex.ragpoc.persistence

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationSummary
import com.dex.ragpoc.domain.ConversationTurn
import com.dex.ragpoc.domain.ModelProfile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

data class AuthorizedWorkspace(
    val workspaceId: String,
    val displayName: String,
    val role: String,
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

private fun requireIdentifier(
    propertyName: String,
    value: String,
): String {
    require(Regex("^[a-z_][a-z0-9_]*$").matches(value)) {
        "$propertyName must be a lowercase PostgreSQL identifier; received '$value'."
    }
    return value
}
