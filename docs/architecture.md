# Architecture Decisions & Hypothesis Log

## Architecture Decisions

### AD-1: Maven over Gradle
Embabel parent project itself uses Maven with `kotlin-maven-plugin`. Chosen for consistency and predictable dependency resolution.

### AD-2: Qdrant over Chroma
Qdrant supports both REST (port 6333) and gRPC (port 6334). Spring AI uses gRPC. Mirrors production-grade vector store deployment.

### AD-3: Two query endpoints
A fixed-pipeline endpoint (`/api/rag/query/pipeline`) mirrors the Python LCEL chain exactly. The agentic endpoint (`/api/rag/query`) uses Embabel's `ToolishRag`. Having both allows direct comparison.

### AD-4: Embabel adapter pattern
`SpringAiVectorSearchAdapter` bridges Spring AI `VectorStore` to Embabel `SearchOperations`. Field mapping is explicit and documented in spec 3.4. This is the highest-risk component — any interface mismatch is a stop condition.

---

## Hypothesis Log

| ID | Hypothesis | Status | Result / Notes |
|---|---|---|---|
| H1 | Agentic RAG answers multi-hop infra questions in one session | ⬜ Pending | Test: "Where does Jenkins store artifacts and what auth does Qdrant use?" |
| H2 | `ResultExpander.ZOOM_OUT` recovers context lost by chunking | ⬜ Pending | Find a mid-sentence chunk; verify zoom-out restores the paragraph |
| H3 | Hybrid (vector+BM25) beats pure vector on config/code content | ⬜ Pending | Search exact config property names |
| H4 | `urtext` vs `text` matters for citations | ⬜ Pending | Normalize text; verify citations use original `urtext` |
| H5 | Multi-tenant `PropertyFilter` correctly scopes searches | ⬜ Pending | Ingest 2 PDFs, filter to one, verify no cross-contamination |

---

## Status Legend
- ⬜ Pending
- ✅ Confirmed
- ❌ Refuted
- ⚠️ Partial / Conditional
