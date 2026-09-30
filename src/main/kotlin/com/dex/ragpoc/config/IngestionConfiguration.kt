package com.dex.ragpoc.config

import com.dex.ragpoc.ingestion.ContentAddressedAssetStore
import com.dex.ragpoc.ingestion.DocumentIngestionService
import com.dex.ragpoc.ingestion.EmbeddingGateway
import com.dex.ragpoc.ingestion.IngestionModelTarget
import com.dex.ragpoc.ingestion.IngestionModelTargetResolver
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import com.dex.ragpoc.providers.ProfiledEmbeddingService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class IngestionConfiguration {
    @Bean
    fun documentLoaderRegistry(): DocumentLoaderRegistry = DocumentLoaderRegistry()

    @Bean
    fun documentChunker(): DocumentChunker = DocumentChunker()

    @Bean
    fun documentIngestionService(
        loaders: DocumentLoaderRegistry,
        chunker: DocumentChunker,
        embeddings: EmbeddingGateway,
        assetStore: ContentAddressedAssetStore,
        sourceDocuments: JdbcSourceDocumentRepository,
        chunks: JdbcChunkRepository,
        assets: JdbcDocumentAssetRepository,
        properties: AppProperties,
        transactions: TransactionTemplate,
        telemetry: RagTelemetry,
        profiles: ProfiledEmbeddingService,
    ): DocumentIngestionService =
        DocumentIngestionService(
            loaders,
            chunker,
            embeddings,
            assetStore,
            sourceDocuments,
            chunks,
            assets,
            properties,
            transactions,
            telemetry,
            IngestionModelTargetResolver { name ->
                profiles.resolveReady(name).let { IngestionModelTarget(it.storageTarget, it.dimensions) }
            },
        )
}
