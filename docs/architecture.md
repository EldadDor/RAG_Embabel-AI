# Shared-runtime architecture

**Status:** proposed for approval

**Last reviewed:** 2026-09-19
**Source revision:** `RAG-dev-plane` `92a594e8e3bec694b1a893563f63d8a220605dcd`

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

`rag.document_chunks` is not a generic Spring AI collection. Its primary key is Python UUIDv5 derived from the stable chunk ID; its JSONB metadata controls workspace/profile scope and carries provenance, assets, and code metadata. Queries use pgvector cosine distance and PostgreSQL full-text search, with application-side RRF. The Kotlin JDBC repository is therefore the interoperability boundary.

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
