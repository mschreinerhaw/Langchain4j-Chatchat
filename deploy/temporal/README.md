# Temporal development service

`chatchat-runtime-news` defaults to the JVM-local embedded Temporal test server and does not need
Docker for local execution. Its state is memory-only and is lost when the JVM stops.

Set `CHATCHAT_NEWS_TEMPORAL_SERVER_MODE=external` when durable workflow and schedule history is
required. This Compose file provides that external local development and integration-test service:

```powershell
docker compose -f deploy/temporal/docker-compose.yml up -d
$env:CHATCHAT_AGENT_RUNTIME_WORKFLOW_ENGINE = 'temporal'
$env:CHATCHAT_TEMPORAL_TARGET = '127.0.0.1:7233'
$env:CHATCHAT_RUNTIME_NEWS_TEMPORAL_ENABLED = 'true'
$env:CHATCHAT_RUNTIME_NEWS_TEMPORAL_SERVER_MODE = 'external'
$env:CHATCHAT_RUNTIME_NEWS_TEMPORAL_TARGET = '127.0.0.1:7233'
```

`chatchat-runtime-news` owns a separate `chatchat-news-collection` task queue. It reconciles each
configured news source into a durable Temporal Schedule; it does not share the Agent Runtime worker.
The checked-in six-field Spring cron values are converted to Temporal's minute-precision five-field
format, so the seconds field must be `0`.

Stop it without deleting history:

```powershell
docker compose -f deploy/temporal/docker-compose.yml down
```

Do not use this single-process service or its checked-in development password in production. Use
Temporal Cloud or the official Helm chart with external persistence, TLS/mTLS, authorization,
metrics, backups and a tested upgrade policy. Pin production images by digest.
