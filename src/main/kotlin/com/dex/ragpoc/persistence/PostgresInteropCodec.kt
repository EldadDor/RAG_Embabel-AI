package com.dex.ragpoc.persistence

import com.dex.ragpoc.domain.ModelProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object PostgresInteropCodec {
    private val urlNamespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    fun chunkUuid(chunkId: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(uuidBytes(urlNamespace))
        val hash = digest.digest(chunkId.toByteArray(StandardCharsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(hash, 0, 16)
        return UUID(bytes.long, bytes.long)
    }

    fun vectorLiteral(vector: List<Float>): String = vector.joinToString(prefix = "[", postfix = "]", separator = ",")

    fun encodeEmbedding(embedding: List<Float>): ByteArray =
        ByteBuffer
            .allocate(embedding.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> embedding.forEach(buffer::putFloat) }
            .array()

    fun decodeEmbedding(
        payload: ByteArray,
        dimensions: Int,
    ): List<Float> {
        require(payload.size == dimensions * Float.SIZE_BYTES) {
            "Cached embedding byte length does not match the model profile"
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return List(dimensions) { buffer.float }
    }

    fun embeddingCacheKey(
        profile: ModelProfile,
        text: String,
        purpose: String,
    ): String {
        val normalized = text.trim().split(Regex("\\s+")).joinToString(" ")
        val material =
            listOf(
                profile.provider,
                profile.model,
                profile.dimensions.toString(),
                purpose,
                normalized,
            ).joinToString("\u0000")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
}
