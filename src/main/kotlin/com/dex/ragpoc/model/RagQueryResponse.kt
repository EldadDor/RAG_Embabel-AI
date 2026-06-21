package com.dex.ragpoc.model

data class RagQueryResponse(
    val answer: String?,
    val retrievedChunks: Int = 0,
    val toolsInvoked: List<String> = emptyList(),
    val durationMs: Long = 0,
)
