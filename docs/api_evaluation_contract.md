# API and evaluation evidence

**Source revision:** `RAG-dev-plane` `92a594e8e3bec694b1a893563f63d8a220605dcd`

**Purpose:** define the Kotlin contract and evaluation evidence selected for RDP-01. It deliberately selects high-value behavior rather than requiring a line-by-line port of the Python test suite.

## API contract suite

| Area | Contract cases to implement in Kotlin |
| --- | --- |
| Health and readiness | `GET /health` returns `status=ok` and configured environment without a model request. `GET /readiness` reports store reachability and configured chat, embedding, and storage details. |
| Chat | `POST /chat` validates a nonblank question and optional `top_k` 1–20, workspace, chunking profile, model profile, session ID, and debug flag. Cover grounded answer/citation output, ungrounded answer, session creation, and provider failure. |
| Chat stream | `POST /chat/stream` emits `answer` events with verbatim JSON `delta` whitespace, one `meta`, then one terminal `done`. Cover post-start error, cancellation, and no retry after disconnect. |
| Safe errors | Validation gives the safe 422 envelope. Provider failure gives the safe 502 envelope and never exposes cause text. Cover authorization, missing resource, and unsupported asset media with their mapped errors. |
| Sessions | List by authorized workspace; read, rename, and archive owned active sessions. A foreign, missing, or archived session has the same masked 404 outcome. |
| Workspaces and identity | Local principal is server-derived. Gateway mode requires trusted identity. Workspace membership is checked for every workspace-scoped operation. |
| Ingestion | `POST /ingest` accepts snake-case `source_path`, `recursive`, `workspace_id`, `chunking_profile`, `model_profile`, and `dry_run`; it returns total `indexed` and per-document ID/path/chunk/skip/asset fields. Workspace membership is checked before path admission, and configured allowed roots bound the source. Cover invalid/outside paths, invalid profile syntax, dry run purity, deterministic recursive scans, selected model storage target, unchanged documents, and result shape. |
| Assets | `GET /workspaces/{workspaceId}/assets/{assetId}` requires membership; permits PNG/JPEG/GIF/WebP only after byte-signature validation; supports ETag/304 and private, inline, `nosniff` headers. |
| Model-profile warmer | Local-only endpoint honors workspace authorization and dry run; non-local environments reject it. |

The Kotlin implementation uses the current Python route modules as source: `api/routers/health.py`, `chat.py`, `ingest.py`, `workspaces.py`, `assets.py`, and `admin.py`. The older `api/routers/router.py` is a reduced duplicate and is not contract authority.

## Exact behavioral evidence

### Frontend wire compatibility (RDP-18)

The current local Python frontend and backend sources were compared on
2026-10-02. Public workspace/session/chat JSON uses snake-case keys; internal
Kotlin property names and persistence serialization remain unchanged.

- `GET /workspaces`: `principal.display_name`; workspace entries contain
  `workspace_id`, `display_name`, and `role`.
- `GET /chat/sessions?workspace_id=...`: bare array with `session_id`,
  `workspace_id`, `title`, `last_preview`, and RFC 3339 `updated_at` strings.
  Details add `summary` and chronological `turns` with `role`, `content`,
  and RFC 3339 `created_at` strings.
- Chat requests accept `question`, `top_k`, `include_debug`, `workspace_id`,
  `session_id`, `chunking_profile`, and `model_profile`. Legacy camelCase chat
  input aliases remain supported. Omit `session_id` for a new chat and reuse
  the returned ID for continuation. New chats persist after successful answer
  completion; cancellation/provider failure does not persist partial answers.
- Completed responses and SSE metadata use `session_id`; citations contain
  `doc_id`, `chunk_id`, `source_path`, title/page/section/score/snippet. The current
  chat implementation does not attach source asset references; the frontend
  treats omitted `assets` as empty. Asset enrichment is separate follow-up scope.
  Retrieval reads Python's metadata `doc_id` with a fallback to existing Kotlin
  metadata `document_id`, preserving citation identity across both row formats.
- SSE has ordered `answer`, terminal `meta` or `error`, then `done` events.
  Post-start errors use `code=stream_interrupted` and a safe `message`, followed
  by `done.reason=error`. Successful completion uses `done.reason=completed`.
  Responses supply `Cache-Control: no-cache` and `X-Accel-Buffering: no`.
- Missing, foreign, archived, and wrong-workspace continuation IDs have the
  masked 404 `resource_not_found` envelope before streaming begins. Missing
  required query parameters and malformed JSON use safe 422 validation errors.

`FrontendCompatibilityTest` exercises Boot's actual MVC/JSON configuration with
real controllers/services and offline repository/provider boundaries. Seeded
Python-shaped records prove contract handling, not live Python-to-Kotlin HTTP
readback. Parallel deployment prerequisites and remaining runtime acceptance
are described in the README and [RDP-18 design](rdp18_design.md).

- Grounded generation uses numbered context passages and the source system prompt.
- Direct chat-service no-context behavior is `I don't know based on the indexed documents.`
- The API unit test also injects a mock with `I don't have enough information in the indexed documents to answer this question.` This is a fixture-specific value, not evidence of the concrete chat-service result. Kotlin should preserve the actual service behavior and separately test the response shape for injected responses.
- Source references include document/chunk ID, source path, title, page, section, score, snippet, and related asset metadata. Browser-safe image assets receive the authorized relative content URL.

## Golden-case evaluation

| Dataset | Cases | Required Kotlin behavior |
| --- | ---: | --- |
| [golden-cases.jsonl](../evaluation/golden-cases.jsonl) | 19 | Load UTF-8 JSONL; reject malformed/empty/duplicate entries; preserve ID, question, expected facts, source hints, workspace, and chunking profile. |
| [golden-cases-heb.jsonl](../evaluation/golden-cases-heb.jsonl) | 13 | Apply the same UTF-8 handling and result format to Hebrew questions/facts/source hints. |

The Kotlin evaluation runner performs each retrieval twice and records deterministic chunk-ID ordering, source-hint precision/recall/MRR, expected-fact coverage, faithfulness proxy, latency, configuration, and failure stage. It writes a portable JSON report for comparison across runs. Live model/database evaluation remains opt-in.

## Selected source tests and fixtures

| Source evidence | Kotlin test family |
| --- | --- |
| `tests/test_api.py` | Health, request validation, safe upstream error, chat response shapes, dry-run warmer. |
| `tests/test_chat_stream.py` | Named SSE events, whitespace preservation, meta-before-done order. |
| `tests/test_workspace_authorization.py`, `tests/test_services.py` | Principal/workspace authorization and session scope. |
| `tests/test_assets.py` | Asset authorization, signature validation, ETag, and safe media handling. |
| `tests/test_evaluation.py` | JSONL validation, deterministic metrics, report format, API adapter. |
| `evaluation/*.jsonl` | Retrieval and generated-answer quality regression coverage. |

RDP-06 through RDP-09 add the document/ingestion fixtures. RDP-11 through RDP-14 add model, retrieval, streaming, telemetry, resilience, and storage evidence.

## PPTX ingestion (RDP-16)

The existing POST /ingest route and supported-extension reporting include .pptx. Request and response schemas remain unchanged. Dry runs include slide-local chunks and embedded image counts without provider/database/asset writes. Slide numbers populate citation page numbers; hidden slides and existing speaker notes are included. Embedded PNG/JPEG/GIF pictures use private assets; external pictures are never fetched. Unsafe or unsupported presentation packages use the existing safe invalid-request handling.
