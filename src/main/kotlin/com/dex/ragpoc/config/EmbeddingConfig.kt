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
    fun embeddingGateway(embeddings: ProfiledEmbeddingService): EmbeddingGateway =
        EmbeddingGateway { profile, texts -> texts.map { embeddings.document(profile, it) } }
}
