package com.dex.ragpoc.domain

enum class SourceType {
    MARKDOWN,
    HTML,
    PDF,
    WORD,
    TEXT,
    CODE,
    UNKNOWN,
}

data class Document(
    val documentId: String,
    val sourcePath: String,
    val sourceType: SourceType,
    val content: String,
    val title: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val contentHash: String? = null,
    val assets: List<DocumentAsset> = emptyList(),
)

data class DocumentAsset(
    val anchorId: String,
    val relationshipId: String,
    val content: ByteArray,
    val contentHash: String,
    val mediaType: String = "application/octet-stream",
    val originalName: String? = null,
    val ordinal: Int = 0,
    val blockId: String? = null,
    val blockOrdinal: Int? = null,
    val sourceIndex: Int? = null,
    val section: String? = null,
    val altText: String? = null,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class Chunk(
    val chunkId: String,
    val documentId: String,
    val sourcePath: String,
    val sourceType: SourceType,
    val text: String,
    val chunkIndex: Int,
    val title: String? = null,
    val page: Int? = null,
    val section: String? = null,
)

data class RetrievedChunk(
    val chunkId: String,
    val documentId: String,
    val sourcePath: String,
    val text: String,
    val score: Double,
    val title: String? = null,
    val page: Int? = null,
    val section: String? = null,
    val relatedAssetIds: List<String> = emptyList(),
)

data class SourceDocument(
    val workspaceId: String,
    val chunkingProfile: String,
    val documentId: String,
    val rootPath: String?,
    val sourcePath: String,
    val sourceType: SourceType,
    val contentHash: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class ModelProfile(
    val profileName: String,
    val provider: String,
    val model: String,
    val dimensions: Int,
    val storageTarget: String,
    val queryPrefix: String = "",
    val documentPrefix: String = "",
    val status: String = "ready",
)

data class ChatSession(
    val sessionId: String,
    val ownerId: String,
    val workspaceId: String,
    val title: String,
    val lastPreview: String? = null,
    val archived: Boolean = false,
)

data class ConversationTurn(
    val id: Long,
    val sessionId: String,
    val role: String,
    val content: String,
)

data class ConversationSummary(
    val sessionId: String,
    val summary: String,
    val lastTurnId: Long,
)
