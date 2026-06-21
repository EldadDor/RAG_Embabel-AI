package com.dex.ragpoc.ingestion

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Task 3.2 — Ingestion REST endpoint.
 *
 * Done-when gate:
 *   curl -s -X POST 'localhost:8080/api/ingest?path=sample-docs/infra-codebase.pdf'
 *   -> {"chunks": N, "path": "..."}  with N > 0
 *   AND collection visible at http://localhost:6333/dashboard
 */
@RestController
@RequestMapping("/api/ingest")
class IngestionController(
    private val pdfIngestionService: PdfIngestionService,
) {
    @PostMapping
    fun ingest(@RequestParam path: String): ResponseEntity<Map<String, Any>> {
        val chunkCount = pdfIngestionService.ingestPdf(path)
        return ResponseEntity.ok(
            mapOf(
                "chunks" to chunkCount,
                "path" to path,
                "status" to "ingested",
            )
        )
    }
}
