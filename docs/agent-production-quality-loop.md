# Agent Production Quality Loop

## Contract

`agent_production_quality_v1` turns persisted Agent runs into a tenant-scoped production quality window. It is
available through:

```http
GET /api/v1/agent/runtime/quality?windowHours=24&sampleLimit=1000
```

The authenticated tenant always overrides a requested `tenantId`. Requests without either an authenticated tenant
or an explicit tenant are rejected. The aggregation service applies the same tenant check again before calculating
metrics.

## Metrics

- `claimAuditPassRate`: `PASS / (PASS + PARTIAL + FAIL)`. `NOT_APPLICABLE` conversations do not dilute this rate.
- `averageClaimCoverage`: average Claim Ledger coverage for applicable audits.
- `groundedRate`: grounded answers divided by runs with an evaluated grounding status.
- `toolSuccessRate`: successful tool traces divided by all tool traces.
- `completionRate`: completed terminal runs divided by all terminal runs.
- `averageLatencyMs` and `p95LatencyMs`: calculated from completed durable run timestamps.

The snapshot also contains deterministic failure categories, hourly or daily trend buckets, and the most recent
failed runs for drill-down. It does not invoke an LLM and therefore remains stable across repeated requests.

## Failure categories

- `CLAIM_AUDIT_FAILED`
- `CLAIM_COVERAGE_PARTIAL`
- `CRITICAL_CLAIM_UNBOUND`
- `UNKNOWN_EVIDENCE_REFERENCE`
- `GROUNDING_NEEDS_REVIEW`
- `TOOL_EXECUTION_FAILED`
- `EXECUTION_FAILED`
- `EXECUTION_CANCELLED`

## Feedback

The management view combines the production quality snapshot with the existing tenant-isolated Agent effect
analytics. This places user usefulness and adoption rates next to deterministic claim and grounding measurements,
without treating user preference as factual evidence.
