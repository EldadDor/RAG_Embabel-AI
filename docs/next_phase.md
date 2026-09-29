# Next phase backlog

**Last reviewed:** 2026-09-29

| ID | Task | Entry condition | Done when |
| --- | --- | --- | --- |
| RDP-17 | Parity ingestion HTTP API | DOC-09 review and G1/G2 approval; precedes manual chat validation | An authorized `POST /ingest` matches the Python request/response contract, delegates to JDBC ingestion, preserves dry-run isolation, records existing telemetry, and passes user-run ingestion validation before chat testing. |
| LEG-01 | Retire legacy vector-store POC | RDP-17 user acceptance and separate G2 approval | The obsolete `/api/ingest`/`/api/rag` POC, its profile gates, dependencies, tests, and historical instructions are removed only after the replacement API is accepted. |
| RDP-16 | PPTX parsing and ingestion | RDP-17; later extension, not required for RDP-10 | Apache POI XSLF loads safe presentations with slide-order text and tables, slide provenance, embedded images, asset-to-chunk links, and ingestion lifecycle coverage. |
| EXP-01 | Evaluate isolated Embabel feature | RDP-15 | A separately exposed experiment demonstrates benefit without changing parity behavior. |

See [PLAN.md](PLAN.md) for scope, sequencing, and phase-specific checks. Move a row here into [work_current_phase.md](work_current_phase.md) before implementation starts.
