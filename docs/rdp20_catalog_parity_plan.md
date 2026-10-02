# RDP-20 — Python NP-20 document catalog parity

**Last reviewed:** 2026-10-03
**Status:** G1/G2 approved 2026-10-03; implementation and offline verification tracked in [verification](rdp20_verification.md). Separate live acceptance, G3 and G4 remain pending. The proposal below records the approved scope and sequence.
**Source:** local `E:/Workspace/AI_Stuff/RAG-dev-plane` at `8599b26`, including backend implementation `afee99c`, rollout `25ea33b`, and frontend implementation `c58e667`. Local main is five commits ahead of cached origin/main; no fetch was performed.

The authoritative browser contract is Python `docs/frontend_architecture.md`, section “Recent Document Metadata Contract (NP-20)”. The latest entries in both `docs/agent_handoff` direction files supersede the earlier design/rollout blockers. Python records 134 offline tests and local API/PostgreSQL acceptance; frontend records 34 tests, type checks and build. Browser integration for the new document panel remains unverified. These results were reviewed, not rerun by Kotlin.

## Required outcome

The existing frontend document panel must work against Kotlin using the same workspace/profile defaults, response shapes, safe errors and protected cache behavior as Python. Both backends must keep the shared catalog accurate when either publishes, replaces, warms or removes indexed documents. A read-only GET implementation alone cannot deliver this outcome.

No frontend badge/color logic belongs in Kotlin. No document-download endpoint, document-selection retrieval filter, OCR, new provider call or new schema migration is requested. Python owns migration, historical reconciliation and catalog certification.

## API contract to add

`GET /workspaces/{workspace_id}/documents` resolves the existing principal and checks current membership before returning each page. Owners and members see the same authorized corpus. The route performs no embedding/chat call or source-filesystem read.

| Area | Required behavior |
| --- | --- |
| Query | `limit` defaults to 25, integer 1–100; optional nonempty opaque `cursor`, maximum 4096 characters; optional model/chunking names use `[A-Za-z0-9][A-Za-z0-9_-]*`, maximum 100 characters. Omission uses the same configured defaults as chat. |
| Envelope | Snake-case `workspace_id`, `scope`, `items`, `page`. Scope contains resolved `model_profile`, `chunking_profile`. |
| Item | Existing opaque `doc_id`, sanitized nonempty `title` and basename-only `file_name`, canonical `document_type`, nullable UTC RFC 3339 `last_ingested_at`, exact `indexed_chunk_count`. |
| Types | `word`, `pdf`, `markdown`, `html`, `text`, `code`, `unknown`. Kotlin PPTX remains `unknown`; preserve its private `document_format=pptx` metadata without extending the public enum. |
| Display safety | Match Python control/bidi-format removal and whitespace normalization; truncate title/filename to 300/255 Unicode code points, not UTF-16 code units. Reject absolute-path titles as display titles. Exclude source/root paths, content, hashes, provider and storage details. |
| Count | One resolved model/chunking/workspace/document scope; 0 is valid; use Long constrained to 0–9007199254740991. Never sum profiles or infer counts from citations. |
| Page | `limit`, `has_more`, nullable `next_cursor`, opaque decimal-string `list_revision`, UTC RFC 3339 `generated_at`. Fetch limit+1; empty ready scopes return 200 with no continuation. |
| Order | Successful changed-ingestion time descending, null last, bytewise document ID ascending (`COLLATE "C"`); keyset pagination, no hidden time window. |
| Headers | `Cache-Control: private, no-store`; `Vary: Cookie, Authorization`, including protected error handling as specified by the Python contract. |
| Errors | Existing safe 401/403/422/500 envelope; 409 `document_list_changed` for expired cursor, changed revision or changed implicit default scope; 503 `document_list_unavailable` when disabled, unsupported, uncertified or unavailable. Copy canonical safe messages. |

Unknown/non-ready/incompatible explicit profiles and malformed/tampered/wrong-principal/wrong-workspace/wrong-limit/explicitly changed-scope cursors return 422. Missing or invalid configured default readiness returns 503. Revalidate authorization on every continuation; a cursor is never an access grant.

## Kotlin changes and dependencies

| Work package | Existing boundary | Required change |
| --- | --- | --- |
| RDP-20A: schema/config | `SharedSchemaValidator`, `AppProperties`, application-example configuration | Read-only validation of Python migration `006_document_index_metadata` and its four relations/required columns; typed disabled-by-default listing configuration and environment-supplied signing secret. Separate schema compatibility required by writers from runtime listing enablement and catalog certification. Missing 006 must prevent upgraded publication writers from running; a disabled/uncertified catalog returns route 503 without disabling unrelated chat. |
| RDP-20B: canonical metadata | `JdbcChunkRepository`, ingestion chunk preparation, retrieval fallback | Resolve DATA-01 for writes, exact counts and replacement deletion: write canonical `doc_id`; retain read compatibility for historical `document_id`; audit default chunking metadata and deterministic UUID identity. Dual-key deletion/count fallback must avoid double counting and profile leakage. Historical aliases/backfill remain operator-owned, with reviewed reconciliation and no automatic data rewrite. |
| RDP-20C: publication lifecycle | `DocumentIngestionService`, JDBC source/chunk/asset repositories | Atomically publish selected-profile vectors, metadata, owned asset references and revision. Preserve unchanged time/count; failed replacement rolls back all publication effects; successful zero-chunk sources remain listed; dry run makes no publication changes. Coordinate shared source provenance across model profiles. |
| RDP-20D: warming/cleanup | `ProfiledEmbeddingService`, ingestion directory cleanup | Warming carries source recency, including null historical time, into target-profile publication without rechunking. Scoped cleanup removes only the intended publication/vectors/references and advances affected revisions; delete shared source/asset rows only when remaining publications no longer require them. Match Python lock order and connection ownership. |
| RDP-20E: catalog query | New JDBC catalog repository/service and controller/DTO boundary, existing identity/error advice | Repeatable-read, read-only page transaction: certification/profile readiness, revision and rows share a snapshot. Validate signing tokens and emit exact JSON, UTC timestamps, safe errors and headers. No new dependency expected; confirm at G2. |
| RDP-20F: acceptance | Existing MVC/persistence test boundaries, optional cross-runtime fixtures | Offline contract and lifecycle coverage, then separately approved real PostgreSQL and frontend adapter/browser checks. Verify catalog continuity across Python and Kotlin and old chat/SSE behavior. |

