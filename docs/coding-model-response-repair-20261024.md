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

The deployed reply limit is 32,768 tokens, with at most two response repairs. Control callbacks
have a 300-second timeout; model callbacks follow the authorized task deadline. Total task
budgets and selected models are retained. No database,
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

- Source commit: `153fabcdfc039995f53c2e8db0bdf3430b4646a1`.
- API and worker image version: `2.4.16-model-deadline-20261024`.
- Deployed API JAR SHA-256: `2565652217e17656f546727fd12d15bcae784880c83387f1e7382062b900cdc4`.
- Java: 33 targeted tests passed; worker: 32 tests passed on production Node 24.21.0,
  including real SDK correction, bounded failure and checkpoint recovery tests.
- API readiness and worker health passed; authorization and template-binding fingerprints
  were unchanged. Reply cap, repair cap, callback timeout and unchanged total model budget
  were verified against the running containers.
- Explicitly selecting the page-published `qwen3.8-flash` through the official interaction
  endpoint returned `OK`, with a completed coding receipt and an available result. The
  provider identity and actual token usage were recorded in successful model receipts.
- Complex task retries re-authorize against the current published Agent. Their request
  model fields are retained, but the Agent's configured model takes precedence; at this
  verification the Agent was bound to `qwen3.8-max`. No Agent binding was changed for testing.

Private deployment backups and full acceptance evidence are retained on the server under
`/opt/chatchat-releases/model-response-repair-20261024/evidence/` and
`/opt/chatchat-releases/model-deadline-20261024/evidence/`.

## Long model-call correction

The complex acceptance exposed a second transport boundary: a coding reply exceeded the
worker's 300-second callback limit while the configured model request was still running.
Changing only a global timeout is insufficient because native fetch also imposes a header
timeout and the model callback initially renewed ownership for only five minutes.

Version `2.4.16-model-deadline-20261024` uses the authorized task deadline for model callbacks,
with a cancellable native HTTP request and a bounded response body. Control callbacks retain
their short timeout. Java renews the active model call's five-minute lease every minute;
cancellation, task deadline and fencing continue to prevent stale execution. Late provider
receipts remain archival evidence and cannot publish a result after ownership is lost.
No task budget or business model binding is changed.

The extended Java gate passed 33 tests. The production Node 24 worker gate passed 32 tests,
including a delayed model response beyond the control timeout, task-deadline abort and explicit
cancellation. Lease renewal was tested across cancellation and replacement ownership.
The renewal tests also run on a separate thread to verify the callback identity and grants
are restored on the scheduler thread.

## Final live acceptance and remaining model-service limit

- On the final deployed version, selecting the page-published `qwen3.8-flash` returned `OK`
  through the official interaction endpoint, with a completed coding receipt and usable result.
- Selecting `hy3` completed a separate, context-only Python calculation of the integer sum
  from 1 through 500. The result was `125250`; five real model calls succeeded and the worker
  recorded two Python programs. This synthetic computation is not the database diagnosis.
- Original-analysis retry `d31875e6-413f-4f95-ab23-728ca3046856` used the current published
  Agent binding, `qwen3.8-max`. Planning completed, but the first coding model request returned
  no receipt within the original twenty-minute tool budget. At 17:27:38 Shanghai time its
  deadline was reached, and the parent ended `FAILED`; the child records `DEADLINE_EXCEEDED`
  with reconciliation still required. No Python program ran for this attempt. It is not
  recorded as a successful full database analysis.
- A private JVM thread dump showed the active call waiting in `JdkHttpClient.execute` /
  `HttpClientImpl.send`, with another model executor thread idle. The lease renewed while
  waiting, including beyond the former five-minute limit. The remaining blocker is an
  unreturned upstream model HTTP request, rather than a model-catalog miss or thread-pool
  saturation. The Node callback goes directly to `http://api:8080`; the Node proxy's timeout
  belongs to the opposite, short-request direction and was not changed.

The current business Agent binding and all original budgets remain unchanged. A reviewable
proposal is prepared on the server to change only `db_ops_assistant.modelName` from
`qwen3.8-max` to the tested `qwen3.8-flash`, then publish through the existing licensed API.
This business configuration change is awaiting the user's explicit choice and has not been
applied. Its private before/after payloads preserve every other upsert field.
