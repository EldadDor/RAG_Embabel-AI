package com.dex.ragpoc.asset

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.DocumentAsset
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.ingestion.ContentAddressedAssetStore
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.StoredDocumentAsset
import com.dex.ragpoc.workspace.WorkspaceAccessService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AssetReadServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `authorized browser image returns stored bytes`() {
        val storage = ContentAddressedAssetStore(AppProperties(assets = AppProperties.Assets(storageRoot = temporaryDirectory)))
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01)
        val hash = sha256(bytes)
        val stored = storage.store(DocumentAsset("image", "rId1", bytes, hash, "image/png", "screen.png"))
        val repository = mockk<JdbcDocumentAssetRepository>()
        every { repository.findForWorkspace("alpha", "asset-1") } returns metadata("asset-1", stored.storageKey, hash, "image/png")
        val access = mockk<WorkspaceAccessService>(relaxed = true)

        val result = AssetReadService(repository, access, storage).read(Principal("alice", "Alice"), "alpha", "asset-1")

        assertContentEquals(bytes, result.bytes)
        verify { access.requireAccess(Principal("alice", "Alice"), "alpha") }
    }

    @Test
    fun `non-browser media is rejected without reading storage`() {
        val storage = ContentAddressedAssetStore(AppProperties(assets = AppProperties.Assets(storageRoot = temporaryDirectory)))
        val repository = mockk<JdbcDocumentAssetRepository>()
        every { repository.findForWorkspace("alpha", "asset-1") } returns metadata("asset-1", "unused", "a".repeat(64), "image/svg+xml")

        assertFailsWith<UnsupportedAssetMediaTypeException> {
            AssetReadService(repository, mockk(relaxed = true), storage).read(Principal("alice", "Alice"), "alpha", "asset-1")
        }
    }

    private fun metadata(
        assetId: String,
        storageKey: String,
        hash: String,
        mediaType: String,
    ) = StoredDocumentAsset(assetId, "alpha", "default", "guide", storageKey, hash, mediaType, 1)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
