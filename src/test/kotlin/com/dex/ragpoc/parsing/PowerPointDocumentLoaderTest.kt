package com.dex.ragpoc.parsing

import com.dex.ragpoc.domain.SourceType
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class PowerPointDocumentLoaderTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `extracts ordered Unicode tables groups notes and repeated embedded pictures`() {
        val source = PresentationFixtures.write(temp.resolve("guide.pptx"))
        val document = DocumentLoaderRegistry().loadDocument(source)
        assertEquals(SourceType.UNKNOWN, document.sourceType)
        assertEquals("pptx", document.metadata["document_format"])
        assertEquals("Operations Slides", document.title)
        assertEquals(4, document.metadata["total_slides"])
        assertTrue(document.content.indexOf("Closing") < document.content.indexOf("Overview"))
        assertTrue(document.content.indexOf("Overview") < document.content.indexOf("| Setting"))
        assertTrue(document.content.indexOf("Nested group") < document.content.indexOf("Speaker explanation"))
        assertTrue("ניתן לצרף קבצים" in document.content)
        assertTrue("- Check readiness" in document.content)
        assertTrue("Local \\| stable" in document.content)
        assertTrue("[SLIDE 4]" in document.content)
        val slides = document.metadata["slides"] as List<*>
        assertEquals(true, (slides[2] as Map<*, *>)["hidden"])
        val blocks = document.metadata["blocks"] as List<*>
        blocks.forEach { value ->
            val block = value as Map<*, *>
            val text = document.content.substring(block["start_index"] as Int, block["end_index"] as Int)
            assertTrue(text.isNotBlank())
        }
        assertEquals(2, document.assets.size)
        val first = document.assets.first()
        assertArrayEquals(PresentationFixtures.image(), first.content)
        assertEquals("image/png", first.mediaType)
        assertEquals("Slide 3", first.section)
        assertNotEquals(first.anchorId, document.assets.last().anchorId)
        assertEquals(first.contentHash, document.assets.last().contentHash)
        assertTrue(first.relationshipId.startsWith("slide-3:"))
        assertTrue(document.content.substring(first.sourceIndex!!).startsWith("[IMAGE"))
    }

    @Test
    fun `named-profile chunks retain absolute offsets and stay on one slide`() {
        val document = DocumentLoaderRegistry().loadDocument(PresentationFixtures.write(temp.resolve("guide.pptx")))
        val chunker = DocumentChunker(mapOf("default" to ChunkingProfile(), "small" to ChunkingProfile(chunkSize = 40, chunkOverlap = 5)))
        val chunks = chunker.chunk(document, "small")
        val slides = (document.metadata["slides"] as List<*>).map { it as Map<*, *> }
        assertEquals(setOf(1, 2, 3, 4), chunks.map { it.page }.toSet())
        chunks.forEach { chunk ->
            val slide = slides[chunk.page!! - 1]
            val start = chunk.metadata["start_index"] as Int
            val end = chunk.metadata["end_index"] as Int
            assertTrue(start >= slide["start_index"] as Int)
            assertTrue(end <= slide["end_index"] as Int)
            assertEquals(chunk.text, document.content.substring(start, end))
            assertEquals("Slide ${chunk.page}", chunk.section)
            assertTrue(chunk.chunkId.contains(":small:"))
        }
        assertEquals(chunks, chunker.chunk(document, "small"))
    }

    @Test
    fun `image-only changes invalidate package and asset hashes without changing text`() {
        val source = PresentationFixtures.write(temp.resolve("guide.pptx"))
        val before = PowerPointDocumentLoader().load(source)
        rewrite(
            source,
            source.resolveSibling("changed.pptx"),
        ) { name, bytes -> if (name.startsWith("ppt/media/")) PresentationFixtures.image(Color.BLUE) else bytes }
        val after = PowerPointDocumentLoader().load(source.resolveSibling("changed.pptx"))
        assertEquals(before.content, after.content)
        assertNotEquals(before.contentHash, after.contentHash)
        assertNotEquals(before.assets.first().contentHash, after.assets.first().contentHash)
    }

    @Test
    fun `directory registry finds PPTX recursively and respects excluded folders`() {
        PresentationFixtures.write(temp.resolve("top.pptx"))
        PresentationFixtures.write(temp.resolve("nested/deep.PPTX"))
        PresentationFixtures.write(temp.resolve("target/ignored.pptx"))
        val registry = DocumentLoaderRegistry()
        assertEquals(1, registry.scanDirectory(temp).size)
        assertEquals(2, registry.scanDirectory(temp, true).size)
    }

    @Test
    fun `rejects invalid missing macro unsafe duplicate and corrupt packages`() {
        val fixture = PresentationFixtures.write(temp.resolve("valid.pptx"))
        val fake = Files.writeString(temp.resolve("fake.pptx"), "not a ZIP or an encrypted presentation")
        assertThrows(PowerPointDocumentException::class.java) { PowerPointDocumentLoader().load(fake) }
        val missing = temp.resolve("missing.pptx")
        writeZip(missing, listOf("notes.txt" to byteArrayOf(1)))
        assertThrows(PowerPointDocumentException::class.java) { PowerPointDocumentLoader().load(missing) }
        val macro = temp.resolve("macro.pptx")
        rewrite(fixture, macro) { name, bytes ->
            if (name == "[Content_Types].xml") {
                bytes
                    .toString(Charsets.UTF_8)
                    .replace(
                        "presentationml.presentation.main+xml",
                        "presentationml.presentation.macroEnabled.main+xml",
                    ).toByteArray()
            } else {
                bytes
            }
        }
        assertThrows(PowerPointDocumentException::class.java) { PowerPointDocumentLoader().load(macro) }
        val entries = entries(fixture)
        listOf("../escape.bin", "/absolute.bin", "C:/absolute.bin", "ppt\\escape.bin").forEachIndexed { index, name ->
            val unsafe = temp.resolve("unsafe$index.pptx")
            writeZip(unsafe, entries + (name to byteArrayOf(1)))
            assertThrows(PowerPointDocumentException::class.java) { PowerPointDocumentLoader().load(unsafe) }
        }
        val duplicate = temp.resolve("duplicate.pptx")
        writeZip(duplicate, entries + entries.first())
        assertThrows(PowerPointDocumentException::class.java) { PowerPointDocumentLoader().load(duplicate) }
        val corrupt = temp.resolve("corrupt.pptx")
        val bytes = Files.readAllBytes(fixture)
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val header = (0..bytes.size - 46).first { offset -> signature.indices.all { bytes[offset + it] == signature[it] } }
        bytes[header + 16] = (bytes[header + 16].toInt() xor 1).toByte()
        Files.write(corrupt, bytes)
        assertTrue(
            assertThrows(
                PowerPointDocumentException::class.java,
            ) { PowerPointDocumentLoader().load(corrupt) }.message!!.contains("corrupt"),
        )
    }

    @Test
    fun `rejects XML entities malformed XML and escaping or missing relationship targets`() {
        val fixture = PresentationFixtures.write(temp.resolve("valid.pptx"))
        val xmlVariants =
            listOf(
                "<!DOCTYPE p:presentation [<!ENTITY x SYSTEM 'file:///missing'>]>" +
                    "<p:presentation " +
                    "xmlns:p='http://schemas.openxmlformats.org/presentationml/2006/main'>&x;</p:presentation>",
                "<broken>",
            )
        xmlVariants.forEachIndexed { index, xml ->
            val bad = temp.resolve("bad$index.pptx")
            rewrite(fixture, bad) { name, bytes -> if (name == "ppt/presentation.xml") xml.toByteArray() else bytes }
            assertThrows(PowerPointDocumentException::class.java) { PowerPointDocumentLoader().load(bad) }
        }
        listOf("../../../../escape.xml", "slides/missing.xml").forEachIndexed { index, target ->
            val bad = temp.resolve("relationship$index.pptx")
            rewrite(fixture, bad) { name, bytes ->
                if (name == "ppt/_rels/presentation.xml.rels") {
                    bytes.toString(Charsets.UTF_8).replace("slides/slide1.xml", target).toByteArray()
                } else {
                    bytes
                }
            }
            assertThrows(PowerPointDocumentException::class.java) { PowerPointDocumentLoader().load(bad) }
        }
    }

    @Test
    fun `enforces source entry expansion slide shape nesting and extracted-text limits`() {
        val fixture = PresentationFixtures.write(temp.resolve("valid.pptx"))
        listOf(
            PresentationLimits(maxSourceBytes = 10),
            PresentationLimits(maxEntries = 2),
            PresentationLimits(maxEntryBytes = 10),
            PresentationLimits(maxExpandedBytes = 20),
            PresentationLimits(maxSlides = 1),
            PresentationLimits(maxGroupDepth = 0),
            PresentationLimits(maxShapes = 2),
            PresentationLimits(maxCharacters = 20),
        ).forEach { limits -> assertThrows(PowerPointDocumentException::class.java) { PowerPointDocumentLoader(limits).load(fixture) } }
    }

    @Test
    fun `linked pictures are unavailable anchors without fetching remote bytes`() {
        val fixture = PresentationFixtures.write(temp.resolve("valid.pptx"))
        val linked = temp.resolve("linked.pptx")
        rewrite(fixture, linked) { name, bytes ->
            val xml = bytes.toString(Charsets.UTF_8)
            when (name) {
                "ppt/slides/slide2.xml" -> {
                    xml.replace(Regex("r:embed=\"([^\"]+)\""), "r:link=\"rIdExternal\"").toByteArray()
                }

                "ppt/slides/_rels/slide2.xml.rels" -> {
                    xml
                        .replace(
                            "</Relationships>",
                            "<Relationship Id=\"rIdExternal\" " +
                                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
                                "Target=\"http://127.0.0.1:1/private.png\" TargetMode=\"External\"/></Relationships>",
                        ).toByteArray()
                }

                else -> {
                    bytes
                }
            }
        }
        val document = PowerPointDocumentLoader().load(linked)
        assertTrue(document.assets.isEmpty())
        val anchors = document.metadata["image_anchors"] as List<*>
        assertEquals(2, anchors.size)
        anchors.forEach { value ->
            val anchor = value as Map<*, *>
            assertEquals(true, anchor["external"])
            assertEquals(false, anchor["available"])
        }
    }

    private fun entries(path: Path) =
        ZipFile(path.toFile()).use { zip ->
            zip
                .entries()
                .asSequence()
                .map {
                    it.name to
                        zip.getInputStream(it).use { input -> input.readAllBytes() }
                }.toList()
        }

    private fun writeZip(
        path: Path,
        entries: List<Pair<String, ByteArray>>,
    ) = ZipArchiveOutputStream(path).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putArchiveEntry(ZipArchiveEntry(name))
            zip.write(bytes)
            zip.closeArchiveEntry()
        }
    }

    private fun rewrite(
        source: Path,
        destination: Path,
        rewrite: (String, ByteArray) -> ByteArray,
    ) = writeZip(
        destination,
        entries(source).map { (name, bytes) ->
            name to
                rewrite(name, bytes)
        },
    )
}
