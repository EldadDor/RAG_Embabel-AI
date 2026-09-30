package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class IngestionSourcePolicy(
    private val properties: AppProperties,
) {
    fun admit(sourcePath: String): Path {
        val requested = Path.of(sourcePath).toAbsolutePath().normalize()
        val source =
            try {
                requested.toRealPath()
            } catch (error: java.io.IOException) {
                throw IllegalArgumentException("The ingestion source is unavailable", error)
            }
        require(Files.isRegularFile(source) || Files.isDirectory(source)) { "The ingestion source is invalid" }
        val allowed =
            properties.ingestion.allowedRoots.any { root ->
                try {
                    val resolvedRoot = root.toAbsolutePath().normalize().toRealPath()
                    Files.isDirectory(resolvedRoot) && source.startsWith(resolvedRoot)
                } catch (error: java.io.IOException) {
                    false
                }
            }
        require(allowed) { "The ingestion source is not allowed" }
        return source
    }
}
