package com.dex.ragpoc.persistence

import com.dex.ragpoc.domain.ModelProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PostgresInteropCodecTest {
    @Test
    fun `matches Python UUID5 URL namespace chunk identifiers`() {
        assertEquals("96bfba1f-a026-5f29-9cb6-eef33495c01d", PostgresInteropCodec.chunkUuid("doc-1:0").toString())
        assertEquals(
            "3fdb7aa4-72ee-5351-8074-c0610eba9900",
            PostgresInteropCodec.chunkUuid("sample/chunk:default:42").toString(),
        )
    }

    @Test
    fun `matches Python pgvector literal formatting`() {
        assertEquals("[0.1,-2.5,3.0]", PostgresInteropCodec.vectorLiteral(listOf(0.1f, -2.5f, 3.0f)))
    }

    @Test
    fun `round trips Python little endian float32 cache payloads`() {
        val embedding = listOf(1.0f, -2.5f, 0.25f)
        val payload = PostgresInteropCodec.encodeEmbedding(embedding)

        assertEquals("0000803f000020c00000803e", payload.toHex())
        assertEquals(embedding, PostgresInteropCodec.decodeEmbedding(payload, 3))
    }

    @Test
    fun `matches Python embedding cache key material`() {
        val profile =
            ModelProfile(
                profileName = "bge-m3",
                provider = "ollama",
                model = "bge-m3:latest",
                dimensions = 1024,
                storageTarget = "document_chunks_bge_m3",
            )

        assertEquals(
            "bc2de38fc45dbe43f9807b2ba0599fe1a4ab7aef685cc10186b1dad66f6a8dfe",
            PostgresInteropCodec.embeddingCacheKey(profile, "  hello\n world  ", "query"),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
