# Coding model response and published model resolution repair

The failed analysis had completed authorized data retrieval. Its first coding model response
contained invalid tool arguments and used all 16,384 output tokens, including reasoning.
The response was rejected before any Python execution. Raw invalid arguments were not archived,
so truncation is supported by the exhausted output budget rather than a retained parse fragment.
The worker treated the rejection as fatal; its `MODEL_FAILED` event was also absent from the
durable event allowlist.

The Java callback now archives a bounded, versioned repair decision alongside actual provider
usage. A worker must negotiate support before receiving this decision. Unauthorized tools,
unknown usage, stale leases and legacy workers retain their rejection behavior. The real Cline
loop requests a corrected response without executing rejected arguments. Each attempt requires
normal budget admission and settlement; the repair counter survives checkpoints and is bounded.

The deployed reply limit is 32,768 tokens, with at most two response repairs and a 300-second
worker callback timeout. Total task budgets and selected models are retained. No database,
template, business question or model identifier is special-cased in the implementation.

A separate model selection failure came from `AgentOrchestrationEngine` constructing its own
file-only resolver. Production now injects the shared resolver backed by the published page
catalog. Published connection changes replace cached clients, including default-model changes.
Unpublished drafts do not become active and disabled published entries do not fall through to
file connections. Configuration properties remain the startup fallback when no published
catalog exists.

Deployment tooling checks the exact running API and worker baselines, replaces only the changed
classes/modules, verifies every other JAR entry, and builds from clean runtime bases. It backs
up the stage configuration and API database, checks idle execution and authorization fingerprints,
and restores both previous images/configurations if readiness or verification fails. Historical
task records are retained; live verification uses the official retry endpoint.

## Deployment verification

- Source commit: `f847b83a1d05b53413b8eb221e873745b87d897a`.
- API and worker image version: `2.4.15-model-response-repair-20261024`.
- Deployed API JAR SHA-256: `d675cb55bad85a6912a23c940efe7fb6a430d375986b3b9da371b8320d799b1d`.
- Java: 31 targeted tests passed; worker: 31 tests passed on production Node 24.21.0,
  including real SDK correction, bounded failure and checkpoint recovery tests.
- API readiness and worker health passed; authorization and template-binding fingerprints
  were unchanged. Reply cap, repair cap, callback timeout and unchanged total model budget
  were verified against the running containers.
- Live planning calls took up to 210 seconds with the current published Agent model. The
  worker callback timeout was consequently raised from the initially deployed 180 seconds
  to the existing supported ceiling of 300 seconds while the worker was idle. Task deadlines
  and total model budgets still apply.
- Explicitly selecting the page-published `qwen3.8-flash` through the official interaction
  endpoint returned `OK`, with a completed coding receipt and an available result. The
  provider identity and actual token usage were recorded in successful model receipts.
- Complex task retries re-authorize against the current published Agent. Their request
  model fields are retained, but the Agent's configured model takes precedence; at this
  verification the Agent was bound to `qwen3.8-max`. No Agent binding was changed for testing.

Private deployment backups and full acceptance evidence are retained on the server under
`/opt/chatchat-releases/model-response-repair-20261024/evidence/`.
