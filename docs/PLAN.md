# RAG-dev-plane Kotlin parity plan

**Status:** revised for approval

**Last reviewed:** 2026-09-22

**Authoritative source:** `EldadDor/RAG-dev-plane` at `92a594e8e3bec694b1a893563f63d8a220605dcd`.

## Task naming

`SVC` meant “service.” It is replaced by `RDP`, meaning **RAG-dev-plane parity**. Every `RDP` task delivers one verifiable part of the Python backend in Kotlin.

## Scope and non-negotiable boundaries

Kotlin ports all backend behavior in RAG-dev-plane: document loading and chunking, embedded-document assets, ingestion, storage, model profiles/cache, retrieval, chat and memory, security, API behavior, streaming, evaluation, and observability.

RAG-dev-plane owns the PostgreSQL/pgvector schema. Kotlin validates and uses the existing schema; it never creates, alters, baselines, migrates, resizes, or seeds it. Spring AI is used for chat and embedding provider calls only. A custom JDBC persistence layer is required for the shared tables.

Embabel is deferred until all RDP tasks pass. It must not alter the deterministic parity path.

## Confirmed source behavior

| Area | Python behavior that Kotlin must reproduce |
| --- | --- |
| Database | `rag.schema_migrations` holds versions `001_baseline` to `005_model_profiles`; startup validates them and all required tables. `document_chunks` uses Python UUIDv5 IDs, JSONB metadata, `vector(n)`, source path, page, and chunk index. |
| Configuration | Pydantic loads `.env` in Python. Kotlin expresses the equivalent active configuration through standard Spring Boot `application.yml` and profile YAML. |
| File registry | Supports `.md`, `.mdx`, `.html`, `.htm`, `.pdf`, `.docx`, `.txt`, `.py`, `.js`, `.jsx`, `.ts`, `.tsx`, `.java`, `.kt`, `.kts`, `.cs`, `.go`, `.rs`, `.sql`, `.json`, `.yaml`, `.yml`, and `.toml`; recursive scans skip `.git`, `.idea`, `.gradle`, `.venv`, `build`, `node_modules`, `out`, and `target`. |
| PDF | Reads text page by page, preserves page break markers and page metadata, and produces stable document identity/provenance. |
| Markdown | Preserves UTF-8 text and uses the first H1 as title when present. |
| Word | Rejects unsafe/corrupt/encrypted packages; preserves ordered paragraphs, headings, lists, tables, page-break hints, block offsets, sections, and title. Extracts embedded image bytes and links each image through relationship ID, anchor block/ordinal, section, underlying text/caption, alt text, content hash, media type, and original name. No image recognition is included. |
| Code | Uses structure-aware Python, Java, and Kotlin chunking; preserves language, symbols, enclosing types, line ranges, and repository metadata. Malformed Java/Kotlin falls back to generic text chunking. |
| Retrieval | Query embedding with profile prefix; semantic score floor 0.35; optional PostgreSQL full-text retrieval; RRF 60; 20 candidates; default top 5; optional reranker. |
| Profiles/cache | Ready profile selects an existing storage target. Query/document prefixes form part of effective input and cache key. Cache payload is little-endian float32. |
| Observability | Python has an optional Langfuse boundary. Kotlin adds local OpenTelemetry, Prometheus, Grafana, and collector support while keeping Langfuse disabled locally. |

## Spring Boot configuration baseline

RDP-02 translates the current Python `.env` into standard Spring Boot configuration: common non-secret settings in `application.yml`, local/provider-specific settings in profile YAML, and secret values supplied through ordinary environment-variable placeholders. It does not use a custom `.env` import as an application configuration mechanism.

| Python group | Spring Boot target |
| --- | --- |
| Runtime and identity | `server.*`, `logging.*`, and typed `app.auth.*` properties |
| PostgreSQL | `spring.datasource.*` plus typed `app.database.*` schema, table, vector-dimension, and Entra settings |
| Chat and embeddings | `spring.ai.*` provider settings plus typed `app.rag.*` profile and timeout settings |
| RAG, assets, memory | Typed `app.rag.*`, `app.assets.*`, and `app.memory.*` properties |
| Telemetry | `management.*`, `spring.otel.*`, and typed `app.observability.*` properties; Langfuse remains off locally |

The checked-in Python defaults are chunk size 800, overlap 120, top 5, score floor 0.35, hybrid enabled, 20 candidates, RRF 60, 10 retained turns, and summary refresh after 8 turns. Preserve those values in Spring configuration unless the active Python configuration supplies a different value. Secrets stay outside committed YAML.

## Ordered task breakdown

### RDP-01 — Freeze API and evaluation evidence

