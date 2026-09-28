# Local observability

Start the stack after the Kotlin service is available at `http://localhost:8080`:

```powershell
docker compose -f docker/docker-compose-observability.yml up -d
```

The service sends OTLP metrics and traces to `http://localhost:4318` by default. Prometheus scrapes `http://host.docker.internal:8080/actuator/prometheus` every 10 seconds.

| Service | URL |
| --- | --- |
| Grafana | `http://localhost:3300` (`admin` / `admin`) |
| Prometheus | `http://localhost:9090` |
| Tempo | `http://localhost:3200` |
| Collector diagnostics | `http://localhost:8888/metrics` |

Set `OTEL_METRICS_ENABLED=false` to disable OTLP metric export. The Prometheus endpoint remains available. `LANGFUSE_ENABLED` defaults to `false` and no Langfuse service is part of this stack.

Grafana provisions the **RAG Service** dashboard from `grafana/provisioning/dashboards/json/rag-service.json`. It shows HTTP throughput/status, chat and retrieval latency, operation outcomes, streaming first-token time, embedding cache hits, retrieval candidates, and ingestion counts. Panels remain empty until the corresponding operation runs; ingestion is not exposed as a normal runtime endpoint yet. Spring Boot supplies HTTP and connection-pool meters, while `rag_*` meters use fixed operation/outcome labels only. No prompts, paths, workspace/session IDs, or provider responses are included in metric labels or default span attributes.

`POST /chat/stream` emits SSE `answer` events containing verbatim provider deltas, followed by `meta` and `done`. A provider failure after streaming begins emits `error` and `done`. Client disconnect or timeout cancels the provider subscription and does not persist an incomplete answer. The normal `POST /chat` JSON API is unchanged.
