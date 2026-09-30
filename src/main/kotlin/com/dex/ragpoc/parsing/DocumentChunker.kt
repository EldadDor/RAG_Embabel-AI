package com.dex.ragpoc.parsing

import com.dex.ragpoc.domain.Chunk
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceType
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterJava
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterPython
import java.nio.charset.StandardCharsets

data class ChunkingProfile(
    val provider: String = "recursive-character",
    val chunkSize: Int = 800,
    val chunkOverlap: Int = 120,
) {
    init {
        require(chunkSize > 0) { "Chunk size must be positive" }
        require(chunkOverlap >= 0 && chunkOverlap < chunkSize) { "Chunk overlap must be nonnegative and smaller than chunk size" }
        require(provider.lowercase() in RECURSIVE_PROVIDERS) { "Unsupported chunker provider: $provider" }
    }

    companion object {
        private val RECURSIVE_PROVIDERS = setOf("default", "langchain", "recursive-character")
    }
}

/** Produces stable profile-scoped chunks without accessing persistence or providers. */
class DocumentChunker(
    private val profiles: Map<String, ChunkingProfile> = mapOf("default" to ChunkingProfile()),
    private val defaultProfileName: String = "default",
) {
    init {
        require(defaultProfileName in profiles) { "Default chunking profile is not configured: $defaultProfileName" }
    }

    fun chunk(
        document: Document,
        profileName: String? = null,
    ): List<Chunk> {
        val name = profileName ?: defaultProfileName
        val profile = profiles[name] ?: throw IllegalArgumentException("Unknown chunking profile: $name")
        val segments =
            when (document.metadata["language"]) {
                "python" -> if (document.sourceType == SourceType.CODE) SyntaxChunker.python(document.content) else null
                "java" -> if (document.sourceType == SourceType.CODE) SyntaxChunker.java(document.content) else null
                "kotlin" -> if (document.sourceType == SourceType.CODE) SyntaxChunker.kotlin(document.content) else null
                else -> null
            } ?: genericSegments(document, profile)
        return segments
            .filter { it.text.isNotBlank() }
            .mapIndexed { index, segment ->
                val chunkMetadata = segment.metadata
                Chunk(
                    chunkId = if (name == "default") "${document.documentId}:$index" else "${document.documentId}:$name:$index",
                    documentId = document.documentId,
                    sourcePath = document.sourcePath,
                    sourceType = document.sourceType,
                    text = segment.text.trim(),
                    chunkIndex = index,
                    title = document.title,
                    page = chunkMetadata["slide_number"] as? Int ?: document.metadata["page"] as? Int,
                    section = chunkMetadata["section"] as? String ?: document.metadata["section"] as? String,
                    metadata =
                        document.metadata + chunkMetadata +
                            mapOf(
                                "chunking_profile" to name,
                                "chunker_provider" to profile.provider,
                                "chunk_index" to index,
                                "start_index" to segment.startIndex,
                                "end_index" to segment.endIndex,
                                "chunker_metadata" to chunkMetadata,
                            ),
                )
            }
    }

    private fun genericSegments(
        document: Document,
        profile: ChunkingProfile,
    ): List<TextSegment> {
        val splitter = RecursiveTextSplitter(profile.chunkSize, profile.chunkOverlap)
        if (document.metadata["document_format"] == "pptx") {
            val slides = document.metadata["slides"] as? List<*> ?: error("Presentation slide metadata is missing")
            return slides.flatMap { value ->
                val slide = value as Map<*, *>
                val start = slide["start_index"] as Int
                val end = slide["end_index"] as Int
                splitter.split(document.content.substring(start, end)).map { segment ->
                    segment.copy(
                        startIndex = segment.startIndex?.plus(start),
                        endIndex = segment.endIndex?.plus(start),
                        metadata =
                            mapOf(
                                "slide_number" to slide["slide_number"],
                                "section" to slide["section"],
                                "hidden" to slide["hidden"],
                            ),
                    )
                }
            }
        }
        if (document.sourceType != SourceType.MARKDOWN) return splitter.split(document.content)
        val sections = markdownSections(document.content)
        if (sections.isEmpty()) return splitter.split(document.content)
        return sections.flatMap { (text, section) ->
            splitter.split(text).map { segment -> segment.copy(metadata = mapOf("section" to section)) }
        }
    }

    private fun markdownSections(content: String): List<Pair<String, String?>> {
        val sections = mutableListOf<Pair<String, String?>>()
        val current = StringBuilder()
        var section: String? = null

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) sections += text to section
            current.clear()
        }
        for (line in content.lineSequence()) {
            val heading = Regex("^#{1,3}\\s+(.+)$").matchEntire(line.trim())
            if (heading != null) {
                flush()
                section = heading.groupValues[1].trim()
            }
            current.append(line).append('\n')
        }
        flush()
        return sections
    }
}

