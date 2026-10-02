# Next phase backlog

**Last reviewed:** 2026-10-02

| ID | Task | Entry condition | Done when |
| --- | --- | --- | --- |
| RDP-18-RT | Browser rendering and configured proxy/load-balancer acceptance | RDP-18 direct cross-backend HTTP and actual frontend API/SSE adapter checks passed; no frontend/proxy listener found | Start the existing frontend against updated Kotlin or a common proxy and verify rendered workspace/chat state; test actual load-balancer routing separately when configured. |
| DATA-01 | Canonical shared chunk metadata for ingestion/replacement | RDP-18 live retrieval found Python doc_id versus Kotlin document_id metadata | Separately audit canonical metadata writes and replacement deletion in both runtimes; plan safe compatibility/backfill for existing Kotlin-origin chunks without editing user data during chat verification. |
| RDP-19 | Coordinate overlapping shared-data writes | Separate G1/G2 after HTTP parity; deployment allows concurrent writes to one chat/document | Define and verify shared transaction/locking/idempotency behavior for overlapping same-chat turns, summaries and same-document ingestion; no blanket concurrency claim based on sequential contract tests. |
| API-01 | Enrich chat citations with shared source assets | Separate G1/G2; current frontend accepts missing assets as empty | Chat/SSE citations expose authorized source asset references consistently with Python, and both services can read the same private asset storage mount. |
| LOG-01 | DOCX library log hygiene | Separate G1 intake after RDP-17; observed during runtime acceptance | Review docx4j source-path INFO output and properties/parser initialization warnings; keep useful ingestion lifecycle logs while avoiding library source-path disclosure. |
| EXP-01 | Evaluate isolated Embabel feature | RDP-15 | A separately exposed experiment demonstrates benefit without changing parity behavior. |

See [PLAN.md](PLAN.md) for scope, sequencing, and phase-specific checks. Move a row here into [work_current_phase.md](work_current_phase.md) before implementation starts.
