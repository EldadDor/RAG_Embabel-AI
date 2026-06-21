package com.dex.ragpoc.config

import org.springframework.context.annotation.Configuration

/**
 * VectorStore configuration.
 * Spring AI auto-configures the QdrantVectorStore bean via spring-ai-qdrant-store-spring-boot-starter
 * and the properties in application.yml.
 * Additional collection/schema settings go here if needed.
 */
@Configuration
class VectorStoreConfig
