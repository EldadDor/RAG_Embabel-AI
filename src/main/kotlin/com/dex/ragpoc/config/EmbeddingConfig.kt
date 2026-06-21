package com.dex.ragpoc.config

import org.springframework.context.annotation.Configuration

/**
 * Embedding configuration.
 * Spring AI auto-configures the EmbeddingModel bean via spring-ai-openai-spring-boot-starter.
 * Additional embedding tuning (e.g., dimensions, batch size) goes here.
 */
@Configuration
class EmbeddingConfig
