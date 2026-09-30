# RAG_Embabel-AI

Kotlin/Spring Boot backend with RAG-dev-plane parity: document loading and chunking, private assets, PostgreSQL/pgvector retrieval, grounded chat, durable sessions, and ingestion HTTP APIs. Spring AI handles model calls; application-owned JDBC repositories read and write the shared Python-managed schema.

## Stack

| Layer | Technology |
| --- | --- |
| Language/runtime | Kotlin 2.3.21, JDK 25 |
| Framework | Spring Boot 4.1.1 |
| Model providers | Spring AI 2.0.1; OpenAI-compatible chat, Ollama embeddings, work-profile Microsoft Foundry |
| Persistence/retrieval | JDBC, PostgreSQL/pgvector, full-text search, reciprocal rank fusion |
| Parsing | PDFBox, docx4j 17.1.0, Tree-sitter |
| Telemetry | Actuator, Micrometer, OpenTelemetry, Prometheus, Grafana, Tempo |
| Tests | JUnit 5, MockK, offline fixtures and opt-in interoperability checks |

Embabel 1.5.2 dependencies are isolated in the optional `embabel-experiments` Maven profile. EXP-01 tracks the planned experiment.

## Run locally

Use the `local` Spring profile, also the default. Supply environment variables for existing PostgreSQL and model services using [application-example.yml](application-example.yml). Python owns shared-schema migrations. Follow [repository instructions](.codex/AGENTS.md) for workspace-local Maven and JGit settings, then run:

```powershell
rtk proxy mvn spring-boot:run
```

The HTTP port defaults to `8000` (`API_PORT`). Health is at `http://localhost:8000/actuator/health`; Prometheus metrics at `/actuator/prometheus`. See [local observability](docker/observability/README.md) for the laptop telemetry stack. The `work` profile uses workplace provider configuration.

## Ingestion

Set `INGESTION_ALLOWED_ROOTS` to comma-separated absolute directories containing documents. An empty value rejects all sources. `POST /ingest` checks workspace membership before resolving a path, rejects sources outside approved roots with a safe 422 response, and scans supported files in stable order. `recursive` controls nested directories.

```json
{"source_path":"C:/documents/guide.pdf","dry_run":true}
```

`source_path` is required. Optional fields are `recursive`, `workspace_id`, `chunking_profile`, `model_profile`, and `dry_run`. The response reports `indexed`, selected profiles, `dry_run`, and a `documents` array with `doc_id`, `source_path`, `chunks_indexed`, `skipped`, `skip_reason`, and `assets_found`.

A dry run parses and chunks without provider calls, database operations, or asset writes. A real run requires a ready model profile and persists vectors to its storage target. Unchanged documents are skipped without embedding or replacement writes. Supported types include text, Markdown, HTML, PDF, DOCX, and source/config files; PPTX is planned as RDP-16.

Application ingestion INFO logs report stages, counts, and elapsed times, omitting paths, IDs, and document text. DOCX library source-path logging is tracked separately as LOG-01.

## HTTP APIs

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/ingest` | Authorized file/directory ingestion or dry-run planning |
| POST | `/chat` | Grounded answer with citations and optional debug data |
| POST | `/chat/stream` | SSE answer deltas, metadata, completion, and error events |
| GET | `/workspaces` | Accessible workspaces |
| GET | `/chat/sessions?workspaceId=...` | List workspace sessions |
| GET | `/chat/sessions/{sessionId}` | Session details and turns |
| PATCH | `/chat/sessions/{sessionId}` | Rename a session |
| DELETE | `/chat/sessions/{sessionId}` | Archive a session |
| GET | `/workspaces/{workspaceId}/assets/{assetId}` | Authorized asset read with ETag support |

For chat, send `{"question":"What does the document describe?"}`. Optional fields are `topK`, `includeDebug`, `sessionId`, `workspaceId`, `chunkingProfile`, and `modelProfile`. Sessions and assets enforce access checks. See [API and evaluation contract](docs/api_evaluation_contract.md).

## Development

With the workspace Maven environment configured:

```powershell
rtk proxy mvn spotless:apply
rtk proxy mvn test
```

The normal suite uses offline fixtures and mocks. Live provider/database checks require explicit opt-in and controlled fixture workspaces. Source lives under `src/main/kotlin/com/dex/ragpoc/`, tests under `src/test/kotlin/com/dex/ragpoc/`.

See [architecture](docs/architecture.md), [delivery plan](docs/PLAN.md), [current phase](docs/work_current_phase.md), and [backlog](docs/next_phase.md) for evidence and remaining work.
