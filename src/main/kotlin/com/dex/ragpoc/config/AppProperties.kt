package com.dex.ragpoc.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("app")
data class AppProperties(
    val environment: String = "local",
    val auth: Auth = Auth(),
    val database: Database = Database(),
    val rag: Rag = Rag(),
    val assets: Assets = Assets(),
    val memory: Memory = Memory(),
    val observability: Observability = Observability(),
    val telemetry: Telemetry = Telemetry(),
    val chat: Chat = Chat(),
    val embedding: Embedding = Embedding(),
) {
    data class Auth(
        val mode: String = "local",
        val localSubject: String = "local-dev",
        val localDisplayName: String = "Local Developer",
        val localEmail: String? = "dev@localhost",
        val identityHeader: String = "X-Forwarded-User",
        val identityNameHeader: String = "X-Forwarded-Name",
        val identityEmailHeader: String = "X-Forwarded-Email",
    )

    data class Database(
        val vectorStore: String = "postgres",
        val schema: String = "rag",
        val chunkTable: String = "document_chunks_bge_m3",
        val vectorDimension: Int = 1024,
        val sslMode: String = "disable",
        val useEntra: Boolean = false,
    )

    data class Rag(
        val defaultWorkspaceId: String = "local",
        val topK: Int = 5,
        val chunkSize: Int = 800,
        val chunkOverlap: Int = 120,
        val defaultChunkingProfile: String = "default",
        val modelProfile: String = "bge-m3",
        val minRetrievalScore: Double = 0.35,
        val hybridSearchEnabled: Boolean = true,
        val retrievalCandidateK: Int = 20,
        val rrfK: Int = 60,
        val rerankEnabled: Boolean = false,
        val rerankCandidateK: Int = 20,
    )

    data class Assets(
        val storageRoot: Path = Path.of(".rag-assets"),
        val maxImageBytes: Long = 15L * 1024 * 1024,
        val maxImagesPerDocument: Int = 100,
        val maxTotalBytesPerDocument: Long = 100L * 1024 * 1024,
    )

    data class Memory(
        val maxTurns: Int = 10,
        val retentionDays: Int = 90,
        val summaryAfterTurns: Int = 8,
    )

    data class Observability(
        val langfuseEnabled: Boolean = false,
    )

    data class Telemetry(
        val serviceName: String = "rag-embabel-ai",
        val metricsExportEnabled: Boolean = true,
        val metricsEndpoint: String = "http://localhost:4318/v1/metrics",
        val tracesEndpoint: String = "http://localhost:4318/v1/traces",
    )

    data class Chat(
        val provider: String = "openai_compatible",
        val model: String = "dictalm2.0-instruct",
        val timeoutSeconds: Long = 240,
        val maxTokens: Int = 400,
        val think: Boolean = false,
    )

    data class Embedding(
        val provider: String = "ollama",
        val baseUrl: String = "http://10.100.102.12:11434",
        val model: String = "bge-m3:latest",
        val timeoutSeconds: Long = 120,
        val concurrency: Int = 8,
        val cacheEnabled: Boolean = true,
    )
}
