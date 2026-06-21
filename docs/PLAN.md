# RAG_Embabel-AI — Project Plan

This document is the canonical execution plan. See the repository root `README.md` for quick start.

> Generated from the Phase 0–8 spec. Do not edit versions ad hoc; return to Task 0.1 for any artifact resolution issue.

## Dependency Version Reference

| Artifact | Version | Notes |
|---|---|---|
| Spring Boot | 3.3.5 | BOM parent |
| Spring AI | 1.0.0 | GA, use BOM |
| Embabel agent-rag | 0.4.0 | Resolved in Task 0.1 |
| Kotlin | 2.0.21 | kotlin-maven-plugin |
| JVM target | 21 | LTS, required by Spring Boot 3.3+ |
| Qdrant Docker | latest | gRPC port 6334 |
| MockK | 1.13.12 | Kotlin-idiomatic mocking |

## Task Checklist

- [x] Task 0.1 — Resolve Embabel (done)
- [x] Task 0.2 — Verify environment (done)
- [ ] Task 1.1 — Bootstrap project (`mvn -q clean compile` exit 0)
- [ ] Task 2.1 — Infrastructure up (`curl http://localhost:6333/healthz` → 200)
- [ ] Task 3.1 — PdfIngestionService
- [ ] Task 3.2 — IngestionController + sample PDF
- [ ] Task 4.1 — Pipeline baseline endpoint
- [ ] Task 5.1 — SpringAiVectorSearchAdapter ← **highest risk — stop on interface mismatch**
- [ ] Task 5.2 — RagConfig + agentic endpoint
- [ ] Task 6.1 — Hybrid Lucene BM25 search
- [ ] Task 7.1 — Observability (ResultsListener)
- [ ] Task 8.1 — Unit tests
- [ ] Task 8.2 — Integration test (TestContainers)

## Stop Conditions

1. Spring AI / Embabel artifact unresolved → return to Task 0.1
2. Sample PDF missing → stop Task 3.2
3. Embabel `VectorSearch` interface name/signature differs from spec 3.4 → stop Task 5.1 and report

## Python → Kotlin Mapping

| Python (LangChain) | Kotlin (Spring AI) |
|---|---|
| `PyPDFLoader(split_pages=True)` | `PagePdfDocumentReader(pagesPerDocument=1)` |
| `RecursiveCharacterTextSplitter(512, 80)` | `TokenTextSplitter(512, 80, 5, 10000, true)` |
| `Chroma.from_documents(...)` | `vectorStore.add(chunks)` |
| `LCEL chain` | `POST /api/rag/query/pipeline` |
| `Agent with tools` | `POST /api/rag/query` (ToolishRag) |
