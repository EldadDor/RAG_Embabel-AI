package com.dex.ragpoc.parsing

import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.DocumentAsset
import com.dex.ragpoc.domain.SourceType
import org.docx4j.XmlUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class WordDocumentException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Reads the document model through docx4j, retaining package relationships for image anchors. */
object WordDocumentLoader : DocumentLoader {
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val VML_NS = "urn:schemas-microsoft-com:vml"
    private const val IMAGE_REL = "$REL_NS/image"
    private const val MAX_ENTRIES = 10_000
    private const val MAX_EXPANDED_BYTES = 250L * 1024 * 1024

    override fun load(sourcePath: Path): Document {
        val parts = validatePackage(sourcePath)
        val word =
            try {
                WordprocessingMLPackage.load(sourcePath.toFile())
            } catch (error: Exception) {
                throw WordDocumentException("Unable to parse Word document: ${error.message}", error)
            }
        val xml = XmlUtils.marshaltoString(word.mainDocumentPart.jaxbElement, true, true)
        val body =
            parseXml(xml.toByteArray(Charsets.UTF_8)).documentElement.descendants(WORD_NS, "body").firstOrNull()
                ?: throw WordDocumentException("Word document has no body")
        val relationships = relationships(parts)
        val blocks = mutableListOf<RenderedBlock>()
        var section: String? = null
        for (element in body.childElements()) {
            val block =
                when {
                    element.matches(WORD_NS, "p") -> renderParagraph(element, section)
                    element.matches(WORD_NS, "tbl") -> renderTable(element, section)
                    else -> null
                }
            if (block != null) {
                blocks += block
                if (block.kind == "heading") section = block.section
            }
        }
        val content = blocks.joinToString("\n\n") { it.text }
        var offset = 0
        val blockMetadata =
            blocks.mapIndexed { ordinal, block ->
                if (ordinal > 0) offset += 2
                val start = offset
                offset += block.text.length
                mapOf(
                    "block_id" to "block-${ordinal.toString().padStart(4, '0')}",
                    "ordinal" to ordinal,
                    "kind" to block.kind,
                    "start_index" to start,
                    "end_index" to offset,
                    "section" to block.section,
                )
            }
        val anchors = mutableListOf<Map<String, Any?>>()
        val assets = mutableListOf<DocumentAsset>()
        blocks.forEachIndexed { blockOrdinal, block ->
            imageRefs(block.element).forEach { ref ->
                val anchorId = "image-${anchors.size.toString().padStart(4, '0')}"
                val blockId = "block-${blockOrdinal.toString().padStart(4, '0')}"
                val relationship = relationships[ref.relationshipId]
                val bytes = relationship?.target?.let(parts::get)
                val available = relationship?.type == IMAGE_REL && !relationship.external && bytes != null
                val caption =
                    block.text.trim().ifBlank {
                        blocks
                            .getOrNull(
                                blockOrdinal + 1,
                            )?.takeIf { it.kind == "caption" }
                            ?.text
                            ?.trim()
                    }
                val hash = bytes?.takeIf { available }?.let(::hash)
                anchors +=
                    mapOf(
                        "anchor_id" to anchorId,
                        "block_id" to blockId,
                        "block_ordinal" to blockOrdinal,
                        "section" to block.section,
                        "relationship_id" to ref.relationshipId,
                        "available" to available,
                        "external" to (relationship?.external ?: false),
                        "part_name" to relationship?.target?.let { "/$it" },
                        "original_name" to relationship?.target?.substringAfterLast('/'),
                        "media_type" to relationship?.target?.let(::mediaType),
                        "byte_size" to bytes?.takeIf { available }?.size,
                        "content_hash" to hash,
                        "alt_text" to ref.altText,
                        "display_name" to ref.displayName,
                        "caption" to caption,
                    )
                if (available && bytes != null && relationship != null && hash != null) {
                    assets +=
                        DocumentAsset(
                            anchorId = anchorId,
                            relationshipId = ref.relationshipId,
                            content = bytes,
                            contentHash = hash,
                            mediaType = mediaType(relationship.target),
                            originalName = relationship.target.substringAfterLast('/'),
                            ordinal = anchors.lastIndex,
                            blockId = blockId,
                            blockOrdinal = blockOrdinal,
                            sourceIndex = blockMetadata[blockOrdinal]["start_index"] as Int,
                            section = block.section,
                            altText = ref.altText,
                            caption = caption,
                        )
                }
            }
        }
        val title =
            parts["docProps/core.xml"]
                ?.let { core ->
                    parseXml(core)
                        .documentElement
                        .descendants("http://purl.org/dc/elements/1.1/", "title")
                        .firstOrNull()
                        ?.textContent
                        ?.trim()
                }.orEmpty()
                .ifBlank { sourcePath.fileName.toString().substringBeforeLast('.') }
        return Document(
            documentId = hash(sourcePath.toString().toByteArray(Charsets.UTF_8)),
            sourcePath = sourcePath.toString(),
            sourceType = SourceType.WORD,
            content = content,
            title = title,
            metadata =
                mapOf(
                    "blocks" to blockMetadata,
                    "image_anchors" to anchors,
                    "image_count" to assets.size,
                    "page_break_count" to blocks.sumOf { it.text.split("[PAGE BREAK]").size - 1 },
                ),
            contentHash = hash(Files.readAllBytes(sourcePath)),
            assets = assets,
        )
    }

