# Shared-runtime architecture

**Status:** proposed for approval

**Last reviewed:** 2026-10-03
**Source revision:** original baseline `92a594e8e3bec694b1a893563f63d8a220605dcd`; NP-20 catalog reviewed at local Python `8599b26` and later `a7e6ce0`. RDP-20 catalog architecture is implemented with offline evidence; live acceptance remains pending.

The Kotlin service ports the Python backend's behavior and uses its existing PostgreSQL/pgvector database as an externally managed shared store. Spring AI handles model-provider calls. Application-owned JDBC repositories handle every read and write to the `rag` schema.

```text
HTTP API
  │
  ├─ PrincipalResolver ─ WorkspaceAuthorization
  ├─ ChatService ─ RetrievalService ─ RagChunkRepository ─ PostgreSQL/pgvector
  │                    │                    ├─ source documents and chunks
  │                    │                    ├─ sessions and summaries
  │                    │                    ├─ model profiles and cache
  │                    │                    └─ assets and chunk links
  │                    └─ EmbeddingGateway ─ Spring AI embedding model
  ├─ ChatGateway ─ Spring AI chat model
  ├─ IngestionService ─ loaders, chunkers, private asset store
  └─ Telemetry ─ Actuator/Micrometer ─ Prometheus
                └─ OpenTelemetry SDK ─ OTel Collector ─ Grafana
```

## Database contract

RAG-dev-plane owns `rag.schema_migrations` and the five applied migration versions: `001_baseline`, `002_workspace_authorization`, `003_chunking_profiles`, `004_document_assets`, and `005_model_profiles`. Kotlin performs no migration or DDL. On startup it validates the schema, configured vector dimension, and selected ready model profile.

Python additionally applied `006_document_index_metadata` locally on 2026-10-02. RDP-20 now validates and uses `document_index_metadata`, `document_index_assets`, `document_list_revisions`, and `document_catalog_state` without DDL. Upgraded writers require the new schema; listing enablement and operator certification remain separate gates. Local Python certification does not establish readiness in other environments or certify later writes from older Kotlin code. See [implementation verification](rdp20_verification.md); real shared-runtime acceptance remains pending.

The catalog service exposes authorized read-only snapshot/keyset queries and Python-compatible signed cursors. Shared publication transactions update vectors, safe display metadata, model-owned asset references and revisions together. Ingestion, corpus warming and cleanup use Python-compatible advisory locks, preserve other profiles' source/assets, canonical `doc_id` metadata and legacy read compatibility. Failed replacement preserves the old publication; unchanged ingestion and warming preserve source recency; dry runs publish nothing. See [RDP-20 plan](rdp20_catalog_parity_plan.md) for API fields, signing/locking protocol, migration prerequisites and rollback restrictions.

`rag.document_chunks` is not a generic Spring AI collection. Its primary key is Python UUIDv5 derived from the stable chunk ID; its JSONB metadata controls workspace/profile scope and carries provenance, assets, and code metadata. Queries use pgvector cosine distance and PostgreSQL full-text search, with application-side RRF. The Kotlin JDBC repository is therefore the interoperability boundary.

DOCX handling is a parser boundary completed before ingestion. It uses docx4j with a JDK 25-compatible JAXB runtime to produce ordered text blocks and extracted image metadata/bytes. Ingestion consumes that parsed result and persists assets and chunk links only after parsing fixtures pass.

RDP-16 adds PPTX through Apache POI XSLF with bounded ZIP/XML validation, stored shape order, tables, existing notes, and slide provenance. Each slide is chunked independently; page_number represents its slide number. Embedded PNG/JPEG/GIF images use private assets and same-slide chunk links. SourceType.UNKNOWN with document_format=pptx metadata preserves shared-schema compatibility without a migration. Linked images, rendering, OCR, and charts are excluded.

Document loading also preserves source-file language for code and configuration files. The chunking layer uses recursive text splitting with an 800-character size and 120-character overlap by default, with named profiles for alternate recursive settings. Bundled Tree-sitter grammars extract Python, Java, and Kotlin declarations and line ranges; malformed Java/Kotlin falls back to generic splitting. Chunk IDs include the document ID, profile name when nondefault, and chunk index. Persistence wiring remains part of RDP-09.

Profile storage targets come from `rag.model_profiles` and are valid only when an existing table has the profile's exact `vector(n)` column. Cache entries in `rag.embedding_cache` are little-endian float32 bytes. Query/document prefixes are included in the effective embedding input and cache key.

## Runtime behavior

The service resolves the principal from local configuration or a trusted gateway header, then checks `rag.workspace_members` for every workspace operation. Sessions are owner- and workspace-scoped through `rag.chat_sessions`, `rag.conversation_turns`, and `rag.conversation_summaries`.

For retrieval, Kotlin resolves a ready model and chunking profile, rewrites follow-ups from the bounded session state, embeds the query with the selected profile prefix, filters semantic results below the configured floor, combines semantic and lexical rankings through RRF, then generates only from numbered retrieved passages. Ingestion computes content hashes, skips unchanged documents, embeds replacements before a transaction, then swaps document rows, chunks, and assets atomically.

## Configuration

Standard Spring Boot YAML and profiles express the active RAG-dev-plane configuration: `spring.datasource.*` for PostgreSQL, `spring.ai.*` for providers, `management.*` and `spring.otel.*` for telemetry, and typed `app.*` properties for RAG behavior. Credentials and tokens are ordinary environment-variable placeholders, never committed configuration values.

The active provider configuration determines the initial Spring AI adapters. OpenAI-compatible/Azure chat and Ollama/Azure embeddings remain independent. Azure PostgreSQL Entra authentication is enabled only when the active configuration selects it.

## Observability

Local runtime observability uses Actuator, Micrometer, Prometheus, Grafana, the OpenTelemetry SDK, and an OpenTelemetry Collector. Instrument HTTP, parsing/chunking, ingestion, assets, PostgreSQL, embedding/cache, retrieval/reranking, chat/streaming, sessions, schema validation, and failures. Metric labels and default span attributes exclude user content, source text, document paths, workspace IDs, session IDs, and provider responses.

Langfuse remains a separate disabled-by-default adapter. It is enabled only by workplace configuration, after content-capture policy is set.

## Verification

Kotlin unit tests use Python fixtures and evaluation cases. Repository compatibility tests use rows created by Python and verify Python can read Kotlin-produced rows. The final shared-database exercise uses a dedicated workspace/profile, begins with read-only validation, and leaves the default workspace unchanged.
