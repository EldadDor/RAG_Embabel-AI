package com.yourorg.ragpoc.ingestion

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.reader.ExtractedTextFormatter
import org.springframework.ai.reader.pdf.PagePdfDocumentReader
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

/**
 * Ingests a PDF from the classpath, splits into chunks, embeds, and stores in Qdrant.
 *
 * Python mapping:
 *   PagePdfDocumentReader(pagesPerDocument=1)  → split_pages=True (PyPDFLoader)
 *   TokenTextSplitter(512, 80)                 → RecursiveCharacterTextSplitter(512, 80)
 *   vectorStore.add(chunks)                    → Chroma.from_documents(...)
 *
 * STOP CONDITION (Task 3.1): If TokenTextSplitter constructor signature differs from
 *   TokenTextSplitter(chunkSize, chunkOverlap, minChunkSizeChars, maxNumChunks, keepSeparator)
 *   report the actual signature before proceeding.
 */
@Service
class PdfIngestionService(
    private val vectorStore: VectorStore,
    private val embeddingModel: EmbeddingModel,
) {
    private val logger = LoggerFactory.getLogger(PdfIngestionService::class.java)

    fun ingestPdf(resourcePath: String): Int {
        logger.info("Starting PDF ingestion: {}", resourcePath)

        val reader = PagePdfDocumentReader(
            ClassPathResource(resourcePath),
            PdfDocumentReaderConfig.builder()
                .withPageTopMargin(0)
                .withPageExtractedTextFormatter(
                    ExtractedTextFormatter.builder()
                        .withNumberOfTopTextLinesToDelete(0)
                        .build()
                )
                .withPagesPerDocument(1) // mirrors split_pages=True
                .build()
        )

        val rawDocuments: List<Document> = reader.get()
        logger.info("Read {} raw pages from PDF", rawDocuments.size)

        // Verify signature: TokenTextSplitter(chunkSize, chunkOverlap, minChunkSizeChars, maxNumChunks, keepSeparator)
        val splitter = TokenTextSplitter(512, 80, 5, 10000, true)
        val chunks: List<Document> = splitter.apply(rawDocuments)
        logger.info("Split into {} chunks", chunks.size)

        vectorStore.add(chunks)
        logger.info("Stored {} chunks in vector store", chunks.size)

        return chunks.size
    }
}