Python 006 relations are `document_index_metadata`, `document_index_assets`, `document_list_revisions`, and `document_catalog_state`. Metadata primary key is `(workspace_id, model_profile, chunking_profile, doc_id)`. Asset ownership is per publication; immutable assets can be shared. Catalog certification is deployment-wide and operator-owned. Kotlin must not set `ready=true` merely because new ingestion succeeds.

The current Kotlin replacement deletes document assets across profiles, and directory cleanup uses the default chunk target. Those paths need a concrete profile-aware audit before enabling catalog publication. Preserve assets and sources needed by another profile even if the replaced profile has no images. Logical database ownership and private file garbage collection must agree; do not delete shared files during rollback or replacement without proof that no references remain.

## Shared locking and cursors

Port Python's PostgreSQL advisory lock keys and order exactly: profile publication key `document-publication:{schema}:{profile}`, then document key from compact JSON `["document", schema, workspace, chunking, doc_id]`, hashed by PostgreSQL `hashtextextended(...,0)`. Do not substitute JVM hashing or differently spaced/escaped JSON. Check Python's Unicode escaping in document key serialization. Warming/reconciliation session locks require the same connection throughout protected work; preserve connection ownership and avoid pool deadlocks. Document-level locking across model profiles is in scope; general simultaneous chat writes remain RDP-19.

Python cursor format is unpadded URL-safe base64 of canonical compact JSON plus a hexadecimal HMAC-SHA256 signature, separated by a dot. Fields are `v`, `workspace`, `model`, `chunking`, HMAC-derived `subject`, `limit`, `revision`, `last_id`, nullable `last_time`, `issued`, `expires`. Principal hashing uses `subject\0` plus subject bytes. Preserve a 900-second lifetime from the first page across continuation pages, constant-time signature validation, and issuance/expiry validation. Preserve PostgreSQL microsecond timestamps for keysets; never round them to milliseconds.

For load-balanced continuation, both services need the same signing secret, token protocol, database revision scope and effective defaults. No secret values belong in docs. If those deployment prerequisites differ, common routing cannot promise continuation compatibility. Publish deterministic Python/Kotlin token fixtures, including Unicode principals/document IDs, rather than assuming serializers match.

## Sequence and approval proposal

1. G1: activate RDP-20 from the backlog. Scope covers API/config/schema validation, canonical metadata, ingestion/asset/warming publication, JDBC queries, tests and docs. No live services, migration or historical writes included. Rough validation is focused offline contract/lifecycle checks followed by the full offline suite and formatting.
2. G2: finalize controller/service/repository wiring, configuration names, schema compatibility, cursor protocol, lock/asset ownership, warming and cleanup design. Proposed approach is one shared JDBC publication protocol plus a provider-free catalog reader. Rejected alternative: compute a list/count directly from vectors; it loses zero-chunk documents, trustworthy recency and atomic revisions and cannot coordinate assets.
3. Implement in dependency order: schema/config and canonical keys; shared publication/ownership/locking; warming/cleanup; route/cursors; regression coverage. Do not enable catalog listing as a substitute for writer interoperability.
4. Obtain separate approval for real database fixtures, HTTP/provider-free integration, server restarts and browser acceptance as required. Python operator verifies migration/reconciliation in each environment; local rollout evidence does not certify other deployments or historical Kotlin rows.
5. G3 before push/merge; G4 before phase closure. Rollback the application changes without undoing Python migration 006 or deleting catalog/user data. An old Kotlin writer does not preserve the new catalog, so rollback must also remove it from ingestion/warming traffic; turning listing off alone is insufficient.

## Required evidence

- MVC contract fixtures: exact envelope, bounds, explicit/default profiles, 0 count, null timestamps, Unicode names, unknown/PPTX type, no private fields, error messages/headers and revoked membership.
- Cursor fixtures: tampering, malformed types, expiration, binding, implicit/explicit scope changes, null-time keysets, timestamp precision, stable traversal and cross-runtime signing/verification.
- Offline repository/lifecycle checks: 006 validation, count scoping, canonical/legacy IDs, unchanged/failed/zero-chunk ingestion, revision rules, transaction rollback, warming recency, cleanup and cross-profile image preservation.
- Separate opt-in PostgreSQL fixtures: actual locks, transaction snapshots, concurrent publication/page 409 behavior, Python/Kotlin readback and cross-runtime cursor continuation with matching configuration. Use dedicated workspace/profile data and a reviewed cleanup plan; fake JDBC tests alone do not prove locking.
- Frontend adapter then browser: first page/load more, refresh, 409 restart, 401/403 clearing, 503 retry, badge values, null history and unchanged chat/SSE behavior. Actual load-balancer routing requires its own configured acceptance.

Selected Python source tests: `tests/test_document_catalog.py`, `tests/test_document_catalog_store.py`, `tests/test_document_publication.py`. Python rollout scripts and QA JSON are evidence/reference, not permission to run them against shared data.