- Freeze the Python API request/response and SSE contracts that Kotlin exposes, including the essential authorization, error, session, asset, ingestion, and chat cases.
- Use the copied [English golden cases](../evaluation/golden-cases.jsonl) and [Hebrew golden cases](../evaluation/golden-cases-heb.jsonl) as the primary answer-quality parity data.
- Record the source revision and select only the Python tests that protect these contracts and high-risk backend behavior.

**Done when:** API contract tests and golden-case evaluation coverage are defined, and the selected Python tests/fixtures are recorded with their Kotlin equivalents. A one-to-one port of every Python test is not required.

### RDP-02 — Build and configuration alignment

- Simplify Maven dependencies; retain Boot 4.1.1, Spring AI 2.0.1, Kotlin 2.3.21, and only provider modules required by the active configuration.
- Remove duplicate PDF dependencies and disable pgvector/chat-memory schema auto-configuration.
- Translate active Python `.env` semantics into `application.yml` and profile YAML using standard Spring Boot property names and typed `@ConfigurationProperties`.
- Use Spring environment placeholders for credentials and tokens; replace obsolete property names and hard-coded topology comments in YAML/README.

**Done when:** configuration loads without secret logging and resolves the same selected provider, model, database/schema/table, workspace/profile, and RAG settings as Python using regular Spring Boot conventions.

### RDP-03 — Local telemetry platform and application foundation

- Add Spring Boot Actuator, Micrometer Prometheus registry, OpenTelemetry SDK/exporter, trace/log correlation, and typed telemetry configuration.
- Add a local Docker Compose stack for Prometheus, Grafana, and the OpenTelemetry Collector. The service exports OTLP to the collector and Prometheus exposes a scrape endpoint.
- Keep Langfuse disabled in the local Spring profile; provide a disabled-by-default Langfuse adapter/configuration for the workplace environment.
- Add health/readiness with no model call for health and explicit database/model readiness details.

**Done when:** local Grafana shows service metrics and traces arrive in the collector with Langfuse absent.

### RDP-04 — Shared schema validator and domain model

- Implement read-only validation of the five Python-applied schema versions, tables, columns, vector dimensions, selected ready profile, and profile storage target.
- Define Kotlin domain types matching Python documents, chunks, source references, assets, sessions, profiles, and API DTOs.

**Done when:** Kotlin starts against a valid Python database without writes and fails with a precise diagnostic for an invalid schema/profile.

### RDP-05 — JDBC persistence interoperability

- Implement repositories for chunks/source documents, workspaces/memberships, sessions/turns/summaries, assets/chunk links, model profiles, and embedding cache.
- Match Python UUIDv5 derivation, JSONB keys, vector literals/types, full-text query semantics, cache byte layout, SQL ordering, and transaction boundaries.

**Done when:** Kotlin reads Python-created fixtures and Python reads controlled Kotlin-created fixtures in a dedicated workspace/profile.

### RDP-06 — Document parsing foundation

- Implement loader registry, supported-extension checks, 50 MiB file limit, recursive directory scan behavior, deterministic traversal, and skipped-file reporting.
- Port plain text, Markdown/MDX, HTML, and PDF parsing. Preserve titles, source type/path, stable doc IDs, HTML visible-text extraction, and PDF page provenance/page-break markers.

**Done when:** Python loader fixtures for text, Markdown, HTML, PDF, unsupported paths, oversized files, and recursive scanning pass in Kotlin.

### RDP-07 — DOCX parsing and embedded-image extraction with docx4j

- Use docx4j as the `.docx` package and document-model implementation, including a compatible JAXB runtime for JDK 25.
- Port safe `.docx` package validation and ordered content traversal for headings, paragraphs, lists, tables, and page-break hints.
- Preserve block IDs, ordinals, offsets, sections, document title, and image anchors.
- Extract embedded image bytes into the private asset store and preserve relationship ID, anchor block, ordinal, underlying block text or following caption, alt text, media type, original name, byte size, and content hash.
- Do not add OCR, visual embedding, or image recognition.

**Done when:** DOCX fixtures prove structural text order, unsafe/corrupt/encrypted-package rejection, image byte extraction, and exact image-to-chunk linkage metadata. The parser and its fixtures must pass before RDP-09 ingestion work begins.

### RDP-08 — Code parsing and chunking

- Port recursive/default text chunking and named chunking profiles with 800/120 default behavior.
- Port Python AST chunking plus Tree-sitter Java and Kotlin declaration-aware chunking. Preserve module/class/function/type symbols, enclosing type, language, line range, and generic fallback for malformed source.
- Preserve chunk identifiers: `{docId}:{index}` for `default`, `{docId}:{profile}:{index}` otherwise.

**Done when:** Python chunker and code-ingestion fixtures produce equivalent chunk boundaries, IDs, and metadata.

### RDP-09 — Asset lifecycle and ingestion