private data class TextSegment(
    val text: String,
    val metadata: Map<String, Any?> = emptyMap(),
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)

/** Mirrors the separator selection and overlap merge used by Python's recursive character splitter. */
private class RecursiveTextSplitter(
    private val chunkSize: Int,
    private val chunkOverlap: Int,
) {
    private val separators = listOf("\n\n", "\n", " ", "")

    fun split(text: String): List<TextSegment> {
        var searchFrom = 0
        var previousLength = 0
        return recursiveSplit(text, separators).map { chunk ->
            val searchStart = (searchFrom + previousLength - chunkOverlap).coerceAtLeast(0)
            val start = text.indexOf(chunk, searchStart).takeIf { it >= 0 }
            searchFrom = start ?: searchFrom
            previousLength = chunk.length
            TextSegment(chunk, startIndex = start, endIndex = start?.plus(chunk.length))
        }
    }

    private fun recursiveSplit(
        text: String,
        choices: List<String>,
    ): List<String> {
        val separator = choices.firstOrNull { it.isEmpty() || text.contains(it) }.orEmpty()
        val remaining = choices.drop(choices.indexOf(separator) + 1)
        val splits = splitWithLeadingSeparator(text, separator)
        val output = mutableListOf<String>()
        val good = mutableListOf<String>()

        fun flush() {
            output += merge(good)
            good.clear()
        }
        for (split in splits) {
            if (split.length < chunkSize) {
                good += split
            } else {
                if (good.isNotEmpty()) flush()
                if (remaining.isEmpty()) output += split else output += recursiveSplit(split, remaining)
            }
        }
        if (good.isNotEmpty()) flush()
        return output
    }

    private fun splitWithLeadingSeparator(
        text: String,
        separator: String,
    ): List<String> {
        if (separator.isEmpty()) return text.map(Char::toString)
        val starts = mutableListOf(0)
        var index = text.indexOf(separator)
        while (index >= 0) {
            if (index > 0) starts += index
            index = text.indexOf(separator, index + separator.length)
        }
        return starts.distinct().mapIndexedNotNull { ordinal, start ->
            text.substring(start, starts.getOrElse(ordinal + 1) { text.length }).takeIf(String::isNotEmpty)
        }
    }

    private fun merge(splits: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var total = 0
        for (split in splits) {
            if (total + split.length > chunkSize && current.isNotEmpty()) {
                current
                    .joinToString("")
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(chunks::add)
                while (current.isNotEmpty() && (total > chunkOverlap || total + split.length > chunkSize)) {
                    total -= current.removeAt(0).length
                }
            }
            current += split
            total += split.length
        }
        current
            .joinToString("")
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(chunks::add)
        return chunks
    }
}

