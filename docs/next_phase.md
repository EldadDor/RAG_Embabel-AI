# Next phase backlog

**Last reviewed:** 2026-09-30

| ID | Task | Entry condition | Done when |
| --- | --- | --- | --- |
| RDP-16 | PPTX parsing and ingestion | RDP-17 G4 closure; later extension, not required for RDP-10 | Apache POI XSLF loads safe presentations with slide-order text and tables, slide provenance, embedded images, asset-to-chunk links, and ingestion lifecycle coverage. |
| LOG-01 | DOCX library log hygiene | Separate G1 intake after RDP-17; observed during runtime acceptance | Review docx4j source-path INFO output and properties/parser initialization warnings; keep useful ingestion lifecycle logs while avoiding library source-path disclosure. |
| EXP-01 | Evaluate isolated Embabel feature | RDP-15 | A separately exposed experiment demonstrates benefit without changing parity behavior. |

See [PLAN.md](PLAN.md) for scope, sequencing, and phase-specific checks. Move a row here into [work_current_phase.md](work_current_phase.md) before implementation starts.
