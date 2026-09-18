# Spring Boot Backend Handoff

**Purpose:** reimplement the current backend in Kotlin while keeping the existing
React/Vite frontend unchanged. This is a behavior and contract handoff, not a
directive to preserve the Python internals. Source inspected: 2026-09-17.

## Executive decision

Build a Kotlin application on **Spring Boot 4.1.x** and **Spring AI 2.0.x**.
Use Spring AI for the chat and embedding provider boundary, but retain a small,
application-owned PostgreSQL/pgvector repository for ingestion, retrieval,
workspaces, sessions, and assets. Do **not** begin by adding an agent framework:
the existing product is a deterministic RAG API, and its frontend requires exact
HTTP/SSE behavior. Introduce Embabel later behind a separate, non-default
endpoint or service interface after parity is complete.

Spring AI 2.0.x explicitly supports Spring Boot 4.0.x and 4.1.x. Its `ChatClient`
supports synchronous and reactive streaming calls, and `PgVectorStore` supports
metadata filtering. References: [Spring AI getting started](https://docs.spring.io/spring-ai/reference/getting-started.html),
[ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html),
and [PGvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html).

The current PostgreSQL tables deliberately contain more than Spring AI's default
vector-store shape: stable chunk IDs, workspace/profile scope, source documents,
assets, conversations, and model-profile/cache tables. Although the standard
`PgVectorStore` is useful for a greenfield collection, it should not own or
initialize this existing schema. Keep database DDL in Flyway migrations and use
`JdbcTemplate`/`NamedParameterJdbcTemplate` (or jOOQ if already selected) for
the repository. This preserves the present index, RRF, full-text search, and
atomic replacement behavior.

## Non-negotiable frontend contract

The authoritative frontend contract remains
[`frontend_architecture.md`](frontend_architecture.md). Preserve its routes,
request fields, status codes, safe error envelope, and SSE protocol exactly.
Do not move the API under `/api`, rename JSON fields, expose a browser supplied
user ID, or make the frontend understand Spring AI/Embabel types.

### Routes and payloads

| Route | Required behavior |
| --- | --- |
| `GET /health` | `{ "status": "ok", "environment": "local\|..." }`; no provider call. |
| `GET /readiness` | Check PostgreSQL/vector access and return status, vector-store state, configured chat/embedding model, and store type. |
| `GET /workspaces` | List only current principal's workspaces, ordered by display name then ID. Return `{principal:{display_name},workspaces:[{workspace_id,display_name,role}]}`. |
| `POST /chat` | Completed grounded answer. Request: `question` (nonblank), optional `top_k` (1–20), `include_debug`, `session_id`, `workspace_id`, `chunking_profile`, `model_profile`. Response: `answer`, `sources`, `grounded`, `session_id`, `debug`. |
| `POST /chat/stream` | POST SSE, not `EventSource`. Header behavior: `text/event-stream; charset=utf-8`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`. Emit zero or more `answer`, then exactly one `meta` or `error`, then exactly one `done`. |
| `GET /chat/sessions?workspace_id=...` | Owned, active sessions for an authorized workspace; bare array; newest `updated_at` first. |
| `GET/PATCH/DELETE /chat/sessions/{id}` | Read, rename (`{title}`), or archive an owned active session. Delete returns 204. A foreign/missing/archived ID is 404. |
| `POST /ingest` | Backend/operator ingestion. Body: `source_path`, `recursive`, optional workspace/profile names, `dry_run`. Keep out of the SPA. |
| `GET /workspaces/{workspace_id}/assets/{asset_id}` | Authorized, browser-safe image bytes only. Support ETag/304; send private caching, inline disposition, and `X-Content-Type-Options: nosniff`. |
| `POST /admin/model-profiles/{profile_name}/warm` | Local-only profile warmer. Return 403 outside `APP_ENV=local`. |

Use the existing error envelope for every pre-stream error:

```json
{ "code": "workspace_access_denied", "message": "You do not have access to this workspace." }
```

Status/code pairs are 401 `authentication_required`, 403
`workspace_access_denied`, 404 `resource_not_found`, 415
`unsupported_media_type`, 422 `invalid_request`, 502 `upstream_unavailable`,
and 500 `internal_error`. Implement one `@RestControllerAdvice`; log causes
server-side but never place them in the response. After SSE headers are sent,
emit `error` with that same envelope and a terminal `done` instead of changing
the HTTP status.

### SSE details to preserve

`answer` data is `{ "delta": "..." }`; it contains answer text only. `meta`
is `{session_id, grounded, sources, debug}`. `done` is `{ "reason":
"completed" }` after `meta`, or `{ "reason": "error" }` after `error`.
`SourceReference` is:

```json
{
  "doc_id": "...", "chunk_id": "...", "source_path": "...",
  "title": null, "page": null, "section": null,
  "score": 0.92, "snippet": "...", "assets": []
}
```

An asset has `asset_id`, `media_type`, nullable `width`, `height`, `alt_text`,
`caption`, and `content_url`. Only emit a relative `content_url` for PNG, JPEG,
GIF, or WebP. The current frontend deliberately supports cancellation via fetch
`AbortController`; cancellation must dispose the upstream subscription and must
not create a retry loop.

## Current product behavior to reproduce

### Security and tenancy

- Local mode resolves a fixed, server-side principal (`LOCAL_SUBJECT` etc.).
  Gateway mode requires a trusted gateway-injected subject header; the browser
  never supplies a subject.
- Every chat and session operation revalidates membership. A requested
  `workspace_id` is scope, not authorization.
- Session reads/rename/archive first prove ownership, then revalidate access to
  the session's stored workspace. Return 404 for ownership mismatch so it is
  indistinguishable from absence.
- Ingestion is currently an operator endpoint and accepts a filesystem path;
  keep it behind local/operator authorization in the replacement. Do not expose
  arbitrary server paths to the office SPA.

### Ingestion

Supported loaders: Markdown, HTML, text, PDF, Word `.docx`, and source/code
files. `.docx` keeps ordered document structure and extracted embedded images;
unsafe/corrupt/encrypted packages are rejected. Code preserves language,
symbol/line-range where parser-aware chunking succeeds, and repository metadata
(repository, branch, commit, relative path). Java and Kotlin use Tree-sitter
with a generic-text fallback.

For each workspace plus chunking profile, content hashing makes an unchanged
document a no-op. Changed documents are embedded before their old chunks are
removed, then source row, chunks, asset associations, and metadata are replaced
in one database transaction. A recursive re-scan removes documents absent from
that same root path. `dry_run=true` computes load/chunk counts only: it must not
call embeddings or write data.

The baseline chunking profile is `default`: recursive/default chunker, 800
characters, 120 overlap. Profiles isolate experiments; default chunk IDs are
`{docId}:{index}`, alternative IDs are `{docId}:{profile}:{index}`. Preserve
these identifiers because the current database and citations use them.

### Retrieval and answering

1. Resolve a ready embedding model profile and a chunking profile.
2. Rewrite a follow-up into a standalone retrieval query using only the
   session's compact summary and up to six latest turns; first-turn questions
   are used unchanged.
3. Embed the query with the **query prefix** of that model profile.
4. Search only the requested workspace and chunking profile. Default PostgreSQL
   behavior is semantic cosine similarity plus full-text search.
5. Discard semantic candidates below `MIN_RETRIEVAL_SCORE` (0.35). Fuse
   semantic and lexical rankings with reciprocal rank fusion (`RRF_K=60`), then
   optionally rerank. Defaults: top 5, candidate 20, reranker off.
6. If nothing remains, return the current ungrounded fallback and no sources.
7. Otherwise give the chat model only numbered retrieved passages and the
   grounded system instruction. Persist the original user question and answer,
   then return citations from the retrieved chunks.

The answer prompt system rule is: use only supplied context and abstain if it
does not contain the answer. Preserve the exact current fallback strings until
contract tests demonstrate that wording is not relied upon. Conversation raw
turns are bounded to 10; at eight new turns, generate/replace a compact factual
summary. Retention defaults to 90 days.

### Embedding model profiles and cache

`rag.model_profiles` keeps name, provider, model, dimensions, storage target,
query/document prefixes, and `draft|warming|ready|archived` state. Only `ready`
profiles may serve ingest/retrieval. The default is the existing Ollama
`nomic-embed-text` 768-dimension `document_chunks` table. The recommended local
evaluation pairing is bge-m3 retrieval in its isolated 1024-dimension table and
DictaLM chat; retain default/nomic as rollback.

Cache keys SHA-256 the provider/model/dimensions/purpose/normalized text.
Validate returned vector length before storing or searching. Document and query
prefixes are part of the effective input and cache key. The local-only warmer
copies one authorized workspace's chunks into a target profile, reporting
chunks, cache hits, provider calls, and dry-run results. It does not re-chunk.

## Database workspace

Keep PostgreSQL + `pgvector`, `rag` schema, and the five existing migrations as
the baseline. Port them into Flyway in the new service (for example
`V001__baseline.sql` through `V005__model_profiles.sql`) without changing their
logical schema. Migration execution belongs to deploy/startup governance, not
to vector-store auto-initialization.

Important tables: `document_chunks` (`content`, JSONB `metadata`,
`embedding vector(n)`), `source_documents`, `workspaces`,
`workspace_members`, `chat_sessions`, `conversation_turns`,
`conversation_summaries`, `document_assets`, `chunk_assets`, `model_profiles`,
and `embedding_cache`.

For profile tables with a different vector dimension, add an explicit Flyway
migration that creates the target `vector(n)` table plus HNSW and full-text
indexes, then register that table as the profile `storage_target`. Never resize
the default vector column in place. PostgreSQL HNSW supports up to 2,000
dimensions, so the 768 and 1024 profiles are valid.

Write a `RagChunkRepository` that owns these SQL operations:

- `replaceDocumentAtomically(...)`, `deleteMissingDocuments(...)`, and content
  hash lookup;
- semantic `embedding <=> :query` and lexical `websearch_to_tsquery` searches;
- workspace/profile filters in every query;
- document asset metadata and chunk-asset joins;
- profile table resolution only after strict identifier validation; and
- deterministic UUIDv5 derivation from `chunk_id`, matching existing rows.

Spring AI's `VectorStore` may be wrapped by an adapter later, but do not allow
its schema initializer to create a parallel `vector_store` table. Its metadata
filter API is useful conceptually, but the custom repository is required for
the existing profile tables, atomic source replacement, RRF full-text search,
and asset joins.

## Recommended Kotlin/Spring structure

```text
src/main/kotlin/<package>/
  api/                 # controllers, request/response DTOs, exception mapping, SSE encoder
  security/             # PrincipalResolver + workspace authorization
  application/          # ChatService, RetrievalService, IngestionService, ProfileWarmer
  domain/               # Chunk, Citation, Session, Workspace, ModelProfile, Asset
  persistence/          # Flyway-facing JDBC repositories and SQL mappers
  ai/                   # ChatGateway, EmbeddingGateway, Spring AI implementations
  ingest/               # loaders, chunkers, repository metadata, asset store
  config/               # @ConfigurationProperties and bean wiring
```

Use constructor injection, Kotlin data classes, Bean Validation (`@field:...`),
and `@ConfigurationProperties` rather than loose environment reads. Keep these
interfaces independent of Spring AI so providers stay replaceable:

```kotlin
interface ChatGateway {
    fun complete(system: String?, user: String): String
    fun stream(system: String?, user: String): Flux<String>
}

interface EmbeddingGateway {
    fun embed(model: String, text: String): FloatArray
}

interface RetrievalRepository {
    fun semantic(query: FloatArray, scope: SearchScope, limit: Int): List<RetrievedChunk>
    fun lexical(query: String, scope: SearchScope, limit: Int): List<RetrievedChunk>
}
```

`SpringAiChatGateway` should inject `ChatClient.Builder`, build the existing
system/user messages, and use `.call().content()` for completed calls and
`.stream().content()` for real token streaming. `SpringAiEmbeddingGateway`
should inject the provider's `EmbeddingModel`; use an explicit model/options
per selected profile when dynamic profile selection requires it. Keep chat and
embedding gateways separate even if both happen to use Ollama or Azure.

Choose one web execution model deliberately. The cleanest parity path is Spring
MVC plus JDBC and an `SseEmitter` whose upstream `Flux` subscription is
cancelled on completion/error/client disconnect. A WebFlux controller is also
valid, but then schedule blocking JDBC work on `boundedElastic` and do not block
the event loop. Do not mix styles incidentally; Spring AI documents caveats for
mixed imperative/reactive `ChatClient` use.

## Dependency and configuration baseline

Use the Spring Boot dependency platform and import the Spring AI BOM rather
than individually pinning Spring AI modules. Pin the Spring AI line (currently
2.0.1) in one version-catalog/property, then take maintenance updates only
after contract tests pass. Confirm the Java/Kotlin versions against the selected
Boot 4.1.x release before changing the existing project versions.

```kotlin
plugins {
    kotlin("jvm") version "<Boot-compatible Kotlin>"
    kotlin("plugin.spring") version "<same>"
    id("org.springframework.boot") version "4.1.1"
}

dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:2.0.1") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    // Add the OpenAI starter when an OpenAI-compatible/Azure path is chosen.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql")
}
```

The exact starter/module name for a nonstandard OpenAI-compatible gateway must
be validated against the chosen Spring AI 2.0.1 provider documentation. If it
cannot reproduce the current endpoint/model semantics, implement that one
`ChatGateway` with Spring `WebClient`; the public domain interfaces remain
unchanged.

Illustrative properties (use the existing project naming convention, not both
this and Python names):

```yaml
app:
  environment: local
  auth: { mode: local, local-subject: local-dev }
  rag: { default-workspace-id: local, top-k: 5, min-score: 0.35,
         hybrid-search-enabled: true, candidate-k: 20, rrf-k: 60,
         memory-max-turns: 10, memory-summary-after-turns: 8 }
  assets: { storage-root: .rag-assets }
