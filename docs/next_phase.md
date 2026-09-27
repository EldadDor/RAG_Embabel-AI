# Next phase backlog

**Last reviewed:** 2026-09-27

| ID | Task | Entry condition | Done when |
| --- | --- | --- | --- |
| RDP-11 | Providers, profiles, and embedding cache | RDP-05 | Provider/profile/cache behavior matches Python with no schema creation. |
| RDP-12 | Retrieval, grounded chat, and memory | RDP-10 and RDP-11 | Retrieval/chat/evaluation fixtures match Python. |
| RDP-13 | Streaming and telemetry instrumentation | RDP-03 and RDP-12 | Streaming behavior and full local metrics/tracing coverage pass. |
| RDP-14 | Evaluation, resilience, and alternative storage | RDP-13 | Evaluation/failure tests pass; Qdrant remains a supported alternative. |
| RDP-15 | Cross-runtime verification | RDP-09 through RDP-14 and user-run environment | Controlled shared-database round trips succeed in a dedicated workspace/profile. |
| RDP-16 | PPTX parsing and ingestion | RDP-15; later extension, not required for RDP-10 | Apache POI XSLF loads safe presentations with slide-order text and tables, slide provenance, embedded images, asset-to-chunk links, and ingestion lifecycle coverage. |
| EXP-01 | Evaluate isolated Embabel feature | RDP-15 | A separately exposed experiment demonstrates benefit without changing parity behavior. |

See [PLAN.md](PLAN.md) for scope, sequencing, and phase-specific checks. Move a row here into [work_current_phase.md](work_current_phase.md) before implementation starts.
