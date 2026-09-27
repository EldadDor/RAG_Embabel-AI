package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.DocumentAsset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class StoredAsset(
    val storageKey: String,
    val absolutePath: Path,
)

/** Stores asset bytes by SHA-256 below one configured, private filesystem root. */
class ContentAddressedAssetStore(
    private val properties: AppProperties,
) {
    private val root =
        properties.assets.storageRoot
            .toAbsolutePath()
            .normalize()

    fun validate(assets: List<DocumentAsset>) {
        require(assets.size <= properties.assets.maxImagesPerDocument) { "Too many document assets" }
        require(assets.sumOf { it.content.size.toLong() } <= properties.assets.maxTotalBytesPerDocument) {
            "Document assets exceed the total byte limit"
        }
        assets.forEach { asset ->
            require(asset.content.size.toLong() <= properties.assets.maxImageBytes) { "Asset exceeds the byte limit" }
            require(asset.contentHash == sha256(asset.content)) { "Asset content hash does not match its bytes" }
            require(asset.mediaType.startsWith("image/")) { "Unsupported asset media type: ${asset.mediaType}" }
        }
    }

    fun store(asset: DocumentAsset): StoredAsset {
        validate(listOf(asset))
        val extension = extensionFor(asset.mediaType, asset.originalName)
        val key = "${asset.contentHash.substring(0, 2)}/${asset.contentHash}$extension"
        val target = resolve(key)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) {
            val temporary = Files.createTempFile(target.parent, ".asset-", ".tmp")
            try {
                Files.write(temporary, asset.content)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        return StoredAsset(key, target)
    }

    private fun resolve(key: String): Path {
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "Asset path escapes the configured storage root" }
        return path
    }

    private fun extensionFor(
        mediaType: String,
        originalName: String?,
    ): String {
        val fromName = originalName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        return fromName?.let { ".$it" } ?: when (mediaType) {
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            else -> ""
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