    private fun validatePackage(path: Path): Map<String, ByteArray> =
        try {
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size > MAX_ENTRIES) throw WordDocumentException("Word package has too many entries")
                val parts = mutableMapOf<String, ByteArray>()
                var expanded = 0L
                for (entry in entries) {
                    val name = entry.name
                    if (name.startsWith('/') || name.startsWith('\\') || name.contains('\\') ||
                        name.split('/').any { it == ".." || it == "." }
                    ) {
                        throw WordDocumentException("Word package contains an unsafe entry path")
                    }
                    if (name in parts) throw WordDocumentException("Word package contains duplicate entries")
                    if (entry.size > MAX_EXPANDED_BYTES - expanded) throw WordDocumentException("Word package exceeds expanded-size limit")
                    val bytes = zip.getInputStream(entry).use { input -> input.readNBytes((MAX_EXPANDED_BYTES - expanded + 1).toInt()) }
                    expanded += bytes.size
                    if (expanded > MAX_EXPANDED_BYTES) throw WordDocumentException("Word package exceeds expanded-size limit")
                    val crc = CRC32().apply { update(bytes) }.value
                    if (entry.crc != crc || (entry.size >= 0 && entry.size != bytes.size.toLong())) {
                        throw WordDocumentException("Word package contains a corrupt entry: $name")
                    }
                    parts[name] = bytes
                }
                if ("[Content_Types].xml" !in parts || "word/document.xml" !in parts) {
                    throw WordDocumentException("File is not a valid .docx package")
                }
                parts
            }
        } catch (error: ZipException) {
            throw WordDocumentException("File is not a readable .docx package", error)
        }

    private fun relationships(parts: Map<String, ByteArray>): Map<String, ImageRelationship> {
        val xml = parts["word/_rels/document.xml.rels"] ?: return emptyMap()
        return parseXml(xml)
            .documentElement
            .descendants("http://schemas.openxmlformats.org/package/2006/relationships", "Relationship")
            .associate { element ->
                val target = element.getAttribute("Target")
                val external = element.getAttribute("TargetMode") == "External"
                val normalized =
                    if (external) {
                        target
                    } else {
                        Path
                            .of("word")
                            .resolve(target)
                            .normalize()
                            .toString()
                            .replace('\\', '/')
                    }
                element.getAttribute("Id") to ImageRelationship(element.getAttribute("Type"), normalized, external)
            }
    }

    private fun renderParagraph(
        element: Element,
        section: String?,
    ): RenderedBlock? {
        val text = element.descendants(WORD_NS, "t").joinToString("") { it.textContent }.trim()
        val style =
            element
                .descendants(WORD_NS, "pStyle")
                .firstOrNull()
                ?.getAttributeNS(WORD_NS, "val")
                .orEmpty()
        val heading =
            Regex("Heading([1-6])", RegexOption.IGNORE_CASE)
                .matchEntire(style)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        if (heading != null && text.isNotEmpty()) return RenderedBlock("heading", "${"#".repeat(heading)} $text", element, text)
        var rendered =
            when {
                style.startsWith("ListBullet", ignoreCase = true) && text.isNotEmpty() -> "- $text"
                style.startsWith("ListNumber", ignoreCase = true) && text.isNotEmpty() -> "1. $text"
                else -> text
            }
        val breaks =
            element.descendants(WORD_NS, "br").count { it.getAttributeNS(WORD_NS, "type") == "page" } +
                element.descendants(WORD_NS, "lastRenderedPageBreak").size
        if (breaks > 0) rendered += (if (rendered.isEmpty()) "" else "\n") + List(breaks) { "[PAGE BREAK]" }.joinToString("\n")
        if (rendered.isEmpty() && imageRefs(element).isEmpty()) return null
        return RenderedBlock(if (style.equals("Caption", ignoreCase = true)) "caption" else "paragraph", rendered, element, section)
    }

    private fun renderTable(
        element: Element,
        section: String?,
    ): RenderedBlock? {
        val rows =
            element.childElements().filter { it.matches(WORD_NS, "tr") }.map { row ->
                row.childElements().filter { it.matches(WORD_NS, "tc") }.map { cell ->
                    cell
                        .descendants(WORD_NS, "t")
                        .joinToString(" ") { it.textContent }
                        .trim()
                        .replace("|", "\\|")
                }
            }
        if (rows.isEmpty()) return null
        val width = rows.maxOf { it.size }

        fun format(row: List<String>) = "| " + (row + List(width - row.size) { "" }).joinToString(" | ") + " |"
        val text = (listOf(format(rows.first()), format(List(width) { "---" })) + rows.drop(1).map(::format)).joinToString("\n")
        return RenderedBlock("table", text, element, section)
    }

    private fun imageRefs(element: Element): List<ImageRef> =
        element
            .getElementsByTagName("*")
            .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
            .mapNotNull { image ->
                when {
                    image.matches(DRAWING_NS, "blip") -> {
                        val id = image.getAttributeNS(REL_NS, "embed").takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val properties = image.ancestor(WORD_NS, "drawing")?.descendants(WP_NS, "docPr")?.firstOrNull()
                        ImageRef(
                            id,
                            properties?.getAttribute("descr")?.ifBlank { properties.getAttribute("title") },
                            properties?.getAttribute("name"),
                        )
                    }

                    image.matches(VML_NS, "imagedata") -> {
                        val id = image.getAttributeNS(REL_NS, "id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val shape = image.ancestor(VML_NS, "shape")
                        ImageRef(id, shape?.getAttribute("alt")?.ifBlank { shape.getAttribute("title") }, shape?.getAttribute("id"))
                    }

                    else -> {
                        null
                    }
                }
            }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setXIncludeAware(false)
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun mediaType(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "tif", "tiff" -> "image/tiff"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class RenderedBlock(
        val kind: String,
        val text: String,
        val element: Element,
        val section: String?,
    )

    private data class ImageRelationship(
        val type: String,
        val target: String,
        val external: Boolean,
    )

    private data class ImageRef(
        val relationshipId: String,
        val altText: String?,
        val displayName: String?,
    )

    private fun Element.matches(
        namespace: String,
        name: String,
    ) = namespaceURI == namespace && localName == name

    private fun Element.childElements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.descendants(
        namespace: String,
        name: String,
    ): List<Element> =
        getElementsByTagNameNS(namespace, name).let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }

    private fun Element.ancestor(
        namespace: String,
        name: String,
    ): Element? {
        var current: Node? = parentNode
        while (current != null) {
            if (current is Element && current.matches(namespace, name)) return current
            current = current.parentNode
        }
        return null
    }
}
