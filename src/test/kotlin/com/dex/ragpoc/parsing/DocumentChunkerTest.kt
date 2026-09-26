package com.dex.ragpoc.parsing

import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocumentChunkerTest {
    @TempDir lateinit var temp: Path

    private fun code(
        language: String,
        content: String,
    ) = Document("doc", "Example.$language", SourceType.CODE, content, "Example", mapOf("language" to language))

    @Test
    fun `uses recursive overlap and profile scoped ids`() {
        val document = Document("doc", "notes.txt", SourceType.TEXT, "word ".repeat(80), "Notes", mapOf("page" to 3))
        val chunker =
            DocumentChunker(
                mapOf(
                    "default" to ChunkingProfile(chunkSize = 80, chunkOverlap = 20),
                    "experiment-small" to ChunkingProfile(chunkSize = 40, chunkOverlap = 10),
                ),
            )
        val default = chunker.chunk(document)
        val named = chunker.chunk(document, "experiment-small")
        assertTrue(default.size > 1)
        assertTrue(named.size > default.size)
        assertEquals("doc:0", default.first().chunkId)
        assertEquals("doc:experiment-small:0", named.first().chunkId)
        assertEquals(3, default.first().page)
        assertEquals("Notes", default.first().title)
        assertEquals(0, default.first().metadata["start_index"])
        assertNotEquals(default[0].chunkId, default[1].chunkId)
        assertThrows(IllegalArgumentException::class.java) { chunker.chunk(document, "missing") }
        assertThrows(IllegalArgumentException::class.java) { ChunkingProfile(chunkSize = 20, chunkOverlap = 20) }
    }

    @Test
    fun `matches Python recursive splitter boundaries and offsets`() {
        val content = "alpha beta gamma delta epsilon zeta eta theta iota kappa"
        val document = Document("doc", "notes.txt", SourceType.TEXT, content)
        val chunks = DocumentChunker(mapOf("default" to ChunkingProfile(chunkSize = 20, chunkOverlap = 5))).chunk(document)
        assertEquals(listOf("alpha beta gamma", "delta epsilon zeta", "zeta eta theta iota", "iota kappa"), chunks.map { it.text })
        assertEquals(listOf(0, 17, 31, 46), chunks.map { it.metadata["start_index"] })
    }

    @Test
    fun `preserves markdown sections`() {
        val document = Document("doc", "readme.md", SourceType.MARKDOWN, "# Title\n\nIntro\n\n## Section A\n\nContent A\n", "Title")
        val chunks = DocumentChunker().chunk(document)
        assertEquals(listOf("Title", "Section A"), chunks.mapNotNull { it.section }.distinct())
    }

    @Test
    fun `extracts Python preamble and function with line metadata`() {
        val source = "import os\n\ndef hello(name):\n    return f'Hi {name}'\n"
        val chunks = DocumentChunker().chunk(code("python", source))
        assertEquals("<module>", chunks.first().metadata["symbol"])
        val function = chunks.first { it.metadata["symbol"] == "hello" }
        assertEquals("function", function.metadata["symbol_type"])
        assertEquals(3, function.metadata["line_start"])
        assertEquals(4, function.metadata["line_end"])
        assertTrue(function.text.contains("return f'Hi {name}'"))
        val broken = DocumentChunker().chunk(code("python", "def broken(:"))
        assertEquals(true, broken.single().metadata["parse_error"])
        val async = DocumentChunker().chunk(code("python", "async def fetch():\n    return 1\n"))
        assertEquals("async_function", async.single().metadata["symbol_type"])
    }

    @Test
    fun `extracts Java type and method symbols with fallback`() {
        val source = """package example;

public class Example {
    private final String name;

    public String getName() {
        return name;
    }
}
"""
        val chunks = DocumentChunker().chunk(code("java", source))
        val method = chunks.first { it.metadata["symbol"] == "Example.getName" }
        assertEquals("method", method.metadata["symbol_type"])
        assertEquals("Example", method.metadata["enclosing_symbol"])
        assertEquals(6, method.metadata["line_start"])
        assertEquals(8, method.metadata["line_end"])
        assertTrue(chunks.any { it.metadata["symbol"] == "Example.name" && it.metadata["symbol_type"] == "field" })
        val broken = DocumentChunker().chunk(code("java", "class Broken {"))
        assertFalse(broken.any { it.metadata.containsKey("symbol") })
    }

    @Test
    fun `extracts Kotlin type and function symbols with fallback`() {
        val source = """package example

class Example {
    fun greet(name: String): String {
        return name
    }
}
"""
        val chunks = DocumentChunker().chunk(code("kotlin", source))
        val function = chunks.first { it.metadata["symbol"] == "Example.greet" }
        assertEquals("function", function.metadata["symbol_type"])
        assertEquals("Example", function.metadata["enclosing_symbol"])
        assertEquals(4, function.metadata["line_start"])
        assertEquals(6, function.metadata["line_end"])
        val broken = DocumentChunker().chunk(code("kotlin", "class Broken {"))
        assertFalse(broken.any { it.metadata.containsKey("symbol") })
    }

    @Test
    fun `loads and chunks a Java source with stable provenance`() {
        val source = temp.resolve("Example.java")
        Files.writeString(source, "class Example { String greet() { return \"hello\"; } }")
        val loaded = DocumentLoaderRegistry().loadDocument(source)

        val first = DocumentChunker().chunk(loaded)
        val second = DocumentChunker().chunk(loaded)

        assertEquals(first.map { it.chunkId }, second.map { it.chunkId })
        assertTrue(first.any { it.metadata["symbol"] == "Example.greet" })
        assertTrue(first.all { it.documentId == loaded.documentId && it.sourcePath == source.toString() })
    }
}
