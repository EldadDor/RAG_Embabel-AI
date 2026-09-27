# RAG_Embabel-AI

Kotlin/Spring Boot service being built for backend parity with RAG-dev-plane. Document loading, chunking, private assets, JDBC interoperability, and an ingestion service are implemented. HTTP parity endpoints are still planned.

## Current local run

Use the `local` Spring profile, which is also the default in `application.yml`. Supply the environment variables needed by your local PostgreSQL and model services, then run `rtk proxy mvn spring-boot:run` with the workspace Maven environment described in [the repository instructions](.codex/AGENTS.md). The HTTP port defaults to `8000` (`API_PORT`). Check `http://localhost:8000/actuator/health`.

`local,legacy-poc` does not expose `POST /api/ingest`: the old PDF controller requires both `legacy-poc` and `vector-store-poc`. The RDP-09 ingestion service currently has no HTTP controller. Adding `vector-store-poc` selects the obsolete Spring AI `VectorStore` path, which has no active store bean in the current configuration. The old `/api/rag/query` routes have the same profile restriction. PPTX support is planned as a later task, RDP-16.

The historical proof-of-concept instructions below are retained for reference and do not describe the current runtime.

## Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21, JVM 21 |
| Framework | Spring Boot 3.3.x |
| AI Abstraction | Spring AI 1.0.0 GA |
| Agentic RAG | Embabel Agent RAG 0.4.0 |
| Vector Store | Qdrant (gRPC port 6334) |
| LLM (cloud) | OpenAI GPT-4o |
| LLM (local) | Ollama llama3 |
| Hybrid Search | Lucene BM25 (embabel-agent-rag-lucene) |
| Tests | MockK + TestContainers |

## Quick Start

```bash
# 1. Start infrastructure
docker-compose -f docker/docker-compose.yml up -d

# 2. Pull Ollama model (optional — for local LLM)
docker exec -it ollama ollama pull llama3

# 3. Configure
cp .env.example .env
# edit .env with your OPENAI_API_KEY

# 4. Build & run
mvn clean package -DskipTests
java -jar target/RAG_Embabel-AI-0.1.0-SNAPSHOT.jar

# 5. Ingest sample PDF
curl -s -X POST 'localhost:8080/api/ingest?path=sample-docs/infra-codebase.pdf'

# 6. Query — pipeline (LCEL-equivalent baseline)
curl -s -X POST localhost:8080/api/rag/query/pipeline \
  -H 'Content-Type: application/json' \
  -d '{"question": "What does the infrastructure codebase do?"}'

# 7. Query — agentic (ToolishRag)
curl -s -X POST localhost:8080/api/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question": "What does the infrastructure codebase do?"}'
```

## Project Structure

```
RAG_Embabel-AI/
├── .github/copilot-instructions.md
├── docs/
│   ├── PLAN.md
│   └── architecture.md
├── sample-docs/            ← add infra-codebase.pdf here (gitignored)
├── src/main/kotlin/com/dex/ragpoc/
│   ├── RagPocApplication.kt
│   ├── config/
│   ├── ingestion/
│   ├── rag/
│   ├── adapter/
│   └── model/
├── src/test/kotlin/com/dex/ragpoc/
├── docker/docker-compose.yml
├── pom.xml
├── .env.example
└── .gitignore
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/ingest?path=...` | Ingest a PDF from classpath |
| `POST` | `/api/rag/query` | Agentic query via ToolishRag |
| `POST` | `/api/rag/query/pipeline` | Fixed pipeline baseline (LCEL-equivalent) |

## Hypotheses

See `docs/architecture.md` for the hypothesis log (H1–H5).

## Definition of Done

- [ ] `mvn clean verify` green
- [ ] Both endpoints return HTTP 200 on same question
- [ ] `ResultsListener` logs ≥1 event per agentic query
- [ ] All 5 hypotheses recorded in `docs/architecture.md`
- [ ] No deprecated `RagService` usage
