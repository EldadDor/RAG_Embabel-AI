package com.dex.ragpoc.parsing

import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceType
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors

const val DEFAULT_MAX_DOCUMENT_BYTES: Long = 50L * 1024 * 1024

class UnsupportedDocumentTypeException(
    message: String,
) : IllegalArgumentException(message)

class DocumentTooLargeException(
    message: String,
) : IllegalArgumentException(message)

data class SkippedDocument(
    val path: String,
    val reason: String,
)

fun interface DocumentLoader {
    fun load(sourcePath: Path): Document
}

class DocumentLoaderRegistry(
    private val loaders: Map<String, DocumentLoader> = defaultLoaders(),
) {
    fun loadDocument(
        sourcePath: Path,
        maxBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
    ): Document {
        require(maxBytes >= 0) { "Maximum document size must not be negative" }
        require(Files.exists(sourcePath)) { "File not found: $sourcePath" }
        require(Files.isRegularFile(sourcePath)) { "Path is not a file: $sourcePath" }
        val size = Files.size(sourcePath)
        if (size > maxBytes) throw DocumentTooLargeException("File too large ($size bytes): $sourcePath")
        return loaders[extension(sourcePath)]?.load(sourcePath)
            ?: throw UnsupportedDocumentTypeException("Unsupported file type '${extension(sourcePath)}': $sourcePath")
    }

    fun loadDirectory(
        directory: Path,
        recursive: Boolean = false,
        maxBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
    ): Pair<List<Document>, List<SkippedDocument>> {
        require(Files.isDirectory(directory)) { "Path is not a directory: $directory" }
        val paths =
            (if (recursive) Files.walk(directory) else Files.list(directory))
                .use { stream ->
                    stream
                        .filter(Files::isRegularFile)
                        .filter { path ->
                            directory
                                .relativize(path)
                                .iterator()
                                .asSequence()
                                .none { segment -> segment.toString() in EXCLUDED_DIRECTORY_NAMES }
                        }.sorted()
                        .collect(Collectors.toList())
                }
        val documents = mutableListOf<Document>()
        val skipped = mutableListOf<SkippedDocument>()
        for (path in paths) {
            if (extension(path) !in loaders) continue
            try {
                documents += loadDocument(path, maxBytes)
            } catch (error: Exception) {
                skipped += SkippedDocument(path.toString(), error.message ?: error.javaClass.simpleName)
            }
        }
        return documents to skipped
    }

    companion object {
        private val EXCLUDED_DIRECTORY_NAMES = setOf(".git", ".idea", ".gradle", ".venv", "build", "node_modules", "out", "target")

        fun defaultLoaders(): Map<String, DocumentLoader> =
            mapOf(
                ".txt" to TextDocumentLoader,
                ".md" to MarkdownDocumentLoader,
                ".mdx" to MarkdownDocumentLoader,
                ".html" to HtmlDocumentLoader,
                ".htm" to HtmlDocumentLoader,
                ".pdf" to PdfDocumentLoader,
            )
    }
}

object TextDocumentLoader : DocumentLoader {
    override fun load(sourcePath: Path): Document =
        document(sourcePath, SourceType.TEXT, readUtf8(sourcePath), title = fileStem(sourcePath))
}

object MarkdownDocumentLoader : DocumentLoader {
    override fun load(sourcePath: Path): Document {
        val content = readUtf8(sourcePath)
        val title =
            content
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")
                ?.trim()
        return document(sourcePath, SourceType.MARKDOWN, content, title ?: fileStem(sourcePath))
    }
}

object HtmlDocumentLoader : DocumentLoader {
    private val removableBlocks = Regex("(?is)<(script|style|noscript)\\b[^>]*>.*?</\\1>")
    private val titleTag = Regex("(?is)<title\\b[^>]*>(.*?)</title>")
    private val h1Tag = Regex("(?is)<h1\\b[^>]*>(.*?)</h1>")
    private val tags = Regex("(?is)<[^>]+>")

    override fun load(sourcePath: Path): Document {
        val visibleHtml = readUtf8(sourcePath).replace(removableBlocks, "")
        val title =
            titleTag
                .find(visibleHtml)
                ?.groupValues
                ?.get(1)
                ?.let(::toVisibleText)
                ?.ifBlank { null }
                ?: h1Tag
                    .find(visibleHtml)
                    ?.groupValues
                    ?.get(1)
                    ?.let(::toVisibleText)
                    ?.ifBlank { null }
                ?: fileStem(sourcePath)
        return document(sourcePath, SourceType.HTML, toVisibleText(visibleHtml), title)
    }

    private fun toVisibleText(html: String): String =
        tags
            .replace(html, "\n")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
}

object PdfDocumentLoader : DocumentLoader {
    override fun load(sourcePath: Path): Document =
        Loader.loadPDF(sourcePath.toFile()).use { pdf ->
            val parts =
                (1..pdf.numberOfPages).map { pageNumber ->
                    val stripper =
                        PDFTextStripper().apply {
                            startPage = pageNumber
                            endPage = pageNumber
                        }
                    "[PAGE $pageNumber]\n${stripper.getText(pdf).trimEnd()}"
                }
            document(
                sourcePath,
                SourceType.PDF,
                parts.joinToString("\n\n"),
                pdf.documentInformation.title?.takeIf(String::isNotBlank) ?: fileStem(sourcePath),
                mapOf("total_pages" to pdf.numberOfPages),
            )
        }
}

private fun document(
    sourcePath: Path,
    sourceType: SourceType,
    content: String,
    title: String,
    metadata: Map<String, Any?> = emptyMap(),
): Document =
    Document(
        documentId = sha256(sourcePath.toString()),
        sourcePath = sourcePath.toString(),
        sourceType = sourceType,
        content = content,
        title = title,
        metadata = metadata,
    )

private fun readUtf8(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

private fun extension(path: Path): String =
    path.fileName.toString().substringAfterLast('.', "").let {
        if (it.isEmpty()) "" else ".${it.lowercase()}"
    }

private fun fileStem(path: Path): String = path.fileName.toString().substringBeforeLast('.', path.fileName.toString())

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
