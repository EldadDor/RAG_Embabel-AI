package com.dex.ragpoc.config

import com.dex.ragpoc.ingestion.EmbeddingGateway
import com.dex.ragpoc.providers.ProfiledEmbeddingService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Embedding configuration.
 * Spring AI auto-configures the EmbeddingModel bean through the selected provider starter.
 * Additional embedding tuning (e.g., dimensions, batch size) goes here.
 */
@Configuration
class EmbeddingConfig {
    @Bean
    fun embeddingGateway(
        properties: AppProperties,
        embeddings: ProfiledEmbeddingService,
    ): EmbeddingGateway = EmbeddingGateway { texts -> texts.map { embeddings.document(properties.rag.modelProfile, it) } }
}
