package com.dex.ragpoc.retrieval

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.RetrievedChunk
import com.dex.ragpoc.persistence.PostgresInteropCodec
import com.dex.ragpoc.persistence.requireIdentifier
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcRetrievalRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    properties: AppProperties,
) : RetrievalRepository {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)

    override fun semantic(
        embedding: List<Float>,
        workspaceId: String,
        chunkingProfile: String,
        storageTarget: String,
        limit: Int,
    ): List<RetrievedChunk> =
        query(
            storageTarget,
            "1 - (embedding <=> ?::vector)",
            PostgresInteropCodec.vectorLiteral(embedding),
            workspaceId,
            chunkingProfile,
            limit,
        )

    override fun lexical(
        query: String,
        workspaceId: String,
        chunkingProfile: String,
        storageTarget: String,
        limit: Int,
    ): List<RetrievedChunk> =
        query(
            storageTarget,
            "ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?))",
            query,
            workspaceId,
            chunkingProfile,
            limit,
            lexical = true,
        )

    private fun query(
        storageTarget: String,
        scoreSql: String,
        scoreArgument: Any,
        workspaceId: String,
        chunkingProfile: String,
        limit: Int,
        lexical: Boolean = false,
    ): List<RetrievedChunk> {
        val table = requireIdentifier("model profile storage target", storageTarget)
        val predicate = if (lexical) "AND to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)" else ""
        val args =
            if (lexical) {
                arrayOf(
                    scoreArgument,
                    workspaceId,
                    chunkingProfile,
                    scoreArgument,
                    limit,
                )
            } else {
                arrayOf(scoreArgument, workspaceId, chunkingProfile, limit)
            }
        return jdbc.query(
            """
            SELECT id, content, metadata, source, page_number, $scoreSql AS score
            FROM $schema.$table
            WHERE metadata ->> 'workspace_id' = ? AND metadata ->> 'chunking_profile' = ? $predicate
            ORDER BY score DESC, id ASC LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val metadata = objectMapper.readValue(rs.getString("metadata"), object : TypeReference<Map<String, Any?>>() {})
                RetrievedChunk(
                    chunkId = metadata["chunk_id"]?.toString() ?: rs.getObject("id").toString(),
                    documentId = (metadata["doc_id"] ?: metadata["document_id"])?.toString().orEmpty(),
                    sourcePath = metadata["source_path"]?.toString() ?: rs.getString("source").orEmpty(),
                    text = rs.getString("content"),
                    score = rs.getDouble("score"),
                    title = metadata["title"]?.toString(),
                    page = rs.getObject("page_number", Int::class.javaObjectType),
                    section = metadata["section"]?.toString(),
                )
            },
            *args,
        )
    }
}
