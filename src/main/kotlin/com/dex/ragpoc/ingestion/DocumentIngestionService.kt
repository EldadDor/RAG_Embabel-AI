package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.config.RagOperation
import com.dex.ragpoc.config.RagTelemetry
import com.dex.ragpoc.domain.Chunk
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceDocument
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.parsing.DocumentTooLargeException
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import com.dex.ragpoc.persistence.PostgresInteropCodec
import com.dex.ragpoc.persistence.StoredChunk
import com.dex.ragpoc.persistence.StoredDocumentAsset
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs

fun interface EmbeddingGateway {
    fun embed(
        modelProfile: String,
        texts: List<String>,
    ): List<List<Float>>
}

data class IngestionModelTarget(
    val storageTarget: String,
    val dimensions: Int,
)

fun interface IngestionModelTargetResolver {
    fun resolve(modelProfile: String): IngestionModelTarget
}

class DocumentLoadFailure(
    cause: Exception,
) : IllegalArgumentException("Could not load source", cause)

class IngestionProviderException(
    cause: RuntimeException,
) : RuntimeException("The embedding provider could not complete the request", cause)

data class IngestionRequest(
    val workspaceId: String,
    val sourcePath: Path,
    val rootPath: Path? = null,
    val chunkingProfile: String = "default",
    val modelProfile: String? = null,
    val dryRun: Boolean = false,
)

data class IngestionResult(
    val documentId: String,
    val changed: Boolean,
    val dryRun: Boolean,
    val chunkCount: Int,
    val assetCount: Int,
    val skipReason: String? = null,
)

data class CleanupResult(
    val deletedDocumentIds: List<String>,
    val dryRun: Boolean,
)

data class IngestionDocumentResult(
    val documentId: String?,
    val sourcePath: String,
    val chunksIndexed: Int,
    val skipped: Boolean,
    val skipReason: String? = null,
    val assetsFound: Int = 0,
)

