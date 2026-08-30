# Temporal development service

This Compose file is a local development and integration-test service. Start it with:

```powershell
docker compose -f deploy/temporal/docker-compose.yml up -d
$env:CHATCHAT_AGENT_RUNTIME_WORKFLOW_ENGINE = 'temporal'
$env:CHATCHAT_TEMPORAL_TARGET = '127.0.0.1:7233'
```

Stop it without deleting history:

```powershell
docker compose -f deploy/temporal/docker-compose.yml down
```

Do not use this single-process service or its checked-in development password in production. Use
Temporal Cloud or the official Helm chart with external persistence, TLS/mTLS, authorization,
metrics, backups and a tested upgrade policy. Pin production images by digest.
