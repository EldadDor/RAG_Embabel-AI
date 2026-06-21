package com.yourorg.ragpoc.ingestion

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoint for triggering PDF ingestion.
 *
 * Done-when gate (Task 3.2):
 *   curl -s -X POST 'localhost:8080/api/ingest?path=sample-docs/infra-codebase.pdf'
 *   → JSON {"chunks": N} with N > 0
 *   AND chunks visible at http://localhost:6333/dashboard
 */
@RestController
@RequestMapping("/api/ingest")
class IngestionController(
    private val pdfIngestionService: PdfIngestionService,
) {
    @PostMapping
    fun ingest(@RequestParam path: String): ResponseEntity<Map<String, Any>> {
        val chunkCount = pdfIngestionService.ingestPdf(path)
        return ResponseEntity.ok(mapOf("chunks" to chunkCount, "path" to path))
    }
}
