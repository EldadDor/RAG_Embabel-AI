package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.DocumentAsset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentAddressedAssetStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `stores image bytes at a deterministic content addressed key`() {
        val bytes = byteArrayOf(1, 2, 3)
        val asset = DocumentAsset("a", "r", bytes, hash(bytes), "image/png", "example.png")
        val store = ContentAddressedAssetStore(AppProperties(assets = AppProperties.Assets(storageRoot = temporaryDirectory)))

        val stored = store.store(asset)

        assertEquals("${asset.contentHash.take(2)}/${asset.contentHash}.png", stored.storageKey)
        assertTrue(stored.absolutePath.startsWith(temporaryDirectory.toAbsolutePath()))
        assertTrue(Files.readAllBytes(stored.absolutePath).contentEquals(bytes))
    }

    @Test
    fun `rejects mismatched hash before writing`() {
        val store = ContentAddressedAssetStore(AppProperties(assets = AppProperties.Assets(storageRoot = temporaryDirectory)))
        val asset = DocumentAsset("a", "r", byteArrayOf(1), "not-a-hash", "image/png")

        assertFailsWith<IllegalArgumentException> { store.store(asset) }
        assertTrue(Files.list(temporaryDirectory).use { !it.findAny().isPresent })
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
