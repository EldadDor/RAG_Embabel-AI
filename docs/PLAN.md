# Kotlin parity plan for RAG-dev-plane

**Status:** revised for approval

**Last reviewed:** 2026-09-19
**Authoritative source:** `EldadDor/RAG-dev-plane` at `92a594e8e3bec694b1a893563f63d8a220605dcd`.

## Scope

Port the Python service's backend behavior into Kotlin/Spring Boot while both services can operate on the same already-provisioned PostgreSQL/pgvector database. The Kotlin implementation must preserve the Python service's public API, security model, ingestion semantics, retrieval behavior, assets, sessions, model profiles, evaluation data, and configuration semantics.

The database schema remains owned by RAG-dev-plane. Kotlin must never create, alter, baseline, migrate, resize, or seed it. It validates the existing database at startup and fails clearly if a required table, index-relevant column, model profile, or vector dimension is absent.

Embabel is outside parity scope. Keep its 1.5.2 dependency isolated until the deterministic Python behavior is reproduced and verified.

## Findings from source review

| Area | Python implementation | Kotlin plan |
| --- | --- | --- |
| Configuration | Pydantic settings load `.env`; the committed example defines the local baseline | Kotlin `.env` uses the same variable names and active values. Spring properties map directly from those variables. |
| Data ownership | Python validates migrations `001_baseline` through `005_model_profiles`; it does not execute them at runtime | No Flyway, schema initializer, or startup DDL in Kotlin. Use read-only schema validation. |
| Vector storage | `rag.document_chunks` stores UUIDv5 IDs, content, JSONB metadata, vector, source, page, and chunk index; search is scoped by workspace/profile | A custom JDBC repository reproduces the Python SQL and types. Do not use Spring AI `PgVectorStore` for this shared table. |
| Retrieval | Query embedding, minimum 0.35 semantic score, optional PostgreSQL full-text search, RRF 60, 20 candidates, optional reranking | Preserve the exact algorithm, ordering, scope filters, defaults, and debug fields. |
| Profiles and cache | `rag.model_profiles` selects a ready profile/table; cache stores little-endian float32 bytes keyed from effective prefixed input | Resolve profiles and cache through the same tables. Validate model dimensions before every write/search. |
| Ingestion | Hashes content; embeds before transactional replacement; supports profile isolation, asset links, and root-scoped stale-file deletion | Preserve chunk IDs, metadata keys, atomic replacement, dry-run purity, asset IDs/storage keys, and cleanup scope. |
| Sessions and authorization | Local or gateway principal; workspace membership; durable sessions/turns/summaries; ownership masking | Use server-derived identity and the same table semantics and HTTP behavior. |
| Providers | OpenAI-compatible or Azure chat; Ollama or Azure embeddings; PostgreSQL is default | Implement the active `.env` provider path first, retaining provider-neutral gateways for the supported alternatives. |

## Configuration baseline

There is currently no Kotlin `.env` file. Before service work begins, create the ignored file by transferring the current values from RAG-dev-plane's local `.env`, using the Python names below. Do not use the obsolete `PGVECTOR_*`, `OLLAMA_CHAT_*`, or `RAG_*` variables as the Kotlin source of truth.

