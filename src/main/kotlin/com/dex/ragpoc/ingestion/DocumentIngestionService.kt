package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Chunk
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceDocument
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import com.dex.ragpoc.persistence.PostgresInteropCodec
import com.dex.ragpoc.persistence.StoredChunk
import com.dex.ragpoc.persistence.StoredDocumentAsset
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

fun interface EmbeddingGateway {
    fun embed(texts: List<String>): List<List<Float>>
}

data class IngestionRequest(
    val workspaceId: String,
    val sourcePath: Path,
    val rootPath: Path? = null,
    val chunkingProfile: String = "default",
    val dryRun: Boolean = false,
)

data class IngestionResult(
    val documentId: String,
    val changed: Boolean,
    val dryRun: Boolean,
    val chunkCount: Int,
    val assetCount: Int,
)

/** Orchestrates parse, pre-transaction embedding, and shared-schema replacement for one source file. */
class DocumentIngestionService(
    private val loaderRegistry: DocumentLoaderRegistry,
    private val chunker: DocumentChunker,
    private val embeddingGateway: EmbeddingGateway,
    private val assetStore: ContentAddressedAssetStore,
    private val sourceDocuments: JdbcSourceDocumentRepository,
    private val chunks: JdbcChunkRepository,
    private val assets: JdbcDocumentAssetRepository,
    private val properties: AppProperties,
) {
    fun ingest(request: IngestionRequest): IngestionResult {
        val loaded = loaderRegistry.loadDocument(request.sourcePath)
        val document = loaded.copy(contentHash = loaded.contentHash ?: sha256(loaded.content))
        val existing = sourceDocuments.find(request.workspaceId, request.chunkingProfile, document.documentId)
        if (existing?.contentHash == document.contentHash) {
            return IngestionResult(document.documentId, changed = false, request.dryRun, 0, 0)
        }
        val preparedChunks = chunker.chunk(document, request.chunkingProfile)
        assetStore.validate(document.assets)
        if (request.dryRun) {
            return IngestionResult(
                document.documentId,
                changed = true,
                dryRun = true,
                preparedChunks.size,
                document.assets.size,
            )
        }

        val embeddings = embeddingGateway.embed(preparedChunks.map(Chunk::text))
        require(embeddings.size == preparedChunks.size) { "Embedding gateway returned an unexpected number of vectors" }
        embeddings.forEach {
            require(
                it.size == properties.database.vectorDimension,
            ) { "Embedding dimension does not match configuration" }
        }
        val storedAssets = document.assets.map { it to assetStore.store(it) }
        replace(request, document, preparedChunks, embeddings, storedAssets)
        return IngestionResult(document.documentId, changed = true, dryRun = false, preparedChunks.size, document.assets.size)
    }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Transactional
    protected fun replace(
        request: IngestionRequest,
        document: Document,
        preparedChunks: List<Chunk>,
        embeddings: List<List<Float>>,
        storedAssets: List<Pair<com.dex.ragpoc.domain.DocumentAsset, StoredAsset>>,
    ) {
        chunks.deleteForDocument(document.documentId)
        assets.deleteForDocument(request.workspaceId, request.chunkingProfile, document.documentId)
        sourceDocuments.upsert(
            SourceDocument(
                request.workspaceId,
                request.chunkingProfile,
                document.documentId,
                request.rootPath
                    ?.toAbsolutePath()
                    ?.normalize()
                    ?.toString(),
                document.sourcePath,
                document.sourceType,
                requireNotNull(document.contentHash) { "Loaded document has no content hash" },
                document.metadata,
            ),
        )
        preparedChunks.zip(embeddings).forEach { (chunk, embedding) ->
            chunks.upsert(
                StoredChunk(
                    id = PostgresInteropCodec.chunkUuid(chunk.chunkId),
                    content = chunk.text,
                    metadata = chunk.metadata + mapOf("document_id" to document.documentId, "chunk_id" to chunk.chunkId),
                    source = chunk.sourcePath,
                    pageNumber = chunk.page,
                    chunkIndex = chunk.chunkIndex,
                ),
                embedding,
            )
        }
        storedAssets.forEach { (asset, stored) ->
            val assetId = "${document.documentId}:${asset.ordinal}:${asset.contentHash}"
            assets.upsert(
                StoredDocumentAsset(
                    assetId,
                    request.workspaceId,
                    request.chunkingProfile,
                    document.documentId,
                    stored.storageKey,
                    asset.contentHash,
                    asset.mediaType,
                    asset.content.size.toLong(),
                    asset.originalName,
                    asset.relationshipId,
                    asset.ordinal,
                    asset.width,
                    asset.height,
                    asset.altText,
                    asset.caption,
                    asset.blockId,
                ),
            )
            preparedChunks.forEach { chunk ->
                val containsAnchor = chunk.metadata["blocks"]?.toString()?.contains(asset.anchorId) == true
                if (containsAnchor || preparedChunks.size == 1) {
                    assets.linkToChunk(
                        request.workspaceId,
                        request.chunkingProfile,
                        document.documentId,
                        chunk.chunkId,
                        assetId,
                        asset.ordinal,
                    )
                }
            }
        }
    }
}
