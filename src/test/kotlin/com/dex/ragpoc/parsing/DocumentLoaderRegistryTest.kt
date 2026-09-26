package com.dex.ragpoc.parsing

import com.dex.ragpoc.domain.SourceType
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocumentLoaderRegistryTest {
    private val registry = DocumentLoaderRegistry()

    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `loads text markdown and visible html with Python-compatible metadata`() {
        val text = tempDirectory.resolve("notes.txt").also { Files.writeString(it, "Hello world") }
        val markdown = tempDirectory.resolve("readme.mdx").also { Files.writeString(it, "# My Title\n\nBody") }
        val html =
            tempDirectory.resolve("page.html").also {
                Files.writeString(it, "<title>Page Title</title><script>hidden()</script><h1>Heading</h1><p>Text</p>")
            }

        assertEquals(SourceType.TEXT, registry.loadDocument(text).sourceType)
        assertEquals("My Title", registry.loadDocument(markdown).title)
        val htmlDocument = registry.loadDocument(html)
        assertEquals("Page Title", htmlDocument.title)
        assertTrue("Heading\nText" in htmlDocument.content)
        assertFalse("hidden" in htmlDocument.content)
    }

    @Test
    fun `rejects unsupported and oversized paths`() {
        val unsupported = tempDirectory.resolve("archive.zip").also { Files.writeString(it, "content") }
        val oversized = tempDirectory.resolve("large.txt").also { Files.writeString(it, "content") }

        assertThrows(UnsupportedDocumentTypeException::class.java) { registry.loadDocument(unsupported) }
        assertThrows(DocumentTooLargeException::class.java) { registry.loadDocument(oversized, 3) }
    }

    @Test
    fun `scans recursively in order and excludes build output`() {
        val source = Files.createDirectories(tempDirectory.resolve("src/main"))
        Files.writeString(source.resolve("b.txt"), "B")
        Files.writeString(source.resolve("a.md"), "A")
        val target = Files.createDirectories(tempDirectory.resolve("target"))
        Files.writeString(target.resolve("generated.txt"), "generated")

        val (documents, skipped) = registry.loadDirectory(tempDirectory, recursive = true)

        assertEquals(listOf("a.md", "b.txt"), documents.map { Path.of(it.sourcePath).fileName.toString() })
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `preserves PDF page markers and page count`() {
        val pdf = tempDirectory.resolve("sample.pdf")
        PDDocument().use { document ->
            document.documentInformation.title = "Fixture PDF"
            document.addPage(PDPage())
            document.addPage(PDPage())
            document.pages.forEachIndexed { index, page ->
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    stream.newLineAtOffset(72f, 720f)
                    stream.showText("Page ${index + 1}")
                    stream.endText()
                }
            }
            document.save(pdf.toFile())
        }

        val parsed = registry.loadDocument(pdf)

        assertEquals(SourceType.PDF, parsed.sourceType)
        assertEquals("Fixture PDF", parsed.title)
        assertEquals(2, parsed.metadata["total_pages"])
        assertTrue("[PAGE 1]\nPage 1" in parsed.content)
        assertTrue("[PAGE 2]\nPage 2" in parsed.content)
    }
}
