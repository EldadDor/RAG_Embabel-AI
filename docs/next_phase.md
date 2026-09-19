# Next phase backlog

**Last reviewed:** 2026-09-19

| ID | Task | Entry condition | Done when |
| --- | --- | --- | --- |
| SVC-01 | Freeze Python backend evidence | Approval of [PLAN.md](PLAN.md) | Python source revision, API/tests/prompts/evaluation fixtures, and active configuration are mapped to Kotlin components. |
| SVC-02 | Align build and configuration | SVC-01 | Kotlin `.env` mirrors active Python configuration; typed binding, dependency cleanup, and no persistence auto-initialization are verified. |
| SVC-03 | Implement shared database compatibility | SVC-02 | Kotlin validates, reads, and writes the Python-managed schema without DDL; cross-language fixtures pass. |
| SVC-04 | Port authorization, sessions, assets, and errors | SVC-03 | Python authorization/session/asset behavior passes in Kotlin. |
| SVC-05 | Port retrieval, chat, profiles, and memory | SVC-03 | Retrieval/evaluation/profile/cache fixtures match Python. |
| SVC-06 | Port ingestion and loaders | SVC-03 | Chunking, asset, dry-run, replacement, and cleanup fixtures match Python. |
| SVC-07 | Port streaming and observability | SVC-04 through SVC-06 | Streaming, readiness, observability, and failure-path tests pass without routine live calls. |
| SVC-08 | Verify shared runtime | SVC-07 and user-run environment | Controlled Python/Kotlin round trips succeed in a dedicated workspace/profile. |
| EXP-01 | Evaluate isolated Embabel feature | SVC-08 | A separately exposed experiment demonstrates benefit without changing parity behavior. |

See [PLAN.md](PLAN.md) for scope, sequencing, and phase-specific checks. Move a row here into [work_current_phase.md](work_current_phase.md) before implementation starts.
