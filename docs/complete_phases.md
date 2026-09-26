# Completed phases

**Last reviewed:** 2026-09-19

| Phase | Status | Evidence |
| --- | --- | --- |
| Planning and contract intake (DOC-01) | Completed 2026-09-18 | [Current phase record](work_current_phase.md); [delivery plan](PLAN.md); [architecture](architecture.md). Source artifacts remain an SVC-01 entry gate. |
| Python source repository access check (DOC-02) | Completed 2026-09-19 | Read-only `git ls-remote` reached `EldadDor/RAG-dev-plane` and resolved its HEAD to `92a594e8e3bec694b1a893563f63d8a220605dcd`. |
| Python/Kotlin plan reconciliation (DOC-03) | Completed 2026-09-19 | [Plan](PLAN.md), [architecture](architecture.md), and [backlog](next_phase.md) now use the Python implementation as authority; the shared schema is validated but never migrated by Kotlin. |
| Detailed parity and local observability planning (DOC-04) | Completed 2026-09-19 | [Plan](PLAN.md) now contains RDP-01 through RDP-15, including early loader/DOCX asset work and the local OTel/Prometheus/Grafana/Collector foundation. |
| API/evaluation and Spring configuration planning (DOC-05) | Completed 2026-09-20 | [Plan](PLAN.md) prioritizes API contracts and copied English/Hebrew golden cases, while configuration uses standard Spring Boot YAML/profiles. |
| API and evaluation evidence (RDP-01) | Completed 2026-09-20 | [API and evaluation evidence](api_evaluation_contract.md) defines selected contract tests and the 19 English plus 13 Hebrew golden cases. |
| JDBC persistence interoperability (RDP-05) | Completed 2026-09-26 | Kotlin and Python read controlled fixtures in both directions for workspace membership, documents/chunks, asset links, sessions/summaries, ready model profiles, and embedding cache values. Dedicated fixture workspaces remain in the shared database. Carry-over: RDP-06 begins loader registry, path-safety, text/Markdown/HTML/PDF parsing, and matching fixtures. |
| Document parsing foundation (RDP-06) | Completed 2026-09-26 | Added a Python-compatible loader registry, size/path checks, deterministic directory scans, and text/Markdown/HTML/PDF parsers with local fixtures. `mvn test` passed 21 tests with 2 existing skips. Carry-over: RDP-07 DOCX parsing and image extraction, RDP-08 code parsing and chunking, then RDP-09 ingestion. |

Close an implementation phase here only after its acceptance evidence is recorded in [work_current_phase.md](work_current_phase.md).