| Python environment group | Kotlin binding target |
| --- | --- |
| `APP_ENV`, `LOG_LEVEL`, `API_HOST`, `API_PORT` | application environment, logging, server |
| `AUTH_MODE`, `LOCAL_*`, `CHAT_IDENTITY_*` | principal resolver |
| `PG_HOST`, `PG_PORT`, `PG_DATABASE`, `PG_USER`, `PG_PASSWORD`, `PG_SSLMODE`, `PG_USE_ENTRA`, `PG_SCHEMA`, `PG_TABLE`, `PG_VECTOR_DIM` | datasource and schema validator |
| `CHAT_PROVIDER`, `CHAT_BASE_URL`, `CHAT_API_KEY`, `CHAT_MODEL`, `CHAT_TIMEOUT_SECONDS`, `CHAT_MAX_TOKENS`, `CHAT_THINK` | chat gateway |
| `EMBEDDING_PROVIDER`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL`, `EMBEDDING_TIMEOUT_SECONDS`, `EMBEDDING_CONCURRENCY`, `MODEL_PROFILE`, `EMBEDDING_CACHE_ENABLED` | embedding gateway and profile/cache services |
| `AZURE_OPENAI_*` | Azure provider and Entra access-token path when selected |
| `DEFAULT_WORKSPACE_ID`, `TOP_K`, `CHUNK_*`, `DEFAULT_CHUNKING_PROFILE`, `CHUNKING_PROFILES`, `MIN_RETRIEVAL_SCORE`, `HYBRID_SEARCH_ENABLED`, `RETRIEVAL_CANDIDATE_K`, `RRF_K`, `RERANK_*` | ingestion and retrieval settings |
| `ASSET_*`, `MEMORY_*`, `LANGFUSE_*` | assets, conversation lifecycle, observability |

The Python checked-in defaults are 800/120 chunking, top 5, 0.35 semantic floor, hybrid enabled, 20 candidates, RRF 60, 10 retained turns, and summary after 8 turns. The actual Kotlin `.env` must retain the active local values from the Python `.env`, including endpoints, selected providers, workspace/profile names, and database host. Secrets remain only in ignored `.env` files.

## Implementation order

### SVC-01 — Freeze Python backend evidence

- Copy or reference the Python API schemas, backend tests, evaluation datasets, prompts, exact abstentions, loader fixtures, and current `.env` key/value configuration.
- Record the source commit above in Kotlin test fixtures. Treat Python tests and behavior as the source of truth if documents conflict.
- Gate: a Kotlin parity matrix maps each Python module/test to an intended Kotlin component and test. No assumptions remain about payloads, SSE events, errors, or fallback wording.

### SVC-02 — Align build and configuration

- Simplify the Maven dependency set: retain the Spring AI BOM and provider starters needed for the active configuration; remove duplicate PDF reader declarations and prevent pgvector/chat-memory auto-configuration from owning persistence.
- Add `spring.config.import=optional:file:.env[.properties]` (or equivalent) and typed `@ConfigurationProperties` that bind the Python variable names directly.
- Create the ignored Kotlin `.env` from the current Python `.env`; update `.env.example` with Python-compatible, non-secret defaults. Replace outdated profile property names and hard-coded topology comments.
- Gate: a configuration test loads the copied `.env` without values leaking to logs and proves Kotlin resolves the same active provider, model, database/schema/table, workspace, profile, and tuning values as Python.

### SVC-03 — Shared database compatibility layer

- Implement a read-only `RagSchemaValidator`. Verify `rag.schema_migrations` contains exactly the five required versions, the required tables/columns exist, `document_chunks.embedding` matches the configured vector dimension, and the selected ready model profile points to an existing compatible table.
- Implement JDBC repositories matching Python SQL for workspaces, sessions, conversation summaries/turns, chunks, source documents, model profiles/cache, and assets. Use parameterized SQL and a strict identifier allow-list for schema/table/profile storage targets.
- Use deterministic UUIDv5 compatible with Python's `uuid.uuid5(uuid.NAMESPACE_URL, chunk_id)`. Preserve JSONB keys and float32 cache encoding.
- Gate: repository fixture tests read Python-created rows and produce rows Python can read. Kotlin performs no DDL and does not modify data during startup validation.

### SVC-04 — Identity, workspaces, sessions, assets, and errors

- Port local and trusted-gateway principal resolution, workspace membership checks, session ownership masking, retention, asset authorization, MIME allow-list, ETag, cache control, and safe error envelope.
- Gate: Python authorization/session/asset test cases pass against Kotlin; a foreign or archived session is indistinguishable from a missing one.

### SVC-05 — Retrieval, chat, profiles, and memory

- Build provider-neutral `ChatGateway` and `EmbeddingGateway`. Use Spring AI only at these boundaries: OpenAI-compatible or Azure chat, Ollama or Azure embedding.
- Reproduce query rewriting, prompt construction, grounded abstention, semantic thresholding, PostgreSQL lexical search, RRF, reranking toggle, source/asset references, and persistence of original turns.
- Reproduce profile resolution, prefix-aware cache keys, float-vector length checks, profile-specific storage-target lookup, and profile warming without re-chunking.
- Gate: Python evaluation cases and retrieval fixtures match for ranking, grounding, citations, profile filtering, cache behavior, and session summaries.

### SVC-06 — Loaders, chunkers, and source lifecycle

- Port Markdown, HTML, text, PDF, Word, Python, Java, Kotlin, and generic-code loaders. Preserve document ordering, code symbols/line ranges, repository metadata, and embedded image associations.
- Preserve the default chunking profile and deterministic chunk IDs: `{docId}:{index}` for default and `{docId}:{profile}:{index}` otherwise.
- Embed changed content before beginning the database transaction; then replace source, chunks, and asset links atomically. Delete stale documents only below the scanned root. Ensure dry run calls neither embedding nor persistence.
- Gate: Python loader/chunker/ingestion tests pass, including unchanged skip, replacement failure rollback, recursive cleanup, dry run, assets, and code metadata.

### SVC-07 — Streaming and observability

- Port completed and streaming chat behavior, including exact SSE event order, terminal error handling, cancellation, and no retry loop after a disconnect.
- Port readiness detail, provider/database failure handling, and optional Langfuse behavior without raw-content capture unless `LANGFUSE_CAPTURE_CONTENT=true`.
- Gate: stream, health/readiness, observability, and failure-path Python tests pass. Routine tests do not call a live model or database.

### SVC-08 — Cross-runtime verification

- You run the planned shared-database verification against the existing RAG-dev-plane environment. Kotlin first validates without writes, then uses a dedicated workspace/profile for controlled ingest, retrieval, session, profile-cache, and asset round trips.
- Compare Python reads after Kotlin writes and Kotlin reads after Python writes. Keep the production/default workspace untouched during this exercise.
- Gate: you confirm both runtimes operate safely against the same database and the Kotlin service passes its parity suite. Only then consider an isolated Embabel experiment.

## Risks to resolve during implementation

1. Spring AI's `PgVectorStore` is not a safe owner of this schema. Its auto-initialization and document mapping may diverge from Python's UUID, metadata, source lifecycle, full-text, profile, and asset behavior. The JDBC layer is required.
2. The active Python `.env` has not been copied into this workspace. SVC-02 must transfer its current values without printing secrets.
3. Azure/Entra paths need an explicit Kotlin token-provider implementation if the active `.env` selects `PG_USE_ENTRA=true` or an Azure provider. Validate the active local path first.
4. Alternative model profiles require a table that already exists in the Python-managed schema and has the profile's exact vector dimension. Kotlin must reject a missing or incompatible target instead of provisioning one.

## Approval point

Approve this plan to start SVC-01 and SVC-02. The first implementation output will be a Python-to-Kotlin parity matrix, a secret-safe `.env` alignment report, and configuration/build changes; it will not change the shared database.
