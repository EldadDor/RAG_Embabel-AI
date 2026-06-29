package com.dex.ragpoc.ingestion

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.reader.ExtractedTextFormatter
import org.springframework.ai.reader.pdf.PagePdfDocumentReader
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.io.File

/**
 * Task 3.1 — PdfIngestionService
 *
 * Ingests a PDF, splits into token-bounded chunks, and stores in Qdrant.
 *
 * Python mapping:
 *   PyPDFLoader(split_pages=True)              -> PagePdfDocumentReader(pagesPerDocument=1)
 *   RecursiveCharacterTextSplitter(512, 80)    -> TokenTextSplitter(512, 80, 5, 10000, true)
 *   Chroma.from_documents(...)                 -> vectorStore.add(chunks)
 *
 * STOP CONDITION (Task 3.1):
 *   If TokenTextSplitter constructor differs from (chunkSize, chunkOverlap, minChunkSizeChars,
 *   maxNumChunks, keepSeparator) — report and stop.
 */
@Service
class PdfIngestionService(
    private val vectorStore: VectorStore,
) {
    private val logger = LoggerFactory.getLogger(PdfIngestionService::class.java)

    /**
     * Resolves the resource: tries classpath first, then filesystem.
     * This allows both `sample-docs/infra-codebase.pdf` (classpath) and
     * absolute/relative filesystem paths to work.
     */
    private fun resolveResource(path: String): Resource {
        val classpathRes = ClassPathResource(path)
        if (classpathRes.exists()) return classpathRes
        val fileRes = FileSystemResource(File(path))
        if (fileRes.exists()) return fileRes
        // try relative to working directory
        val cwdRes = FileSystemResource(File(System.getProperty("user.dir"), path))
        if (cwdRes.exists()) return cwdRes
        error("PDF resource not found on classpath or filesystem: $path")
    }

    fun ingestPdf(resourcePath: String): Int {
        logger.info("Starting PDF ingestion: {}", resourcePath)

        val resource = resolveResource(resourcePath)
        logger.info("Resolved PDF resource: {}", resource.description)

        val config = PdfDocumentReaderConfig.builder()
            .withPageTopMargin(0)
            .withPageExtractedTextFormatter(
                ExtractedTextFormatter.builder()
                    .withNumberOfTopTextLinesToDelete(0)
                    .build()
            )
            .withPagesPerDocument(1) // mirrors split_pages=True
            .build()

        val reader = PagePdfDocumentReader(resource, config)
        val rawDocuments: List<Document> = reader.get()
        logger.info("Read {} raw pages from PDF", rawDocuments.size)

        val splitter = TokenTextSplitter(
            /* chunkSize         */ 512,
            /* chunkOverlap      */ 80,
            /* minChunkSizeChars */ 5,
            /* maxNumChunks      */ 10000,
            /* keepSeparator     */ true,
        )
        val chunks: List<Document> = splitter.apply(rawDocuments)
        logger.info("Split into {} chunks", chunks.size)

        vectorStore.add(chunks)
        logger.info("Stored {} chunks in pgvector", chunks.size)

        return chunks.size
    }
}
