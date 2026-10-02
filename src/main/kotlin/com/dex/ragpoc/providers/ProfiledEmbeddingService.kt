package com.dex.ragpoc.providers

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.config.RagOperation
import com.dex.ragpoc.config.RagTelemetry
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.persistence.JdbcEmbeddingCacheRepository
import com.dex.ragpoc.persistence.JdbcModelProfileRepository
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Service
import java.security.MessageDigest

class ModelProfileUnavailable(
    message: String,
) : IllegalArgumentException(message)

@Service
class ProfiledEmbeddingService(
    private val profiles: JdbcModelProfileRepository,
    private val cache: JdbcEmbeddingCacheRepository,
    private val embeddingModel: EmbeddingModel,
    private val properties: AppProperties,
    private val telemetry: RagTelemetry? = null,
    private val documentWarmer: DocumentProfileWarmer? = null,
) {
    fun query(
        profileName: String,
        text: String,
    ): List<Float> = embed(resolveReady(profileName), text, "query")

    fun document(
        profileName: String,
        text: String,
    ): List<Float> = embed(resolveReady(profileName), text, "document")

    fun resolveReady(profileName: String): ModelProfile {
        val profile = profiles.get(profileName) ?: throw ModelProfileUnavailable("Unknown model profile: $profileName")
        if (profile.status != "ready") throw ModelProfileUnavailable("Model profile $profileName is not ready")
        validateProfile(profile)
        return profile
    }

    /**
     * Warms a draft or already-warming profile with one document-prefixed probe.
     * It deliberately has no dependency on chunk loading or persistence, so it
     * cannot re-chunk the corpus while a profile is being prepared.
     */
    fun warm(profileName: String): ModelProfile {
        val profile = profiles.get(profileName) ?: throw ModelProfileUnavailable("Unknown model profile: $profileName")
        require(profile.status in setOf("draft", "warming")) {
            "Model profile $profileName must be draft or warming to warm"
        }
        validateProfile(profile)
        profiles.setStatus(profileName, "warming")
        try {
            embed(profile, WARMUP_TEXT, "warmup")
            profiles.setStatus(profileName, "ready")
            return profile.copy(status = "ready")
        } catch (error: RuntimeException) {
            profiles.setStatus(profileName, profile.status)
            throw error
        }
    }

    fun storageTarget(profileName: String): String = resolveReady(profileName).storageTarget

    /** Operator entry point for corpus warming; the single-argument overload remains a provider probe. */
    fun warm(
        targetProfile: String,
        sourceProfile: String,
        workspace: String,
        dryRun: Boolean,
    ): DocumentWarmResult =
        requireNotNull(documentWarmer) { "Document warming is not configured" }.warm(targetProfile, sourceProfile, workspace, dryRun)

    internal fun documentForWarming(
        profile: ModelProfile,
        text: String,
    ): List<Float> {
        validateProfile(profile)
        return embed(profile, text, "document")
    }

    private fun validateProfile(profile: ModelProfile) {
        require(Regex("^[a-z_][a-z0-9_]*$").matches(profile.storageTarget)) {
            "Model profile storage target is invalid"
        }
        require(profile.dimensions > 0) { "Model profile dimensions must be positive" }
        require(providerMatches(profile.provider, properties.embedding.provider)) {
            "Model profile provider ${profile.provider} does not match active embedding provider ${properties.embedding.provider}"
        }
    }

    private fun embed(
        profile: ModelProfile,
        text: String,
        purpose: String,
    ): List<Float> {
        val effective = (if (purpose == "query") profile.queryPrefix else profile.documentPrefix) + text
        val key = cacheKey(profile, effective, purpose)
        if (properties.embedding.cacheEnabled) {
            cache.get(key, profile.dimensions)?.let {
                telemetry?.count("rag_embedding_cache_hits_total")
                return it
            }
        }
        val embedding =
            telemetry?.observe(RagOperation.EMBEDDING) { embeddingModel.embed(listOf(effective)).single().toList() }
                ?: embeddingModel.embed(listOf(effective)).single().toList()
        if (embedding.size != profile.dimensions) {
            telemetry?.count("rag_embedding_dimension_failures_total")
            throw IllegalArgumentException("Embedding dimensions do not match the model profile")
        }
        if (properties.embedding.cacheEnabled) {
            cache.put(key, profile.provider, profile.model, profile.dimensions, embedding)
        }
        return embedding
    }

    private fun cacheKey(
        profile: ModelProfile,
        text: String,
        purpose: String,
    ): String {
        val normalized = text.trim().split(Regex("\\s+")).joinToString(" ")
        val material = "${profile.provider}\u0000${profile.model}\u0000${profile.dimensions}\u0000$purpose\u0000$normalized"
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun providerMatches(
        profileProvider: String,
        activeProvider: String,
    ): Boolean = providerAliases(profileProvider).intersect(providerAliases(activeProvider)).isNotEmpty()

    private fun providerAliases(provider: String): Set<String> =
        when (provider.lowercase()) {
            "azure", "azure_openai" -> setOf("azure_openai")
            "openai", "openai_compatible" -> setOf("openai_compatible")
            else -> setOf(provider.lowercase())
        }

    private companion object {
        const val WARMUP_TEXT = "model profile warmup"
    }
}
