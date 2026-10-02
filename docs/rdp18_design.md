# RDP-18: frontend compatibility across Python and Kotlin

Reviewed: 2026-10-02. G1 and G2 approved; implementation verified offline.

## Evidence from local source

- The frontend `apps/rag-dev-plane/src/api.ts` reads `principal.display_name`,
  `workspace_id`, and `display_name`. Kotlin workspace responses expose camelCase.
  Its existing API test explicitly asserts the incompatible camelCase keys.
- The frontend lists chats with `GET /chat/sessions?workspace_id=...`.
  Kotlin's unnamed `@RequestParam workspaceId` binds a different query name.
- The frontend posts `workspace_id`, `session_id`, and `include_debug` to
  `/chat/stream`. Kotlin `ChatRequest` declares camelCase properties without
  explicit JSON names. This cannot reliably preserve the selected workspace or
  existing session and can reject the request or use configured defaults.
- Kotlin session summary/detail/turn DTOs also expose camelCase fields.
- Kotlin stream citations use `document_id`; Python and the frontend use `doc_id`.
  Kotlin stream errors use `detail`; the frontend expects `code` and `message`.
- Live frontend adapter verification exposed Kotlin retrieval reading only
  metadata `document_id`, while Python-origin chunks use `doc_id`. The reader now
  prefers `doc_id` and falls back to `document_id`, without modifying stored rows.
- Workspace membership SQL is equivalent in the two implementations. Both local
  identity defaults are `local-dev`, and both support the same configurable
  gateway identity headers. Runtime environment equality has not been verified.
- Both backends use shared PostgreSQL session/turn tables. Kotlin does not need
  in-memory session affinity for sequential requests to move between services.
  Actual concurrent execution and cross-backend HTTP readback remain unverified.
- The frontend proxies both API prefixes to localhost:8000. Kotlin's default
  port is also 8000 (override: `API_PORT`). Parallel local services need separate
  listening ports and one proxy/load balancer serving these routes consistently.

## Implementation evidence

Explicit DTO JSON names, camelCase chat input aliases, session query binding,
masked continuation errors, safe malformed-body/query validation, rename
validation, stream error envelopes, and SSE headers are implemented. Chat
provider HTTP errors now use the frontend's `upstream_unavailable` code.

Boot MVC context tests initially caught an existing class-level `@Validated`
proxy throwing an unhandled constraint exception for blank workspace queries.
Removing that annotation lets MVC's built-in method validation return the safe
422 envelope. The tests use Boot 4.1.1 Jackson and HTTP converter auto-configuration
alongside the application's existing Jackson 2 helper bean; the HTTP converter
handles the new annotations and serializes timestamps correctly.

Focused verification passed 22 tests. After adding malformed-input and provider
error coverage, the full offline suite reports 117 tests, 0 failures/errors,
5 live opt-in skips (112 passed), including 11 frontend compatibility tests.
`spotless:apply`, `spotless:check`, and `git diff --check` passed.

After the live citation-reader correction, the final full suite reports 120
tests, 0 failures/errors, 5 live opt-in skips (115 passed). Three JDBC regression
tests cover Python/legacy Kotlin document identity and canonical-key precedence
through semantic/lexical row mapping. Final formatting and diff checks passed.

A redacted equality-only comparison of selected `.env` entries confirmed matching
PostgreSQL host/port/database/schema and model profile entries. Both files also
specify the same API port: one process needs an override for parallel operation.
Neither file defines auth mode/local subject/default workspace/default chunking
profile/asset root. Source defaults agree for identity and workspace, but asset
roots are relative to different checkouts. Active process/IDE environment
overrides have not been inspected. No secrets or setting values were printed,
no configuration files were edited, and no services were called or managed.

## Proposed implementation

Use explicit JSON property names on the affected Kotlin public DTOs, following
the existing ingestion controller pattern, rather than changing global Jackson
configuration or internal persistence serialization. Publish Python-compatible
snake-case workspace/session/chat fields and `doc_id` citations. Accept the
frontend's snake-case request fields; retain camelCase request aliases where
supported to avoid disrupting existing Kotlin callers. Explicitly bind the
session-list query as `workspace_id`.

Use the existing masked session-not-found exception for missing, foreign,
archived, or wrong-workspace chat continuation. Emit safe stream error envelopes
and proxy-friendly SSE headers. Keep existing service/repository boundaries,
shared schema, membership checks, and completed-answer persistence behavior.
No new dependency, bean wiring pattern, schema migration, or frontend/Python edit.

