# Local observability

Start the stack after the Kotlin service is available at `http://localhost:8080`:

```powershell
docker compose -f docker/docker-compose-observability.yml up -d
```

The service sends OTLP metrics and traces to `http://localhost:4318` by default. Prometheus scrapes `http://host.docker.internal:8080/actuator/prometheus` every 10 seconds.

| Service | URL |
| --- | --- |
| Grafana | `http://localhost:3000` (`admin` / `admin`) |
| Prometheus | `http://localhost:9090` |
| Tempo | `http://localhost:3200` |
| Collector diagnostics | `http://localhost:8888/metrics` |

Set `OTEL_METRICS_ENABLED=false` to disable OTLP metric export. The Prometheus endpoint remains available. `LANGFUSE_ENABLED` defaults to `false` and no Langfuse service is part of this stack.
