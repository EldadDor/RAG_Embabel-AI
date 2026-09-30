package com.dex.ragpoc.parsing

import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.DocumentAsset
import com.dex.ragpoc.domain.SourceType
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.xml.sax.Attributes
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

class PowerPointDocumentException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Per-load limits, also allowing small adversarial fixtures without global POI settings. */
data class PresentationLimits(
    val maxSourceBytes: Int = DEFAULT_MAX_DOCUMENT_BYTES.toInt(),
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Int = 50 * 1024 * 1024,
    val maxExpandedBytes: Long = 250L * 1024 * 1024,
    val maxSlides: Int = 1_000,
    val maxGroupDepth: Int = 64,
    val maxShapes: Int = 100_000,
    val maxCharacters: Int = 2_000_000,
)

/** Extracts structured presentation content; never renders or resolves external resources. */
class PowerPointDocumentLoader(
    private val limits: PresentationLimits = PresentationLimits(),
) : DocumentLoader {
    override fun load(sourcePath: Path): Document {
        try {
            val snapshot = Files.newInputStream(sourcePath).use { it.readNBytes(limits.maxSourceBytes + 1) }
            requirePptx(snapshot.size <= limits.maxSourceBytes, "Presentation exceeds source-size limit")
            validatePackage(snapshot)
            return XMLSlideShow(ByteArrayInputStream(snapshot)).use { show ->
                requirePptx(show.slides.size <= limits.maxSlides, "Presentation has too many slides")
                render(sourcePath, snapshot, show)
            }
        } catch (error: PowerPointDocumentException) {
            throw error
        } catch (error: Exception) {
            throw PowerPointDocumentException("Unable to read presentation", error)
        }
    }

    private fun validatePackage(snapshot: ByteArray) {
        val names = mutableSetOf<String>()
        val relationships = mutableListOf<Relationship>()
        var presentationType = false
        var expanded = 0L
        var slideParts = 0
        SeekableInMemoryByteChannel(snapshot).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    requirePptx(names.size < limits.maxEntries, "Presentation has too many package entries")
                    requirePptx(
                        name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') &&
                            name.trimEnd('/').split('/').none { it == ".." || it == "." || it.isEmpty() },
                        "Presentation contains an unsafe package path",
                    )
                    requirePptx(names.add(name), "Presentation contains duplicate package entries")
                    requirePptx(!entry.isUnixSymlink && zip.canReadEntryData(entry), "Presentation contains an unsupported entry")
                    if (entry.isDirectory) continue
                    requirePptx(entry.size in 0..limits.maxEntryBytes.toLong(), "Presentation entry exceeds size limit")
                    requirePptx(entry.size <= limits.maxExpandedBytes - expanded, "Presentation exceeds expanded-size limit")
                    val remaining = minOf(limits.maxEntryBytes.toLong(), limits.maxExpandedBytes - expanded).toInt()
                    val bytes = zip.getInputStream(entry).use { it.readNBytes(remaining + 1) }
                    expanded += bytes.size
                    requirePptx(
                        bytes.size <= limits.maxEntryBytes && expanded <= limits.maxExpandedBytes,
                        "Presentation exceeds expanded-size limit",
                    )
                    requirePptx(
                        bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc,
                        "Presentation contains a corrupt entry",
                    )
                    if (name.matches(Regex("ppt/slides/slide\\d+\\.xml"))) {
                        slideParts++
                        requirePptx(slideParts <= limits.maxSlides, "Presentation has too many slides")
                    }
                    if (name.endsWith(".xml") || name.endsWith(".rels")) {
                        validateXml(bytes) { namespace, local, attrs ->
                            if (name == "[Content_Types].xml" && namespace == CONTENT_NS && local in setOf("Override", "Default")) {
                                val type = attrs.getValue("ContentType").orEmpty()
                                requirePptx(
                                    !type.contains("macroEnabled", true) && !type.contains("vbaProject", true),
                                    "Macro-enabled presentations are unsupported",
                                )
                                if (attrs.getValue("PartName") == "/ppt/presentation.xml") presentationType = type == PRESENTATION_TYPE
                            }
                            if (namespace == PACKAGE_REL_NS && local == "Relationship") {
                                requirePptx(relationships.size < limits.maxShapes, "Presentation has too many relationships")
                                relationships +=
                                    Relationship(
                                        name,
                                        attrs.getValue("Id").orEmpty(),
                                        attrs.getValue("Type").orEmpty(),
                                        attrs.getValue("Target").orEmpty(),
                                        attrs.getValue("TargetMode") == "External",
                                    )
                            }
                        }
                    }
                }
            }
        }
        val required = setOf("[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels")
        requirePptx(required.all(names::contains) && presentationType, "File is not a valid PPTX package")
        val ids = mutableSetOf<Pair<String, String>>()
        relationships.forEach { relationship ->
            requirePptx(
                relationship.id.isNotBlank() && ids.add(relationship.part to relationship.id),
                "Presentation contains duplicate or missing relationship IDs",
            )
            if (!relationship.external) {
                val target = resolveTarget(relationship.part, relationship.target)
                requirePptx(target in names, "Presentation relationship refers to a missing part")
                if (relationship.part == "_rels/.rels" && relationship.type.endsWith("/officeDocument")) {
                    requirePptx(target == "ppt/presentation.xml", "Presentation root relationship is invalid")
                }
            }
        }
        requirePptx(
            relationships.any { it.part == "_rels/.rels" && !it.external && it.type.endsWith("/officeDocument") },
            "Presentation has no document relationship",
        )
    }

    private fun resolveTarget(
        part: String,
        target: String,
    ): String {
        requirePptx(
            target.isNotBlank() && !target.contains('\\') && !target.contains(':') && !target.contains('%') &&
                !target.contains('#') && !target.contains('?'),
            "Presentation has an unsafe relationship target",
        )
        val directory = if (part == "_rels/.rels") "" else part.substringBeforeLast("/_rels/")
        val pieces = mutableListOf<String>()
        val combined = if (target.startsWith('/')) target.drop(1) else "$directory/$target".trimStart('/')
        combined.split('/').forEach { piece ->
            when (piece) {
                "", "." -> {
                    Unit
                }

                ".." -> {
                    requirePptx(pieces.isNotEmpty(), "Presentation relationship escapes its package")
                    pieces.removeAt(pieces.lastIndex)
                }

                else -> {
                    pieces += piece
                }
            }
        }
        return pieces.joinToString("/")
    }

    private fun validateXml(
        bytes: ByteArray,
        visit: (String, String, Attributes) -> Unit,
    ) {
        val factory =
            SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        val parser =
            factory.newSAXParser().apply {
                setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
        parser.parse(
            ByteArrayInputStream(bytes),
            object : DefaultHandler() {
                private var depth = 0

                override fun startElement(
                    uri: String,
                    localName: String,
                    qName: String,
                    attributes: Attributes,
                ) {
                    depth++
                    requirePptx(depth <= 256, "Presentation XML nesting is too deep")
                    visit(uri, localName, attributes)
                }

                override fun endElement(
                    uri: String,
                    localName: String,
                    qName: String,
                ) {
                    depth--
                }

                override fun error(error: SAXParseException): Unit = throw error

                override fun fatalError(error: SAXParseException): Unit = throw error
            },
        )
    }

    private fun render(
        path: Path,
        snapshot: ByteArray,
        show: XMLSlideShow,
    ): Document {
        val content = StringBuilder()
        val slides = mutableListOf<Map<String, Any?>>()
        val blocks = mutableListOf<Map<String, Any?>>()
        val anchors = mutableListOf<Map<String, Any?>>()
        val assets = mutableListOf<DocumentAsset>()
        var shapeCount = 0
        var assetBytes = 0L

        fun append(
            text: String,
            kind: String,
            number: Int,
            shapeId: Int? = null,
            groupIds: List<Int> = emptyList(),
        ): Map<String, Any?> {
            requirePptx(blocks.size < limits.maxShapes, "Presentation has too many blocks")
            requirePptx(text.length.toLong() + content.length + 2 <= limits.maxCharacters, "Presentation exceeds text limit")
            if (content.isNotEmpty()) content.append("\n\n")
            val start = content.length
            content.append(text)
            val block =
                mapOf(
                    "block_id" to "slide-$number-block-${blocks.size}",
                    "ordinal" to blocks.size,
                    "kind" to kind,
                    "slide_number" to number,
                    "section" to "Slide $number",
                    "shape_id" to shapeId,
                    "group_ids" to groupIds,
                    "start_index" to start,
                    "end_index" to content.length,
                )
            blocks += block
            return block
        }

        show.slides.forEachIndexed { index, slide ->
            val number = index + 1
            val marker = append("[SLIDE $number]", "slide", number)
            val slideStart = marker["start_index"] as Int

            fun walk(
                shapes: List<XSLFShape>,
                depth: Int,
                notes: Boolean = false,
                groupIds: List<Int> = emptyList(),
            ) {
                requirePptx(depth <= limits.maxGroupDepth, "Presentation group nesting is too deep")
                shapes.forEach { shape ->
                    shapeCount++
                    requirePptx(shapeCount <= limits.maxShapes, "Presentation has too many shapes")
                    when (shape) {
                        is XSLFGroupShape -> {
                            walk(shape.shapes, depth + 1, notes, groupIds + shape.shapeId)
                        }

                        is XSLFTable -> {
                            val text =
                                buildString {
                                    shape.rows.forEach { row ->
                                        if (isNotEmpty()) append('\n')
                                        append("| ")
                                        row.cells.forEach { cell ->
                                            requirePptx(
                                                length + cell.text.length <= limits.maxCharacters,
                                                "Presentation table exceeds text limit",
                                            )
                                            append(cell.text.replace("|", "\\|").replace("\n", " / "))
                                            append(" | ")
                                        }
                                    }
                                }
                            if (text.isNotBlank()) append(text, "table", number, shape.shapeId, groupIds)
                        }

                        is XSLFPictureShape -> {
                            if (!notes) {
                                val block = append("[IMAGE ${shape.shapeId}]", "image", number, shape.shapeId, groupIds)
                                val external = shape.isExternalLinkedPicture
                                val picture = if (external) null else shape.pictureData
                                val mediaType = picture?.contentType
                                val supported = mediaType in setOf("image/png", "image/jpeg", "image/gif")
                                if (supported && picture != null) {
                                    requirePptx(picture.packagePart.size <= MAX_IMAGE_BYTES, "Presentation image exceeds byte limit")
                                }
                                val bytes = if (supported) picture?.data else null
                                if (bytes != null) {
                                    requirePptx(
                                        bytes.size <= MAX_IMAGE_BYTES && assets.size < MAX_IMAGES &&
                                            assetBytes + bytes.size <= MAX_ASSET_BYTES,
                                        "Presentation images exceed asset limits",
                                    )
                                    assetBytes += bytes.size
                                }
                                val anchorId = "slide-$number-image-${shape.shapeId}"
                                val properties =
                                    shape.xmlObject.newCursor().use { cursor ->
                                        cursor.selectPath("declare namespace p='$PRESENTATION_NS'; .//p:cNvPr")
                                        if (cursor.toNextSelection()) cursor.getAttributeText(javax.xml.namespace.QName("descr")) else null
                                    }
                                anchors +=
                                    mapOf(
                                        "anchor_id" to anchorId,
                                        "block_id" to block["block_id"],
                                        "slide_number" to number,
                                        "available" to (bytes != null),
                                        "external" to external,
                                        "media_type" to mediaType,
                                        "skip_reason" to
                                            if (external) {
                                                "external"
                                            } else if (!supported) {
                                                "unsupported_image_type"
                                            } else {
                                                null
                                            },
                                    )
                                if (bytes != null && picture != null && mediaType != null) {
                                    val relationship =
                                        slide.packagePart.relationships
                                            .firstOrNull {
                                                it.targetURI.toString().endsWith(
                                                    picture.packagePart.partName.name
                                                        .substringAfterLast('/'),
                                                )
                                            }?.id ?: "shape-${shape.shapeId}"
                                    val dimensions = picture.imageDimension
                                    assets +=
                                        DocumentAsset(
                                            anchorId,
                                            "slide-$number:$relationship",
                                            bytes,
                                            sha256(bytes),
                                            mediaType,
                                            picture.fileName,
                                            anchors.lastIndex,
                                            block["block_id"] as String,
                                            block["ordinal"] as Int,
                                            block["start_index"] as Int,
                                            "Slide $number",
                                            properties,
                                            slide.title,
                                            dimensions.width,
                                            dimensions.height,
                                        )
                                }
                            }
                        }

                        is XSLFTextShape -> {
                            if (notes && shape.textType?.name !in setOf("BODY")) return@forEach
                            val text =
                                buildString {
                                    shape.textParagraphs.forEach { paragraph ->
                                        val value = paragraph.text.trim()
                                        requirePptx(
                                            length + value.length + 4 <= limits.maxCharacters,
                                            "Presentation shape exceeds text limit",
                                        )
                                        if (value.isNotEmpty()) {
                                            if (isNotEmpty()) append('\n')
                                            if (paragraph.isBullet) append("- ")
                                            append(value)
                                        }
                                    }
                                }
                            if (text.isNotBlank()) append(text, if (notes) "notes" else "text", number, shape.shapeId, groupIds)
                        }
                    }
                }
            }
            walk(slide.shapes, 0)
            slide.notes?.let { walk(it.shapes, 0, true) }
            slides +=
                mapOf(
                    "slide_number" to number,
                    "section" to "Slide $number",
                    "title" to slide.title,
                    "hidden" to (slide.xmlObject.isSetShow && !slide.xmlObject.show),
                    "start_index" to slideStart,
                    "end_index" to content.length,
                )
        }
        return Document(
            sha256(path.toString().toByteArray(Charsets.UTF_8)),
            path.toString(),
            SourceType.UNKNOWN,
            content.toString(),
            show.properties.coreProperties.title
                ?.takeIf(String::isNotBlank) ?: path.fileName.toString().substringBeforeLast('.'),
            mapOf(
                "document_format" to "pptx",
                "total_slides" to slides.size,
                "slides" to slides,
                "blocks" to blocks,
                "image_anchors" to anchors,
                "image_count" to assets.size,
            ),
            sha256(snapshot),
            assets,
        )
    }

    private data class Relationship(
        val part: String,
        val id: String,
        val type: String,
        val target: String,
        val external: Boolean,
    )

    private companion object {
        const val CONTENT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
        const val PRESENTATION_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
        const val PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val PRESENTATION_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
        const val MAX_IMAGES = 100
        const val MAX_ASSET_BYTES = 100L * 1024 * 1024

        fun requirePptx(
            condition: Boolean,
            message: String,
        ) {
            if (!condition) throw PowerPointDocumentException(message)
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