/** Tree-sitter mirrors the Python AST and Java/Kotlin declaration walkers. */
private object SyntaxChunker {
    private val javaTypes =
        mapOf(
            "class_declaration" to "class",
            "interface_declaration" to "interface",
            "enum_declaration" to "enum",
            "record_declaration" to "record",
            "annotation_type_declaration" to "annotation",
        )
    private val javaMembers =
        mapOf(
            "method_declaration" to "method",
            "constructor_declaration" to "constructor",
            "compact_constructor_declaration" to "constructor",
            "field_declaration" to "field",
        )
    private val kotlinTypes =
        mapOf(
            "class_declaration" to "class",
            "object_declaration" to "object",
            "interface_declaration" to "interface",
            "enum_class_body" to "enum",
        )
    private val kotlinMembers = mapOf("function_declaration" to "function", "property_declaration" to "property")

    fun python(content: String): List<TextSegment> {
        val parsed = parse(content, TreeSitterPython())
        val root = parsed.first
        if (root.hasError()) return listOf(TextSegment(content, mapOf("language" to "python", "parse_error" to true)))
        val source = parsed.second
        val symbols =
            root.namedChildren().mapNotNull { node ->
                val actual =
                    if (node.type ==
                        "decorated_definition"
                    ) {
                        node.namedChildren().firstOrNull { it.type in setOf("class_definition", "function_definition") }
                    } else {
                        node
                    }
                actual?.takeIf { it.type in setOf("class_definition", "function_definition") }
            }
        val chunks = mutableListOf<TextSegment>()
        val lines = if (content.isEmpty()) emptyList() else content.split('\n').let { if (content.endsWith('\n')) it.dropLast(1) else it }
        val firstLine = symbols.minOfOrNull { it.startPoint.row + 1 } ?: lines.size + 1
        val preamble = lines.take(firstLine - 1).joinToString("\n").trim()
        if (preamble.isNotEmpty()) chunks += TextSegment(preamble, symbolMetadata("python", "<module>", "module", 1, firstLine - 1))
        for (node in symbols) {
            val name = node.field("name")?.text(source) ?: "<anonymous>"
            val type =
                if (node.type ==
                    "class_definition"
                ) {
                    "class"
                } else if (node.text(source).trimStart().startsWith("async def")) {
                    "async_function"
                } else {
                    "function"
                }
            val text = node.text(source).trim()
            if (text.isNotEmpty()) {
                chunks +=
                    TextSegment(text, symbolMetadata("python", name, type, node.startPoint.row + 1, node.endPoint.row + 1))
            }
        }
        return chunks.ifEmpty { listOf(TextSegment(content, symbolMetadata("python", "<module>", "module", 1, lines.size))) }
    }

    fun java(content: String): List<TextSegment>? {
        val (root, source) = parse(content, TreeSitterJava())
        if (root.hasError()) return null
        val declarations = root.namedChildren().filter { it.type in javaTypes }
        val chunks = mutableListOf<TextSegment>()
        val first = declarations.minOfOrNull(TSNode::getStartByte) ?: source.size
        val preamble = String(source.copyOfRange(0, first), StandardCharsets.UTF_8).trim()
        if (preamble.isNotEmpty()) {
            chunks +=
                TextSegment(preamble, symbolMetadata("java", "<module>", "module", 1, preamble.count { it == '\n' } + 1))
        }
        declarations.forEach { collectJava(it, source, emptyList(), chunks) }
        return chunks.ifEmpty {
            listOf(
                TextSegment(content, symbolMetadata("java", "<module>", "module", 1, content.count { it == '\n' } + 1)),
            )
        }
    }

    fun kotlin(content: String): List<TextSegment>? {
        val (root, source) = parse(content, TreeSitterKotlin())
        if (root.hasError()) return null
        val chunks = mutableListOf<TextSegment>()
        root.namedChildren().forEach { collectKotlin(it, source, emptyList(), chunks) }
        return chunks.ifEmpty {
            listOf(
                TextSegment(
                    content,
                    symbolMetadata(
                        "kotlin",
                        "<module>",
                        "module",
                        1,
                        content.count { it == '\n' } + 1,
                    ),
                ),
            )
        }
    }

