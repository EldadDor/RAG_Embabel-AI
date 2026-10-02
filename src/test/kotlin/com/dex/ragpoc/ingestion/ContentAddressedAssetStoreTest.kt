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

        assertEquals(asset.contentHash, stored.storageKey)
        assertEquals(temporaryDirectory.resolve(asset.contentHash.take(2)).resolve(asset.contentHash).toAbsolutePath(), stored.absolutePath)
        assertTrue(stored.absolutePath.startsWith(temporaryDirectory.toAbsolutePath()))
        assertTrue(Files.readAllBytes(stored.absolutePath).contentEquals(bytes))
    }

    @Test
    fun `reads existing Kotlin extension keys and Python hash keys without rewriting files`() {
        val bytes = byteArrayOf(1, 2, 3)
        val key = hash(bytes)
        val directory = Files.createDirectories(temporaryDirectory.resolve(key.take(2)))
        Files.write(directory.resolve("$key.png"), bytes)
        Files.write(directory.resolve(key), bytes)
        val store = ContentAddressedAssetStore(AppProperties(assets = AppProperties.Assets(storageRoot = temporaryDirectory)))
        assertTrue(store.read("${key.take(2)}/$key.png").contentEquals(bytes))
        assertTrue(store.read(key).contentEquals(bytes))
        assertFailsWith<IllegalArgumentException> { store.read("../outside") }
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
