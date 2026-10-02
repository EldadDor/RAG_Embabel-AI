package com.dex.ragpoc.providers

import com.dex.ragpoc.catalog.DisplayMetadata
import com.dex.ragpoc.catalog.JdbcDocumentPublicationRepository
import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceDocument
import com.dex.ragpoc.domain.SourceType
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcEmbeddingCacheRepository
import com.dex.ragpoc.persistence.JdbcModelProfileRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import com.dex.ragpoc.persistence.PostgresInteropCodec
import com.dex.ragpoc.persistence.StoredChunk
import com.dex.ragpoc.persistence.requireIdentifier
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import javax.sql.DataSource

data class DocumentWarmResult(
    val profileName: String,
    val sourceModelProfile: String,
    val workspaceId: String,
    val chunks: Int,
    val cacheHits: Int,
    val providerCalls: Int,
    val dryRun: Boolean,
)

/** Corpus warming is separate from the existing provider probe and never re-chunks source files. */
@Service
class DocumentProfileWarmer(
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
    private val properties: AppProperties,
    private val embeddingModel: EmbeddingModel,
) {
    fun warm(
        targetName: String,
        sourceName: String,
        workspace: String,
        dryRun: Boolean,
    ): DocumentWarmResult {
        require(targetName != sourceName)
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Warming must prepare embeddings outside a transaction" }
        // Every adapter uses this connection while session locks are held. A second
        // pooled connection could wait on our own lock and exhaust the pool.
        dataSource.connection.use { connection ->
            val pinned = SingleConnectionDataSource(connection, true)
            val jdbc = JdbcTemplate(pinned)
            val profiles = JdbcModelProfileRepository(jdbc, properties)
            val cache = JdbcEmbeddingCacheRepository(jdbc, NamedParameterJdbcTemplate(jdbc), properties)
            val publication = JdbcDocumentPublicationRepository(jdbc, properties)
            val keys = listOf(targetName, sourceName).sorted().map(publication::profileKey)
            val held = mutableListOf<String>()
            var operationFailure: Throwable? = null
            try {
                keys.forEach { key ->
                    jdbc.query("SELECT pg_advisory_lock(hashtextextended(?,0))", { _, _ -> Unit }, key)
                    held += key
                }
                val target = requireNotNull(profiles.get(targetName)) { "Unknown target profile" }
                val source = requireNotNull(profiles.get(sourceName)) { "Unknown source profile" }
                require(target.status in setOf("draft", "warming", "ready") && source.status == "ready")
                require(target.provider == properties.embedding.provider && target.dimensions > 0)
                require(target.storageTarget != source.storageTarget) { "Profile storage targets must be isolated" }
                val documents = loadDocuments(jdbc, workspace, sourceName, source.storageTarget)
                val chunkRows = documents.flatMap { it.chunks }
                val cacheKeys =
                    chunkRows.map {
                        PostgresInteropCodec.embeddingCacheKey(
                            target,
                            target.documentPrefix + it.content,
                            "document",
                        )
                    }
                val cached = if (properties.embedding.cacheEnabled) cache.containsMany(cacheKeys, target.dimensions) else emptySet()
                val result =
                    DocumentWarmResult(
                        targetName,
                        sourceName,
                        workspace,
                        chunkRows.size,
                        cacheKeys.count { it in cached },
                        (cacheKeys.toSet() - cached).size,
                        dryRun,
                    )
                if (dryRun) return result
                val targetTable = requireIdentifier("target storage", target.storageTarget)
                require(
                    jdbc.queryForObject(
                        "SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid=to_regclass(?) AND attname='embedding' AND NOT attisdropped",
                        String::class.java,
                        "${properties.database.schema}.$targetTable",
                    ) == "vector(${target.dimensions})",
                )
                profiles.setStatus(targetName, "warming")
                val embeddings = ProfiledEmbeddingService(profiles, cache, embeddingModel, properties)
                val chunks = JdbcChunkRepository(jdbc, mapper, properties)
                val sources = JdbcSourceDocumentRepository(jdbc, mapper, properties)
                val transactions = TransactionTemplate(DataSourceTransactionManager(pinned))
                documents.forEach { item ->
                    val vectors = item.chunks.map { embeddings.documentForWarming(target, it.content) }
                    transactions.executeWithoutResult {
                        publication.lockProfile(targetName)
                        publication.lockDocument(workspace, item.chunking, item.document.documentId)
                        chunks.deleteForDocument(workspace, item.chunking, item.document.documentId, targetTable)
                        item.chunks.zip(vectors).forEach { (chunk, vector) -> chunks.upsert(chunk, vector, targetTable, target.dimensions) }
                        sources.upsert(
                            SourceDocument(
                                workspace,
                                item.chunking,
                                item.document.documentId,
                                item.rootPath,
                                item.document.sourcePath,
                                item.document.sourceType,
                                item.document.contentHash.orEmpty(),
                            ),
                            preserveExisting = true,
                        )
                        publication.publish(
                            workspace,
                            targetName,
                            item.chunking,
                            targetTable,
                            item.document,
                            item.rootPath,
                            preserveTime = true,
                            sourceTime = item.time,
                            displayOverride = item.display,
                        )
                        publication.replaceAssetReferences(workspace, targetName, item.chunking, item.document.documentId, item.assets)
                    }
                }
                profiles.setStatus(targetName, "ready")
                return result
            } catch (error: Throwable) {
                operationFailure = error
                throw error
            } finally {
                // Failure intentionally leaves a partially warmed profile in warming state.
                var unlockFailure: Throwable? = null
                held.asReversed().forEach { key ->
                    try {
                        jdbc.query("SELECT pg_advisory_unlock(hashtextextended(?,0))", { _, _ -> Unit }, key)
                    } catch (error: Throwable) {
                        if (unlockFailure == null) unlockFailure = error else unlockFailure!!.addSuppressed(error)
                    }
                }
                unlockFailure?.let { error ->
                    // Never return a session with leaked advisory locks to a connection pool.
                    try {
                        connection.abort { command -> command.run() }
                    } catch (abortError: Throwable) {
                        error.addSuppressed(abortError)
                    }
                    if (operationFailure != null) operationFailure!!.addSuppressed(error) else throw error
                }
            }
        }
    }

    private data class WarmDocument(
        val document: Document,
        val chunking: String,
        val rootPath: String?,
        val time: Instant?,
        val display: DisplayMetadata,
        val chunks: MutableList<StoredChunk> = mutableListOf(),
        val assets: MutableList<String> = mutableListOf(),
    )

    private fun loadDocuments(
        jdbc: JdbcTemplate,
        workspace: String,
        model: String,
        storage: String,
    ): List<WarmDocument> {
        val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)
        val table = requireIdentifier("source storage", storage)
        val rows =
            jdbc.query(
                "SELECT * FROM $schema.document_index_metadata WHERE workspace_id=? AND model_profile=? ORDER BY chunking_profile,doc_id COLLATE \"C\"",
                { rs, _ ->
                    WarmDocument(
                        Document(
                            rs.getString("doc_id"),
                            rs.getString("source_path"),
                            sourceType(rs.getString("document_type")),
                            "",
                            title = rs.getString("title"),
                            contentHash = rs.getString("content_hash"),
                        ),
                        rs.getString("chunking_profile"),
                        rs.getString("root_path"),
                        rs.getTimestamp("last_ingested_at")?.toInstant(),
                        DisplayMetadata(rs.getString("title"), rs.getString("file_name"), rs.getString("document_type")),
                    )
                },
                workspace,
                model,
            )
        val documents = rows.associateByTo(linkedMapOf()) { it.chunking to it.document.documentId }
        val chunks =
            jdbc.query(
                "SELECT id,content,metadata,source,page_number,chunk_index FROM $schema.$table WHERE metadata->>'workspace_id'=? ORDER BY id",
                { rs, _ ->
                    StoredChunk(
                        rs.getObject("id", java.util.UUID::class.java),
                        rs.getString("content"),
                        mapper.readValue(rs.getString("metadata"), object : TypeReference<Map<String, Any?>>() {}),
                        rs.getString("source"),
                        rs.getObject("page_number", Int::class.javaObjectType),
                        rs.getObject("chunk_index", Int::class.javaObjectType),
                    )
                },
                workspace,
            )
        chunks.forEach { chunk ->
            val id = (chunk.metadata["doc_id"] ?: chunk.metadata["document_id"]) as? String
            require(!id.isNullOrEmpty()) { "Source chunk has no identity" }
            val chunking = chunk.metadata["chunking_profile"] as? String ?: "default"
            val item =
                documents.getOrPut(chunking to id) {
                    val document =
                        Document(
                            id,
                            chunk.source ?: chunk.metadata["source_path"] as? String ?: "Untitled document",
                            sourceType(chunk.metadata["source_type"] as? String ?: "unknown"),
                            "",
                            title = chunk.metadata["title"] as? String,
                        )
                    WarmDocument(
                        document,
                        chunking,
                        null,
                        null,
                        com.dex.ragpoc.catalog.DocumentDisplay
                            .from(document),
                    )
                }
            item.chunks += chunk.copy(metadata = chunk.metadata + mapOf("doc_id" to id, "chunking_profile" to chunking))
        }
        documents.values.forEach { item ->
            val ids =
                jdbc
                    .query(
                        "SELECT asset_id FROM $schema.document_index_assets WHERE workspace_id=? AND model_profile=? AND chunking_profile=? AND doc_id=?",
                        { rs, _ -> rs.getString(1) },
                        workspace,
                        model,
                        item.chunking,
                        item.document.documentId,
                    ).toMutableSet()
            item.chunks.forEach { chunk ->
                ids += (chunk.metadata["related_asset_ids"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                val chunkId = chunk.metadata["chunk_id"] as? String ?: chunk.id.toString()
                ids +=
                    jdbc.query(
                        "SELECT asset_id FROM $schema.chunk_assets WHERE workspace_id=? AND chunking_profile=? AND chunk_id=?",
                        { rs, _ -> rs.getString(1) },
                        workspace,
                        item.chunking,
                        chunkId,
                    )
            }
            ids.forEach { id ->
                require(
                    jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM $schema.document_assets WHERE workspace_id=? AND chunking_profile=? AND doc_id=? AND asset_id=?)",
                        Boolean::class.java,
                        workspace,
                        item.chunking,
                        item.document.documentId,
                        id,
                    ) == true,
                ) { "Source asset is unavailable" }
            }
            item.assets += ids.sorted()
        }
        return documents.values.toList()
    }

    private fun sourceType(value: String): SourceType = SourceType.entries.firstOrNull { it.name.equals(value, true) } ?: SourceType.UNKNOWN
}