    private fun collectJava(
        node: TSNode,
        source: ByteArray,
        ancestors: List<String>,
        output: MutableList<TextSegment>,
    ) {
        val name =
            (node.field("name") ?: node.namedChildren().firstOrNull { it.type in setOf("type_identifier", "simple_identifier") })
                ?.text(source) ?: "<anonymous>"
        val symbol = (ancestors + name).joinToString(".")
        val body = node.field("body")
        val header = String(source.copyOfRange(node.startByte, body?.startByte ?: node.endByte), StandardCharsets.UTF_8).trim()
        if (header.isNotEmpty()) {
            output +=
                TextSegment(
                    header,
                    symbolMetadata(
                        "java",
                        symbol,
                        javaTypes.getValue(node.type),
                        node.startPoint.row + 1,
                        body?.startPoint?.row?.plus(1) ?: node.endPoint.row + 1,
                        ancestors.joinToString(".").ifBlank { null },
                    ),
                )
        }
        body?.namedChildren()?.forEach { child ->
            when {
                child.type in javaTypes -> {
                    collectJava(child, source, ancestors + name, output)
                }

                child.type in javaMembers -> {
                    val memberName =
                        child.field("name")?.text(source)
                            ?: child
                                .namedChildren()
                                .firstOrNull { it.type == "variable_declarator" }
                                ?.field("name")
                                ?.text(source)
                            ?: "<constructor>"
                    val text = child.text(source).trim()
                    if (text.isNotEmpty()) {
                        output +=
                            TextSegment(
                                text,
                                symbolMetadata(
                                    "java",
                                    "$symbol.$memberName",
                                    javaMembers.getValue(child.type),
                                    child.startPoint.row + 1,
                                    child.endPoint.row + 1,
                                    symbol,
                                ),
                            )
                    }
                }
            }
        }
    }

    private fun collectKotlin(
        node: TSNode,
        source: ByteArray,
        parents: List<String>,
        output: MutableList<TextSegment>,
    ) {
        val type = kotlinTypes[node.type] ?: kotlinMembers[node.type]
        if (type == null) {
            node.namedChildren().forEach { collectKotlin(it, source, parents, output) }
            return
        }
        val name =
            (node.field("name") ?: node.namedChildren().firstOrNull { it.type in setOf("type_identifier", "simple_identifier") })
                ?.text(source) ?: "<anonymous>"
        val symbol = (parents + name).joinToString(".")
        val text = node.text(source).trim()
        if (text.isNotEmpty()) {
            output +=
                TextSegment(
                    text,
                    symbolMetadata(
                        "kotlin",
                        symbol,
                        type,
                        node.startPoint.row + 1,
                        node.endPoint.row + 1,
                        parents
                            .joinToString(
                                ".",
                            ).ifBlank {
                                null
                            },
                    ),
                )
        }
        val childParents = if (node.type in kotlinTypes) parents + name else parents
        node.namedChildren().forEach { collectKotlin(it, source, childParents, output) }
    }

    private fun symbolMetadata(
        language: String,
        symbol: String,
        type: String,
        start: Int,
        end: Int,
        enclosing: String? = null,
    ): Map<String, Any?> =
        mapOf("language" to language, "symbol" to symbol, "symbol_type" to type, "line_start" to start, "line_end" to end) +
            (enclosing?.let { mapOf("enclosing_symbol" to it) } ?: emptyMap())

    private fun parse(
        content: String,
        language: TSLanguage,
    ): Pair<TSNode, ByteArray> {
        val parser = TSParser()
        require(parser.setLanguage(language)) { "Unsupported Tree-sitter grammar" }
        val source = content.toByteArray(StandardCharsets.UTF_8)
        return parser.parseString(null, content).rootNode to source
    }

    private fun TSNode.namedChildren(): List<TSNode> = (0 until namedChildCount).mapNotNull(::getNamedChild)

    private fun TSNode.field(name: String): TSNode? = getChildByFieldName(name)?.takeUnless(TSNode::isNull)

    private fun TSNode.text(source: ByteArray): String = String(source.copyOfRange(startByte, endByte), StandardCharsets.UTF_8)
}
