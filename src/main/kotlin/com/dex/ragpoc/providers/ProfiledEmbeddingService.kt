package com.dex.ragpoc.providers

import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.persistence.JdbcEmbeddingCacheRepository
import com.dex.ragpoc.persistence.JdbcModelProfileRepository
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Service
import java.security.MessageDigest

class ModelProfileUnavailable(message: String) : IllegalArgumentException(message)

@Service
class ProfiledEmbeddingService(
    private val profiles: JdbcModelProfileRepository,
    private val cache: JdbcEmbeddingCacheRepository,
    private val embeddingModel: EmbeddingModel,
) {
    fun query(profileName: String, text: String): List<Float> = embed(resolveReady(profileName), text, "query")

    fun document(profileName: String, text: String): List<Float> = embed(resolveReady(profileName), text, "document")

    fun resolveReady(profileName: String): ModelProfile {
        val profile = profiles.get(profileName) ?: throw ModelProfileUnavailable("Unknown model profile: $profileName")
        if (profile.status != "ready") throw ModelProfileUnavailable("Model profile $profileName is not ready")
        require(Regex("^[a-z_][a-z0-9_]*$").matches(profile.storageTarget)) {
            "Model profile storage target is invalid"
        }
        require(profile.dimensions > 0) { "Model profile dimensions must be positive" }
        return profile
    }

    private fun embed(profile: ModelProfile, text: String, purpose: String): List<Float> {
        val effective = (if (purpose == "query") profile.queryPrefix else profile.documentPrefix) + text
        val key = cacheKey(profile, effective, purpose)
        cache.get(key, profile.dimensions)?.let { return it }
        val embedding = embeddingModel.embed(listOf(effective)).single().toList()
        require(embedding.size == profile.dimensions) { "Embedding dimensions do not match the model profile" }
        cache.put(key, profile.provider, profile.model, profile.dimensions, embedding)
        return embedding
    }

    private fun cacheKey(profile: ModelProfile, text: String, purpose: String): String {
        val normalized = text.trim().split(Regex("\\s+")).joinToString(" ")
        val material = "${profile.provider}\u0000${profile.model}\u0000${profile.dimensions}\u0000$purpose\u0000$normalized"
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
