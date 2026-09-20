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
| Ingestion | `POST /ingest` accepts source path, recursive flag, optional workspace/profile, and dry run. Cover missing path, invalid profile, dry run purity, skipped/unchanged documents, and result shape. |
| Assets | `GET /workspaces/{workspaceId}/assets/{assetId}` requires membership; permits PNG/JPEG/GIF/WebP only after byte-signature validation; supports ETag/304 and private, inline, `nosniff` headers. |
| Model-profile warmer | Local-only endpoint honors workspace authorization and dry run; non-local environments reject it. |

The Kotlin implementation uses the current Python route modules as source: `api/routers/health.py`, `chat.py`, `ingest.py`, `workspaces.py`, `assets.py`, and `admin.py`. The older `api/routers/router.py` is a reduced duplicate and is not contract authority.

## Exact behavioral evidence

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
