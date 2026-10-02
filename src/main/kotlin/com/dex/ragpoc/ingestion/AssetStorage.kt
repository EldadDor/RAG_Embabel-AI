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
        val key = asset.contentHash
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

    fun read(storageKey: String): ByteArray {
        val target = resolve(storageKey)
        require(Files.isRegularFile(target)) { "Asset is not available" }
        return Files.readAllBytes(target)
    }

    private fun resolve(key: String): Path {
        val relative = if (Regex("^[0-9a-f]{64}$").matches(key)) "${key.take(2)}/$key" else key
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root)) { "Asset path escapes the configured storage root" }
        return path
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
