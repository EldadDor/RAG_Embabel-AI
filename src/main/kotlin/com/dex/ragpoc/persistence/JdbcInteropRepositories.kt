package com.dex.ragpoc.persistence

import com.dex.ragpoc.config.AppProperties
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

private fun requireIdentifier(
    propertyName: String,
    value: String,
): String {
    require(Regex("^[a-z_][a-z0-9_]*$").matches(value)) {
        "$propertyName must be a lowercase PostgreSQL identifier; received '$value'."
    }
    return value
}
