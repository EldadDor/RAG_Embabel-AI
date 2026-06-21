# Copilot Instructions — RAG_Embabel-AI

## Project Context

This is an **agentic RAG proof-of-concept** using Spring AI + Embabel, mirroring a Python LangChain/Chroma pipeline in Kotlin.

## Critical Rules

1. **Do NOT use deprecated `RagService`**. The correct class is `ToolishRag`.
   - Grep check: `grep -r 'RagService' src/` must return nothing.
2. **Do not edit dependency versions ad hoc**. If an artifact fails to resolve, return to Task 0.1.
3. **Task 5.1 is the highest-risk task**. Before implementing `SpringAiVectorSearchAdapter`, verify all Embabel interface names/signatures against the resolved artifact. Any mismatch is a stop condition.
4. **Stop conditions** — report and pause, do not guess:
   - Embabel artifact unresolved
   - Sample PDF missing (Task 3.2)
   - `VectorSearch` interface mismatch (Task 5.1)
   - `ToolishRag` constructor params differ (Task 5.2)

## Dependency Versions (do not modify without Task 0.1)

| Artifact | Version |
|---|---|
| Spring Boot | 3.3.5 |
| Spring AI | 1.0.0 GA |
| Embabel agent-rag | 0.4.0 |
| Kotlin | 2.0.21 |
| JVM target | 21 |
| MockK | 1.13.12 |

## Python → Kotlin Mapping

| Python (LangChain/Chroma) | Kotlin (Spring AI + Embabel) |
|---|---|
| `PyPDFLoader(split_pages=True)` | `PagePdfDocumentReader(pagesPerDocument=1)` |
| `RecursiveCharacterTextSplitter(512, 80)` | `TokenTextSplitter(512, 80, 5, 10000, true)` |
| `Chroma.from_documents(...)` | `vectorStore.add(chunks)` |
| LCEL chain | `POST /api/rag/query/pipeline` |
| Agent with tools | `POST /api/rag/query` (ToolishRag) |

## SpringAiVectorSearchAdapter — Mandatory Field Mapping

| Embabel `Chunk` field | Source |
|---|---|
| `text` | `document.content` |
| `urtext` | raw original `document.content` |
| `parentId` | `document.metadata["source"]` |
| `pathFromRoot` | `[source] → [page_number] → chunkId` |
| `metadata` | `document.metadata` (verbatim) |

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/ingest?path=...` | Ingest PDF from classpath |
| POST | `/api/rag/query` | Agentic query (ToolishRag) |
| POST | `/api/rag/query/pipeline` | Fixed pipeline baseline |

## Hypotheses to Validate (record in docs/architecture.md)

- H1: Agentic RAG answers multi-hop questions in one session
- H2: `ResultExpander.ZOOM_OUT` recovers context lost by chunking
- H3: Hybrid (vector+BM25) beats pure vector on config/code content
- H4: `urtext` vs `text` matters for citations
- H5: Multi-tenant `PropertyFilter` correctly scopes searches

## Definition of Done

- [ ] `mvn clean verify` green
- [ ] Both endpoints HTTP 200 on same question
- [ ] `ResultsListener` logs ≥1 event per agentic query
- [ ] All 5 hypotheses recorded in `docs/architecture.md`
- [ ] No deprecated `RagService` usage (`grep -r 'RagService' src/` returns nothing)