data class IngestionBatchResult(
    val indexed: Int,
    val chunkingProfile: String,
    val modelProfile: String,
    val dryRun: Boolean,
    val documents: List<IngestionDocumentResult>,
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
    private val transactions: TransactionTemplate,
    private val telemetry: RagTelemetry? = null,
    private val modelTargets: IngestionModelTargetResolver =
        IngestionModelTargetResolver { IngestionModelTarget(properties.database.chunkTable, properties.database.vectorDimension) },
) {
    private val logger = LoggerFactory.getLogger(DocumentIngestionService::class.java)

    fun ingestPath(
        request: IngestionRequest,
        recursive: Boolean = false,
    ): IngestionBatchResult {
        val started = System.nanoTime()
        val source = request.sourcePath
        val directory = Files.isDirectory(source)
        val paths = if (directory) loaderRegistry.scanDirectory(source, recursive) else listOf(source)
        val realRoot = if (directory) source.toRealPath() else null
        val modelProfile = request.modelProfile ?: properties.rag.modelProfile
        logger.info(
            "Ingestion batch started: sourceKind={}, candidateFiles={}, recursive={}, dryRun={}, chunkingProfile={}, modelProfile={}",
            if (directory) "directory" else "file",
            paths.size,
            recursive,
            request.dryRun,
            request.chunkingProfile,
            modelProfile,
        )
        val documents =
            paths.map { path ->
                if (realRoot != null && !pathIsInside(path, realRoot)) {
                    IngestionDocumentResult(null, path.toString(), 0, true, "Source is outside the selected directory")
                } else {
                    try {
                        val result = ingest(request.copy(sourcePath = path, rootPath = if (directory) source else request.rootPath))
                        IngestionDocumentResult(
                            result.documentId,
                            path.toString(),
                            result.chunkCount,
                            !result.changed || result.skipReason != null,
                            result.skipReason ?: if (result.changed) null else "unchanged",
                            result.assetCount,
                        )
                    } catch (error: DocumentLoadFailure) {
                        if (!directory) throw error
                        logger.warn("Ingestion source skipped: reason=unreadable")
                        IngestionDocumentResult(null, path.toString(), 0, true, "Could not read source")
                    } catch (error: DocumentTooLargeException) {
                        if (!directory) throw error
                        logger.warn("Ingestion source skipped: reason=too_large")
                        IngestionDocumentResult(null, path.toString(), 0, true, "Source is too large")
                    }
                }
            }
        val indexed = documents.sumOf(IngestionDocumentResult::chunksIndexed)
        val skipped = documents.count(IngestionDocumentResult::skipped)
        logger.info(
            "Ingestion batch completed: documents={}, indexedChunks={}, skippedDocuments={}, dryRun={}, durationMs={}",
            documents.size,
            indexed,
            skipped,
            request.dryRun,
            (System.nanoTime() - started) / 1_000_000,
        )
        return IngestionBatchResult(
            indexed,
            request.chunkingProfile,
            modelProfile,
            request.dryRun,
            documents,
        )
    }

    private fun pathIsInside(
        path: Path,
        root: Path,
    ): Boolean =
        try {
            path.toRealPath().startsWith(root)
        } catch (error: java.io.IOException) {
            false
        }

    fun ingest(request: IngestionRequest): IngestionResult =
        try {
            telemetry?.observe(RagOperation.INGESTION) { ingestPrepared(request) } ?: ingestPrepared(request)
        } catch (error: RuntimeException) {
            telemetry?.count("rag_ingestion_failures_total")
            logger.warn("Ingestion failed: errorType={}", error.javaClass.simpleName)
            throw error
        }

    private fun ingestPrepared(request: IngestionRequest): IngestionResult {
        val modelProfile = request.modelProfile ?: properties.rag.modelProfile
        val loadStarted = System.nanoTime()
        val loaded =
            try {
                telemetry?.observe(RagOperation.LOADER) { loaderRegistry.loadDocument(request.sourcePath) }
                    ?: loaderRegistry.loadDocument(request.sourcePath)
            } catch (error: DocumentTooLargeException) {
                throw error
            } catch (error: Exception) {
                throw DocumentLoadFailure(error)
            }
        logger.info(
            "Ingestion source loaded: sourceType={}, contentCharacters={}, durationMs={}",
            loaded.sourceType.name.lowercase(),
            loaded.content.length,
            (System.nanoTime() - loadStarted) / 1_000_000,
        )
        val document = loaded.copy(contentHash = loaded.contentHash ?: sha256(Files.readAllBytes(request.sourcePath)))
        val chunkStarted = System.nanoTime()
        val preparedChunks =
            telemetry?.observe(RagOperation.CHUNKER) { chunker.chunk(document, request.chunkingProfile) }
                ?: chunker.chunk(document, request.chunkingProfile)
        logger.info(
            "Ingestion chunking completed: chunks={}, chunkingProfile={}, durationMs={}",
            preparedChunks.size,
            request.chunkingProfile,
            (System.nanoTime() - chunkStarted) / 1_000_000,
        )
        assetStore.validate(document.assets)
        if (request.dryRun) {
            logger.info("Ingestion dry run completed: chunks={}, assets={}", preparedChunks.size, document.assets.size)
            return IngestionResult(
                document.documentId,
                changed = true,
                dryRun = true,
                preparedChunks.size,
                document.assets.size,
                if (preparedChunks.isEmpty()) "empty" else null,
            )
        }
        val modelTarget = modelTargets.resolve(modelProfile)
        val existing = sourceDocuments.find(request.workspaceId, request.chunkingProfile, document.documentId)
        if (existing?.contentHash == document.contentHash && existing?.metadata?.get("model_profile") == modelProfile) {
            telemetry?.count("rag_ingestion_skips_total")
            logger.info("Ingestion skipped unchanged source: modelProfile={}", modelProfile)
            return IngestionResult(document.documentId, changed = false, dryRun = false, 0, document.assets.size, "unchanged")
        }

        val embeddings =
            if (preparedChunks.isEmpty()) {
                emptyList()
            } else {
                try {
                    val embeddingStarted = System.nanoTime()
                    logger.info("Ingestion embedding started: modelProfile={}, chunks={}", modelProfile, preparedChunks.size)
                    embeddingGateway
                        .embed(modelProfile, preparedChunks.map(Chunk::text))
                        .also {
                            logger.info(
                                "Ingestion embedding completed: vectors={}, durationMs={}",
                                it.size,
                                (System.nanoTime() - embeddingStarted) / 1_000_000,
                            )
                        }
                } catch (error: IllegalArgumentException) {
                    throw error
                } catch (error: RuntimeException) {
                    throw IngestionProviderException(error)
                }
            }
        require(embeddings.size == preparedChunks.size) { "Embedding gateway returned an unexpected number of vectors" }
        embeddings.forEach {
            require(
                it.size == modelTarget.dimensions,
            ) { "Embedding dimension does not match configuration" }
        }
        val storedAssets = document.assets.map { it to assetStore.store(it) }
        val persistenceStarted = System.nanoTime()
        transactions.executeWithoutResult { replace(request, document, preparedChunks, embeddings, storedAssets, modelTarget) }
        logger.info(
            "Ingestion persistence completed: chunks={}, assets={}, durationMs={}",
            preparedChunks.size,
            storedAssets.size,
            (System.nanoTime() - persistenceStarted) / 1_000_000,
        )
        telemetry?.count("rag_ingestion_documents_total")
        telemetry?.count("rag_ingestion_chunks_total", preparedChunks.size.toDouble())
        telemetry?.count("rag_ingestion_assets_total", document.assets.size.toDouble())
        if (preparedChunks.isEmpty()) telemetry?.count("rag_ingestion_skips_total")
        return IngestionResult(
            document.documentId,
            changed = true,
            dryRun = false,
            preparedChunks.size,
            document.assets.size,
            if (preparedChunks.isEmpty()) "empty" else null,
        )
    }

    /** Removes only database records whose stored source path is missing beneath the supplied root. */
    fun cleanupMissingSources(
        workspaceId: String,
        rootPath: Path,
        chunkingProfile: String = "default",
        dryRun: Boolean = false,
    ): CleanupResult {
        val normalizedRoot = rootPath.toAbsolutePath().normalize()
        require(
            java.nio.file.Files
                .isDirectory(normalizedRoot),
        ) { "Cleanup root is not a directory: $normalizedRoot" }
        val stale =
            sourceDocuments
                .listForRoot(workspaceId, chunkingProfile, normalizedRoot.toString())
                .filter { document ->
                    val source = Path.of(document.sourcePath).toAbsolutePath().normalize()
                    source.startsWith(normalizedRoot) &&
                        !java.nio.file.Files
                            .isRegularFile(source)
                }
        if (!dryRun) {
            transactions.executeWithoutResult {
                stale.forEach { document ->
                    chunks.deleteForDocument(workspaceId, chunkingProfile, document.documentId)
                    assets.deleteForDocument(workspaceId, chunkingProfile, document.documentId)
                    sourceDocuments.delete(workspaceId, chunkingProfile, document.documentId)
                }
            }
        }
        return CleanupResult(stale.map(SourceDocument::documentId), dryRun)
    }

    private fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private fun replace(
        request: IngestionRequest,
        document: Document,
        preparedChunks: List<Chunk>,
        embeddings: List<List<Float>>,
        storedAssets: List<Pair<com.dex.ragpoc.domain.DocumentAsset, StoredAsset>>,
        modelTarget: IngestionModelTarget,
    ) {
        chunks.deleteForDocument(request.workspaceId, request.chunkingProfile, document.documentId, modelTarget.storageTarget)
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
                document.metadata + mapOf("model_profile" to (request.modelProfile ?: properties.rag.modelProfile)),
            ),
        )
        preparedChunks.zip(embeddings).forEach { (chunk, embedding) ->
            chunks.upsert(
                StoredChunk(
                    id = PostgresInteropCodec.chunkUuid(chunk.chunkId),
                    content = chunk.text,
                    metadata =
                        chunk.metadata +
                            mapOf(
                                "workspace_id" to request.workspaceId,
                                "chunking_profile" to request.chunkingProfile,
                                "document_id" to document.documentId,
                                "chunk_id" to chunk.chunkId,
                            ),
                    source = chunk.sourcePath,
                    pageNumber = chunk.page,
                    chunkIndex = chunk.chunkIndex,
                ),
                embedding,
                modelTarget.storageTarget,
                modelTarget.dimensions,
            )
        }
        storedAssets.forEach { (asset, stored) ->
            val assetId = assetId(request, document, asset)
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
            relatedChunk(asset, preparedChunks)?.let { chunk ->
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

    /** Mirrors Python's cross-runtime asset identity and nearest-chunk association. */
    private fun assetId(
        request: IngestionRequest,
        document: Document,
        asset: com.dex.ragpoc.domain.DocumentAsset,
    ): String =
        sha256(
            "${request.workspaceId}:${request.chunkingProfile}:${document.documentId}:${asset.anchorId}"
                .toByteArray(StandardCharsets.UTF_8),
        )

    private fun relatedChunk(
        asset: com.dex.ragpoc.domain.DocumentAsset,
        preparedChunks: List<Chunk>,
    ): Chunk? {
        if (preparedChunks.isEmpty()) return null
        val candidates =
            if (preparedChunks.first().metadata["document_format"] == "pptx") {
                preparedChunks.filter { it.section == asset.section }
            } else {
                preparedChunks
            }
        val sourceIndex = asset.sourceIndex ?: 0
        return candidates.firstOrNull { chunk ->
            val start = chunk.metadata["start_index"] as? Int
            val end = chunk.metadata["end_index"] as? Int
            start != null && end != null && sourceIndex in start..end
        } ?: candidates.minByOrNull { chunk ->
            abs((chunk.metadata["start_index"] as? Int ?: 0) - sourceIndex)
        }
    }
}
