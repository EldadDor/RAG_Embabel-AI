package com.dex.ragpoc.config

import org.springframework.context.annotation.Configuration

/**
 * Embedding configuration.
 * Spring AI auto-configures the EmbeddingModel bean through the selected provider starter.
 * Additional embedding tuning (e.g., dimensions, batch size) goes here.
 */
@Configuration
class EmbeddingConfig
