package com.dex.ragpoc.parsing

import com.dex.ragpoc.domain.SourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WordDocumentLoaderTest {
    @TempDir lateinit var temp: Path

    private fun fixture(): Path = Path.of("src/test/resources/fixtures/word-guide.docx")

    @Test
    fun `extracts ordered blocks and linked image bytes`() {
        val document = DocumentLoaderRegistry().loadDocument(fixture())
        assertEquals(SourceType.WORD, document.sourceType)
        assertEquals("Operations Guide", document.title)
        assertTrue(document.content.indexOf("# Deployment") < document.content.indexOf("| Setting | Value |"))
        assertTrue("- Verify readiness" in document.content)
        assertTrue(document.content.indexOf("[PAGE BREAK]") < document.content.indexOf("# Rollback"))
        assertTrue("ניתן לצרף קבצים" in document.content)
        assertEquals(1, document.metadata["page_break_count"])
        assertEquals(1, document.metadata["image_count"])
        val blocks = document.metadata["blocks"] as List<*>
        val anchors = document.metadata["image_anchors"] as List<*>
        val anchor = anchors.single() as Map<*, *>
        val asset = document.assets.single()
        assertEquals(true, anchor["available"])
        assertEquals("image/png", anchor["media_type"])
        assertEquals("Deployment", anchor["section"])
        assertEquals("Console screenshot", anchor["caption"])
        assertEquals(anchor["anchor_id"], asset.anchorId)
        assertEquals(anchor["block_id"], asset.blockId)
        assertEquals(anchor["content_hash"], asset.contentHash)
        assertEquals(anchor["byte_size"], asset.content.size)
        assertEquals((blocks[asset.blockOrdinal!!] as Map<*, *>)["start_index"], asset.sourceIndex)
        assertEquals(64, document.contentHash?.length)
        val (documents, skipped) = DocumentLoaderRegistry().loadDirectory(fixture().parent)
        assertEquals(listOf(fixture().toString()), documents.map { it.sourcePath })
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `package and asset hashes change when only image bytes change`() {
        val before = WordDocumentLoader.load(fixture())
        val modified = temp.resolve("modified.docx")
        rewriteZip(fixture(), modified) { name, bytes -> if (name.startsWith("word/media/")) byteArrayOf(1, 2, 3) else bytes }
        val after = WordDocumentLoader.load(modified)
        assertEquals(before.content, after.content)
        assertNotEquals(before.contentHash, after.contentHash)
        assertNotEquals(before.assets.single().contentHash, after.assets.single().contentHash)
    }

    @Test
    fun `rejects fake missing-part traversal and corrupt packages`() {
        val fake = temp.resolve("fake.docx").also { Files.writeString(it, "not a zip") }
        assertThrows(WordDocumentException::class.java) { WordDocumentLoader.load(fake) }
        val missing = temp.resolve("missing.docx")
        ZipOutputStream(Files.newOutputStream(missing)).use {
            it.putNextEntry(ZipEntry("notes.txt"))
            it.write(byteArrayOf(1))
            it.closeEntry()
        }
        assertThrows(WordDocumentException::class.java) { WordDocumentLoader.load(missing) }
        val unsafe = temp.resolve("unsafe.docx")
        rewriteZip(fixture(), unsafe) { _, bytes -> bytes }
        // Append a traversal entry while preserving the original valid package.
        val entries =
            ZipFile(unsafe.toFile()).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .map {
                        it.name to
                            zip.getInputStream(it).readAllBytes()
                    }.toList()
            }
        ZipOutputStream(Files.newOutputStream(unsafe)).use { zip ->
            (entries + ("../escape.bin" to byteArrayOf(1))).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertTrue(assertThrows(WordDocumentException::class.java) { WordDocumentLoader.load(unsafe) }.message!!.contains("unsafe entry"))

        val corrupt = temp.resolve("corrupt.docx")
        val bytes = Files.readAllBytes(fixture())
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val header = (0..bytes.size - 46).first { offset -> signature.indices.all { bytes[offset + it] == signature[it] } }
        bytes[header + 16] = (bytes[header + 16].toInt() xor 0x01).toByte()
        Files.write(corrupt, bytes)
        assertTrue(assertThrows(WordDocumentException::class.java) { WordDocumentLoader.load(corrupt) }.message!!.contains("corrupt entry"))
    }

    private fun rewriteZip(
        source: Path,
        destination: Path,
        rewrite: (String, ByteArray) -> ByteArray,
    ) {
        ZipFile(source.toFile()).use { original ->
            ZipOutputStream(Files.newOutputStream(destination)).use { output ->
                original.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(rewrite(entry.name, original.getInputStream(entry).readAllBytes()))
                    output.closeEntry()
                }
            }
        }
    }
}
