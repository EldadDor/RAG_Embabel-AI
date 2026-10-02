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

A dry run parses and chunks without provider calls, database operations, or asset writes. A real run requires a ready model profile and persists vectors to its storage target. Unchanged documents are skipped without embedding or replacement writes. Supported types include text, Markdown, HTML, PDF, DOCX, PPTX, and source/config files.

Application ingestion INFO logs report stages, counts, and elapsed times, omitting paths, IDs, and document text. DOCX library source-path logging is tracked separately as LOG-01.

## HTTP APIs

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/ingest` | Authorized file/directory ingestion or dry-run planning |
| POST | `/chat` | Grounded answer with citations and optional debug data |
| POST | `/chat/stream` | SSE answer deltas, metadata, completion, and error events |
| GET | `/workspaces` | Accessible workspaces |
| GET | `/workspaces/{workspace_id}/documents` | Authorized catalog with exact profile counts and signed pagination |
| GET | `/chat/sessions?workspace_id=...` | List workspace sessions |
| GET | `/chat/sessions/{sessionId}` | Session details and turns |
| PATCH | `/chat/sessions/{sessionId}` | Rename a session |
| DELETE | `/chat/sessions/{sessionId}` | Archive a session |
| GET | `/workspaces/{workspaceId}/assets/{assetId}` | Authorized asset read with ETag support |

For chat, send `{"question":"What does the document describe?"}`. Optional fields are `top_k`, `include_debug`, `session_id`, `workspace_id`, `chunking_profile`, and `model_profile`. CamelCase chat request aliases remain accepted for existing callers. Workspace, session, and completed-chat responses use the Python/frontend snake-case contract, including `display_name`, `session_id`, `last_preview`, `updated_at`, `created_at`, and citation `doc_id`. This corrects the earlier incompatible camelCase responses. Sessions and assets enforce access checks. See [API and evaluation contract](docs/api_evaluation_contract.md).

## Serving the frontend with both backends

The document catalog requires Python migration `006_document_index_metadata` in every deployment. Kotlin validates it and its four catalog relations without running DDL. Python/operator reconciliation owns `document_catalog_state.ready`; Kotlin never certifies historical data automatically. Listing is disabled by default. After certification and acceptance, set `DOCUMENT_LIST_ENABLED=true` and supply the same `DOCUMENT_LIST_CURSOR_SECRET` (at least 32 UTF-8 bytes) to both services. Python's process-local fallback secret cannot support continuation through a load balancer. If the document panel reports unavailable, check that the Kotlin process has both settings; restarting without them keeps listing disabled. Catalog 503 responses now emit a server WARN identifying disabled listing, invalid cursor-secret configuration, catalog readiness or database failure. Unexpected API failures emit an ERROR with exception class and stack locations, omitting potentially sensitive exception messages. Public error responses remain generic.

`GET /workspaces/{workspace_id}/documents?limit=25` accepts limits 1–100 and optional `cursor`, `model_profile`, `chunking_profile`. Omitted profiles match chat defaults. Responses contain `workspace_id`, resolved `scope`, `items` and `page`: safe title/filename/type, nullable successful-ingestion time, exact scoped chunk count including zero, and revision-checked keyset continuation. A safe 409 requires restarting page one; safe 503 means listing is disabled/uncertified/unavailable. Success and errors use private no-store headers. Type/size colors remain frontend configuration. Python-origin `powerpoint` strings are readable; Kotlin PPTX writes retain `unknown` under the approved NP-20 scope.

Ingestion publishes vectors, metadata, revisions and model-owned asset references together under Python-compatible advisory locks. Unchanged ingestion preserves recency; failed replacement rolls back; successful zero-chunk documents are listed. Directory cleanup is model/root/scan-coverage scoped and ignores publications newer than the scan. New chunks use canonical `doc_id`; reads/deletion/counts retain historical `document_id` compatibility. Historical alias reconciliation remains operator-owned.

New asset IDs include source version and image content, matching Python's immutable identity. New storage keys use Python's content hash and `<hash-prefix>/<hash>` layout. Existing Kotlin extension-bearing keys remain readable; historical files are not moved or pruned. A shared storage mount remains required. `ProfiledEmbeddingService.warm(target, source, workspace, dryRun)` provides corpus warming without rechunking, preserves source timestamps and asset ownership, and pins adapters to one connection while source/target session locks are held. The single-argument warm method remains a provider probe; no new warmer HTTP route was added in this task.

Python and Kotlin must listen on separate ports when running on the same host.
For example, keep Python on `8000` and launch Kotlin with `API_PORT=8001`.
The current Python-project Vite configuration proxies `/workspaces` and `/chat`
to `localhost:8000`; point those routes at the selected backend or a common
reverse proxy/load balancer when testing parallel operation. Route asset requests
under `/workspaces` through that same upstream.

Both backends must use the same PostgreSQL host/port/database/schema and the
same user subject (`LOCAL_SUBJECT` locally; trusted gateway identity in the
workplace). Align `DEFAULT_WORKSPACE_ID`, chunking/model profiles, and retention
settings. Membership and chat ownership are stored in PostgreSQL, so sequential
requests can change backend without a process-local session. The gateway must
strip browser-supplied identity headers and inject the same authenticated subject
for both backends. SSE proxy buffering must be disabled; Kotlin supplies
`Cache-Control: no-cache` and `X-Accel-Buffering: no`.

Use a shared asset directory/storage mount accessible to both services; identical
relative asset roots in different checkouts do not reference the same files.
Catalog publication uses the shared document/profile lock protocol; real PostgreSQL concurrency acceptance remains pending. Load balancing does not serialize overlapping requests to the same chat; that coordination remains RDP-19. Offline contract tests
cover frontend requests and durable-session boundaries; real cross-backend
HTTP/load-balancer acceptance remains a separate runtime check.

## Development

With the workspace Maven environment configured:

```powershell
rtk proxy mvn spotless:apply
rtk proxy mvn test
```

The normal suite uses offline fixtures and mocks. Live provider/database checks require explicit opt-in and controlled fixture workspaces. Source lives under `src/main/kotlin/com/dex/ragpoc/`, tests under `src/test/kotlin/com/dex/ragpoc/`.

See [architecture](docs/architecture.md), [delivery plan](docs/PLAN.md), [current phase](docs/work_current_phase.md), and [backlog](docs/next_phase.md) for evidence and remaining work.

RDP-20 verification and the separately approved database-only opt-in procedure are in [catalog verification](docs/rdp20_verification.md). Rolling back to an old Kotlin writer requires removing it from ingestion/warming traffic; disabling the catalog route alone cannot preserve catalog integrity.

PPTX extraction follows slide and stored shape order, including tables, nested groups, existing speaker notes, and hidden slides. Chunks stay within one slide; citation page numbers are slide numbers. Embedded PNG/JPEG/GIF pictures use private assets with links to chunks on the same slide. Linked and unsupported pictures retain unavailable anchors. Blank slides retain markers. Package validation bounds ZIP/XML expansion and rejects corrupt, encrypted, or macro-enabled input. Visual reading-order inference, rendering, OCR, charts, and binary PowerPoint files are outside this extractor.