Rejected alternative: change the frontend to accept Kotlin-specific payloads.
That leaves backend contracts different behind a load balancer. A global naming
strategy also risks changing persistence and unrelated endpoints unnecessarily.

Rollback: revert the affected Kotlin DTO/controller/service changes and tests;
no data migration or data deletion. Previously incompatible response keys change
to the existing Python/frontend contract; document that correction for callers.

## Verification

Add offline HTTP contract tests for workspace discovery; session query binding,
summary/detail timestamps, rename/archive and errors; snake-case chat request
propagation with a nondefault workspace and existing session; new chat completion,
stream source/error shapes and headers; and masked invalid session continuation.
Include Spring MVC's actual configured JSON converter path to avoid testing only
an unrelated ObjectMapper. Use fake retrieval/providers/repositories; no live
provider or database writes. Run focused coverage, formatting and full offline
suite. Update the API contract and README with parallel-service prerequisites.

Runtime acceptance is separate: verify matching database/schema and identity
settings, serve both backends on different ports, compare authorized workspace
and session payloads, and create/continue a dedicated test chat across backends.
Obtain separate authorization before managing services or performing live
database/provider operations. Do not claim load-balancer acceptance from mocks.
Overlapping writes to the same chat and simultaneous ingestion are not proven
safe by sequential HTTP compatibility checks; inspect and report any such gaps.

Concrete live acceptance, explicitly approved and executed:

1. Keep the existing user-managed service running. Identify its backend and port;
   launch the updated Kotlin service on a separate available port without
   changing persisted configuration or stopping an unrelated process.
2. Compare authorized workspace IDs/display names from both `/workspaces`
   responses and active session IDs through `workspace_id` queries. Session
   list/detail paths currently run retention cleanup; do not describe those
   requests as guaranteed database-read-only.
3. Create one labelled RDP-18 test chat through Kotlin; verify Python lists and
   loads its completed turns. Continue that ID through Python and verify Kotlin
   loads the continuation. This invokes embeddings/chat and writes test turns.
4. If a common proxy/load balancer is already configured, verify the same routes
   through it. Otherwise report direct-backend acceptance separately from
   untested load balancing. Browser acceptance requires the frontend to target
   the updated backend or that proxy.
5. Report exact results and leave any test-chat cleanup or user-managed process
   changes within the authorization given. Do not delete existing user chats.

## Live acceptance results

No existing Python/Kotlin API listener was present initially. Python was started
on 127.0.0.1:8000 and Kotlin on port 8001, using existing configurations and a
process-only server-port override. Initial sandbox PostgreSQL connections were
denied; approved network-capable retries started both successfully. Only the
Codex-created Kotlin instance was restarted after the retrieval reader fix.

Both `/workspaces` payloads were identical: one authorized `local` workspace.
Both backends listed the same 344 existing session IDs and loaded the same
existing session's two turns. One labelled `[RDP-18 compatibility test]` chat was
created through Kotlin, read through Python, continued through Python, and read
through Kotlin with four completed turns. Both streams returned HTTP 200,
grounded source metadata and the expected terminal events.

The unchanged frontend `api.ts` was then transpiled with Node's built-in
TypeScript transform and executed against both services, without editing the
Python/frontend checkout. Its workspace/session/detail calls passed, and its
actual SSE parser/continuation request passed against updated Kotlin with five
sources and nonempty document/chunk/path fields. Python then read all eight
turns in the same test chat. The intermediate adapter attempt completed a turn
but its additional citation-ID assertion exposed the reader bug, which was fixed
and retested. Only one test chat was created; it remains active and both backends
now list 345 chats. No document ingestion, metadata backfill or schema change ran.

Evidence lives in ignored workspace files under `target/rdp18-runtime/`:
`live-evidence.json`, `frontend-adapter-evidence.json`, verification scripts,
process records and service logs. Python app PID 24608 and final Kotlin app PID
18740 were left running for user testing. No existing user-managed service was
stopped. Session list/detail endpoints include normal retention cleanup.

No frontend listener or common proxy/load balancer was available. Browser
rendering and actual load balancing were not tested. The current frontend Vite
proxy still targets localhost:8000 (Python); route it to 8001 for direct Kotlin
testing or to a common upstream for both services. Concurrent same-chat/document
writes and canonical ingestion metadata/backfill remain separate backlog work.
