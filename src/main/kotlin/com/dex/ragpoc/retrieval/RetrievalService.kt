package com.dex.ragpoc.retrieval

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.domain.RetrievedChunk
import com.dex.ragpoc.providers.ProfiledEmbeddingService
import org.springframework.stereotype.Service

/** Narrow persistence boundary for profile-scoped hybrid retrieval. */
interface RetrievalRepository {
    fun semantic(
        embedding: List<Float>,
        workspaceId: String,
        chunkingProfile: String,
        storageTarget: String,
        limit: Int,
    ): List<RetrievedChunk>

    fun lexical(
        query: String,
        workspaceId: String,
        chunkingProfile: String,
        storageTarget: String,
        limit: Int,
    ): List<RetrievedChunk>
}

data class RetrievalResult(
    val chunks: List<RetrievedChunk>,
    val modelProfile: ModelProfile,
)

/** Optional local reranking boundary; absence intentionally preserves fused order. */
fun interface Reranker {
    fun rerank(
        question: String,
        candidates: List<RetrievedChunk>,
    ): List<RetrievedChunk>
}

@Service
class RetrievalService(
    private val embeddings: ProfiledEmbeddingService,
    private val repository: RetrievalRepository,
    private val properties: AppProperties,
    private val reranker: Reranker? = null,
) {
    fun retrieve(
        question: String,
        workspaceId: String,
        chunkingProfile: String = properties.rag.defaultChunkingProfile,
        modelProfile: String = properties.rag.modelProfile,
        topK: Int = properties.rag.topK,
    ): RetrievalResult {
        require(question.isNotBlank()) { "Question must not be blank" }
        require(topK in 1..20) { "topK must be between 1 and 20" }
        val profile = embeddings.resolveReady(modelProfile)
        val limit = maxOf(properties.rag.retrievalCandidateK, if (properties.rag.rerankEnabled) properties.rag.rerankCandidateK else 0)
        val semantic =
            repository
                .semantic(embeddings.query(modelProfile, question), workspaceId, chunkingProfile, profile.storageTarget, limit)
                .filter { it.score >= properties.rag.minRetrievalScore }
        val candidates =
            if (properties.rag.hybridSearchEnabled) {
                reciprocalRankFusion(semantic, repository.lexical(question, workspaceId, chunkingProfile, profile.storageTarget, limit))
            } else {
                semantic
            }
        val ordered = if (properties.rag.rerankEnabled) reranker?.rerank(question, candidates) ?: candidates else candidates
        return RetrievalResult(ordered.take(topK), profile)
    }

    private fun reciprocalRankFusion(
        semantic: List<RetrievedChunk>,
        lexical: List<RetrievedChunk>,
    ): List<RetrievedChunk> {
        val chunks = linkedMapOf<String, RetrievedChunk>()
        val scores = linkedMapOf<String, Double>()
        listOf(semantic, lexical).forEach { ranking ->
            ranking.forEachIndexed { index, chunk ->
                chunks.putIfAbsent(chunk.chunkId, chunk)
                scores[chunk.chunkId] = (scores[chunk.chunkId] ?: 0.0) + 1.0 / (properties.rag.rrfK + index + 1)
            }
        }
        return chunks.values.sortedWith(compareByDescending<RetrievedChunk> { scores[it.chunkId] }.thenBy { it.chunkId })
    }
}