- Implement content-addressed private asset storage and asset limits; persist `document_assets` and `chunk_assets` links through the shared schema.
- Port content hashing, unchanged no-op, dry run with no provider/database calls, pre-embedding before transaction, atomic replacement, and root-scoped stale-file deletion.
- Include all loader types from RDP-06 through RDP-08. RDP-07 DOCX parsing and image linkage are required inputs, not work deferred into ingestion.

**Done when:** Python ingestion tests pass for changed/unchanged files, rollback safety, dry run, recursive deletion, all document classes, and image association.

### RDP-10 — Identity, workspaces, sessions, API errors, and assets

- Port local and trusted-gateway principal resolution, workspace authorization, session ownership masking, durable memory retention, asset authorization, MIME allow-list, ETag, cache control, and safe errors.

**Done when:** Python authorization, session, and asset tests pass; foreign/missing/archived sessions produce the same masked outcome.

### RDP-11 — Providers, profiles, and embedding cache

- Implement provider-neutral gateways backed by Spring AI: OpenAI-compatible/Azure chat and Ollama/Azure embeddings.
- Implement ready-profile lookup, prefix-aware effective input, vector-length checks, profile storage target selection, float32 cache compatibility, and profile warming without re-chunking.
- Support Azure/Entra token providers only when selected by active configuration.

**Done when:** Python model-profile and cache tests pass, and Kotlin rejects missing/incompatible profile targets without DDL.

### RDP-12 — Retrieval, grounded chat, and memory

- Port follow-up rewrite, bounded turns/summaries, grounded prompt, abstention, semantic search, lexical search, thresholding, RRF, reranking option, citations, debug data, and completed chat persistence.

**Done when:** Python retrieval, chat, and evaluation fixtures match on rankings, sources, grounding, and memory behavior.

### RDP-13 — Streaming and telemetry instrumentation

- Port SSE answer/meta/error/done behavior, cancellation, and provider failure handling.
- Instrument all operations with low-cardinality metrics and spans: request count/latency/status; ingestion documents/chunks/assets/skips/failures; loader/chunker duration; embedding calls/cache hits/dimension failures; vector/lexical/RRF/rerank duration and candidate counts; chat time-to-first-token/tokens/completion; session operations; asset reads; database query/connection-pool health; and schema validation.
- Exclude prompts, document text, user questions, paths, session IDs, workspace IDs, and provider responses from metric labels and default span attributes.

**Done when:** Prometheus captures the metric families, Grafana dashboards display throughput/latency/error/cache/retrieval/ingestion views, collector traces cover request-to-provider/database flow, and streaming tests pass.

### RDP-14 — Evaluation, resilience, and alternative storage

- Port Python golden evaluation runner/datasets and failure-path tests.
- Keep Qdrant as the Python-supported alternative behind the same storage interface; PostgreSQL remains the shared-runtime target and must reach parity first.
- Validate provider/database unavailability, timeout, cancellation, telemetry failure isolation, and retention behavior.

**Done when:** ordinary tests make no live calls, evaluation fixtures pass, and optional integrations are explicit opt-in.

The existing Python-ingested PostgreSQL corpus may be used read-only for Kotlin retrieval and golden-case compatibility checks. New user-provided documents are requested only when RDP-09 reaches its opt-in ingestion integration test.

### RDP-15 — Cross-runtime verification

- Run read-only Kotlin validation against the existing RAG-dev-plane database.
- In a dedicated workspace/profile, perform controlled Python-to-Kotlin and Kotlin-to-Python round trips for parsed documents, assets, chunks, retrieval, sessions, model cache, and profile selection.
- Keep the default workspace untouched.

**Done when:** you confirm both runtimes work safely against the same database.

### EXP-01 — Optional Embabel experiment

After RDP-15, evaluate an isolated Embabel workflow with explicit tool allow-list, time/cost limits, citations, traces, cancellation, and approval for mutations.

**Done when:** it demonstrates a documented benefit without changing any RDP parity behavior.

## Local observability deliverables

| Component | Purpose |
| --- | --- |
| Spring Actuator + Micrometer | Health/readiness and Prometheus metrics endpoint. |
| OpenTelemetry SDK | Creates traces and correlates them with structured logs. |
| OpenTelemetry Collector | Receives OTLP, samples/routes telemetry, and exposes local diagnostics. |
| Prometheus | Scrapes application/collector metrics and retains local time series. |
| Grafana | Dashboards for HTTP, ingestion, parsing, chunks/assets, retrieval, cache, model calls, database, streams, and errors. |
| Langfuse adapter | Disabled locally; activated only by workplace configuration and policy. |

## Approval point

Approve this breakdown to start RDP-01 through RDP-03. The first implementation output will be API/golden evaluation evidence, Spring YAML alignment, and the local observability foundation. It will not modify the shared database.
