package com.dex.ragpoc.asset

import com.dex.ragpoc.config.RagOperation
import com.dex.ragpoc.config.RagTelemetry
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.ingestion.ContentAddressedAssetStore
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.StoredDocumentAsset
import com.dex.ragpoc.workspace.WorkspaceAccessService
import org.springframework.stereotype.Service

class AssetNotFoundException : RuntimeException()

class UnsupportedAssetMediaTypeException : RuntimeException()

data class AssetContent(
    val metadata: StoredDocumentAsset,
    val bytes: ByteArray,
)

@Service
class AssetReadService(
    private val assets: JdbcDocumentAssetRepository,
    private val access: WorkspaceAccessService,
    private val storage: ContentAddressedAssetStore,
    private val telemetry: RagTelemetry? = null,
) {
    fun read(
        principal: Principal,
        workspaceId: String,
        assetId: String,
    ): AssetContent =
        if (telemetry != null) {
            telemetry.observe(RagOperation.ASSET_READ) { readPrepared(principal, workspaceId, assetId) }
        } else {
            readPrepared(principal, workspaceId, assetId)
        }

    private fun readPrepared(
        principal: Principal,
        workspaceId: String,
        assetId: String,
    ): AssetContent {
        access.requireAccess(principal, workspaceId)
        val metadata = assets.findForWorkspace(workspaceId, assetId) ?: throw AssetNotFoundException()
        if (metadata.mediaType !in browserImageTypes) throw UnsupportedAssetMediaTypeException()
        val bytes =
            try {
                storage.read(metadata.storageKey)
            } catch (_: IllegalArgumentException) {
                throw AssetNotFoundException()
            }
        if (!matchesSignature(metadata.mediaType, bytes)) throw UnsupportedAssetMediaTypeException()
        return AssetContent(metadata, bytes)
    }

    private fun matchesSignature(
        mediaType: String,
        bytes: ByteArray,
    ): Boolean =
        when (mediaType) {
            "image/png" -> {
                hasPrefix(bytes, byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            }

            "image/jpeg" -> {
                hasPrefix(bytes, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
            }

            "image/gif" -> {
                hasPrefix(bytes, "GIF87a".toByteArray()) || hasPrefix(bytes, "GIF89a".toByteArray())
            }

            "image/webp" -> {
                bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                    bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
            }

            else -> {
                false
            }
        }

    private fun hasPrefix(
        bytes: ByteArray,
        prefix: ByteArray,
    ): Boolean = bytes.size >= prefix.size && bytes.copyOfRange(0, prefix.size).contentEquals(prefix)

    private companion object {
        val browserImageTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}