spring:
  datasource: { url: jdbc:postgresql://localhost:5432/ragdb, username: rag_app }
  flyway: { schemas: rag, default-schema: rag }
  ai:
    model: { embedding: ollama }
    ollama:
      base-url: http://localhost:11434
      embedding: { model: nomic-embed-text }
```

Spring AI 2.0 enables embedding auto-configuration through
`spring.ai.model.embedding=ollama`; use `spring.ai.ollama.embedding.*` for the
model and request options. Keep credentials in environment/secret management,
not committed YAML. Do not enable model pulling in production application
startup without an explicit operational decision.

## Embabel: useful later, not the parity engine

Embabel is a Kotlin/JVM agent framework built on Spring AI. It models actions,
goals, conditions, and dynamically planned flows; it offers annotation and
Kotlin DSL authoring. Its stable starter is
`com.embabel.agent:embabel-agent-starter:0.3.0`; the project documentation says
Gradle needs Maven Central plus Spring Milestones because of a transitive MCP
BOM. References: [Embabel README](https://github.com/embabel/embabel-agent)
and [Kotlin starter instructions](https://github.com/embabel/embabel-agent#adding-embabel-agent-framework-to-your-project).

Add Embabel only after the parity test suite is green, in a dedicated
`agent-experiments` module or feature flag. The first safe use cases are an
operator-only ingestion diagnosis/workflow, a research/triage endpoint, or a
supervised answer-improvement experiment. It must call the same authorized
`RetrievalService` and must never receive raw SQL, unrestricted filesystem
paths, browser identity, or an implicit ability to mutate documents.

Do not replace the normal `/chat` path with an open/planned Embabel agent. That
would change latency, determinism, auditability, and the grounding contract.
If agentic RAG is adopted, expose a new versioned endpoint and make citations,
tool allow-list, token/cost caps, timeouts, cancellation, traces, and approval
requirements explicit.

## Delivery plan

1. **Freeze and test the contract.** Port frontend examples into JSON/SSE
   contract tests. Add Python-vs-Kotlin golden fixtures for authorization,
   errors, sessions, source references, and SSE event order.
2. **Foundation.** Configure Boot, Flyway, datasource, typed configuration,
   health/readiness, logging, and principal resolution. Import/validate the
   existing `rag` schema against a disposable PostgreSQL/pgvector Testcontainer.
3. **Persistence/security.** Implement workspaces, session repository, safe
   error mapping, asset authorization, content-addressed asset storage, and
   migration validation.
4. **RAG core.** Implement model profiles/cache, custom pgvector semantic and
   full-text repositories, RRF/thresholding, prompts, and Spring AI gateways.
   Confirm default 768-dimension nomic parity before adding bge-m3.
5. **Ingestion.** Port loaders/chunkers in priority order: text/Markdown/code,
   HTML/PDF, then structured Word/images. Preserve deterministic IDs, hashes,
   dry runs, atomic replace, and stale-file cleanup.
6. **Streaming parity.** Implement actual token streaming while preserving the
   required SSE envelope/order and cancellation. Validate with the unchanged
   frontend and Nginx buffering disabled.
7. **Operational hardening.** Metrics/traces without raw content by default;
   integration tests for provider unavailable, database unavailable, gateway
   identity, tenant isolation, and binary asset safety.
8. **Optional Embabel spike.** Isolated and feature-flagged; promote only with
   a documented benefit and no regression in default `/chat` behavior.

## Acceptance gates

- Existing frontend runs unchanged against the Kotlin service.
- Tenant A cannot list/read/change Tenant B's workspaces, sessions, chunks, or
  assets; a browser cannot impersonate a user with a header.
- Contract tests cover ordinary and post-start SSE errors, final `done`,
  cancellation, and 404 ownership masking.
- Fixture ingestion proves unchanged skip, changed atomic replacement, scoped
  directory deletion, dry-run no-write/no-embedding, and image access checks.
- Golden retrieval fixtures prove workspace/profile filtering, threshold/RRF
  order, citations, and default model dimensions.
- PostgreSQL migrations are applied only by Flyway/deployment, never inferred
  or created by application vector-store auto-configuration.
- Live tests remain separately gated: no CI test silently calls Ollama, Azure,
  or a real database without explicit credentials/approval.

## Open decisions for the Spring project

1. Is the initial target strictly local Ollama, or must Azure OpenAI be included
   in the first production-capable increment?
2. Which gateway provides trusted identity headers, and which headers will be
   stripped/injected by Nginx?
3. Is a direct import of the existing `rag` database required, or may the
   Spring project start from clean Flyway-managed data?
4. Should structured Word/PDF ingestion be parity scope one or a later phase?
5. What approved operator authentication replaces local-only ingestion/profile
   warming in office deployments?

