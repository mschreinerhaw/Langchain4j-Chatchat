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

The deployed reply limit is 32,768 tokens, with at most two response repairs and a 180-second
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
