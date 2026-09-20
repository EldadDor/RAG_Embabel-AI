# Next phase backlog

**Last reviewed:** 2026-09-19

| ID | Task | Entry condition | Done when |
| --- | --- | --- | --- |
| RDP-04 | Shared schema validator and domain model | RDP-02 | Kotlin validates the Python-managed schema without DDL. |
| RDP-05 | JDBC persistence interoperability | RDP-04 | Controlled Python/Kotlin cross-language repository fixtures pass. |
| RDP-06 | Document parsing foundation | RDP-02 | Text, Markdown, HTML, PDF, registry, directory scan, and fixture behavior match Python. |
| RDP-07 | Word parsing and embedded-image extraction | RDP-06 | DOCX structure, safety, image bytes, and image-to-chunk linkage match Python. |
| RDP-08 | Code parsing and chunking | RDP-06 | Text/profile/Python/Java/Kotlin chunking and metadata fixtures match Python. |
| RDP-09 | Asset lifecycle and ingestion | RDP-05 through RDP-08 | Ingestion, atomic replacement, dry run, cleanup, and assets match Python. |
| RDP-10 | Identity, sessions, API errors, and assets | RDP-05 | Authorization/session/asset behavior matches Python. |
| RDP-11 | Providers, profiles, and embedding cache | RDP-05 | Provider/profile/cache behavior matches Python with no schema creation. |
| RDP-12 | Retrieval, grounded chat, and memory | RDP-10 and RDP-11 | Retrieval/chat/evaluation fixtures match Python. |
| RDP-13 | Streaming and telemetry instrumentation | RDP-03 and RDP-12 | Streaming behavior and full local metrics/tracing coverage pass. |
| RDP-14 | Evaluation, resilience, and alternative storage | RDP-13 | Evaluation/failure tests pass; Qdrant remains a supported alternative. |
| RDP-15 | Cross-runtime verification | RDP-09 through RDP-14 and user-run environment | Controlled shared-database round trips succeed in a dedicated workspace/profile. |
| EXP-01 | Evaluate isolated Embabel feature | RDP-15 | A separately exposed experiment demonstrates benefit without changing parity behavior. |

See [PLAN.md](PLAN.md) for scope, sequencing, and phase-specific checks. Move a row here into [work_current_phase.md](work_current_phase.md) before implementation starts.
