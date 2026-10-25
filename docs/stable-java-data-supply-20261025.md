# Java stable data acquisition and candidate review

## Observed cause

A server task asked for Docker images and container runtime state. The Agent required
a fixed query publication whose entire authorized set contained eight Kubernetes
templates. The model correctly rejected those candidates: Kubernetes events, nodes,
pods and rollouts do not provide Docker image/container facts. No command execution
occurred and no business dataset existed. The no-invented-evidence guard was correct.

The failure also exposed an availability problem: the preceding asset-review call
took 1,007,816 ms including transport retries. Template selection then consumed
48,300 ms plus a 21,222 ms coverage audit. A configured acquisition workflow still
depended on repeated model approval, and reviewer unavailability was indistinguishable
from a genuine semantic no-match in the final diagnostic.

## Responsibilities

- Keep canonical asset identity already selected in the governed query IR. Project it
  onto returned authorized candidates using the existing asset evaluator. A sole
  candidate without an explicit server selection still needs disambiguation; Java
  does not guess the target from a name or pick a different host.
- A fixed binding alone does **not** authorize baseline acquisition. The publisher
  must explicitly declare `mcp.data-supply.v1` in a released tool's page-managed input
  schema annotation. Unannotated publications keep semantic selection. This prevents
  a Kubernetes binding from becoming a Docker supplier by merely disabling review.
- For an opted-in publication, Runtime admits only returned identities with an
  explicitly parameter-free object schema. Schemas with business fields, required
  values, references, compositions or unsupported constraints are deferred. Java
  creates empty schema-binding protocols and uses the existing authorized batch
  compiler/executor; it never invents parameters or raw commands.
- The batch bound remains `ToolCallBatchSchema.DEFAULT_MAX_CALLS`; deferred identities
  and evidence limitations remain in the audit. Baseline admission is recorded as
  `RUNTIME_PUBLISHED_DATA_SUPPLY`, with analytical coverage `NOT_EVALUATED`. The
  conservative scope-insufficient opinion does not claim the baseline answers the
  complete question. Analysis owns relevance, sufficiency and additional acquisition.
- Discovery metadata remains outside business datasets. Actual executor results are
  still required. Resource scope, confirmation, template-binding evidence, server-side
  authorization and the final evidence guard are unchanged.

## Page-managed opt-in

In a deliberately scoped tool version, add this annotation at the root of its input
JSON Schema, then validate, test and publish through the existing page workflow:

```json
"x-runtime-data-supply": {
  "contractVersion": "mcp.data-supply.v1",
  "mode": "FIXED_PARAMETER_FREE_BASELINE",
  "selectionAuthority": "PUBLISHER",
  "analyticalCoverage": "NOT_EVALUATED"
}
```

The release catalog exports the supported annotation as tool control-plane metadata.
Unknown versions do not enable it. User request arguments and response-only assertions
cannot grant this policy. Existing output schemas and response shapes are retained;
old clients keep their original behavior. API and MCP must both be updated, and the
registered MCP catalog refreshed, before the new policy is effective.

An independent Docker baseline publication was created from the role's already
authorized parameter-free runtime/image/container/resource/storage templates. Its
contract test passed before publication. The server Agent now includes this capability,
retains its prior tools, and no longer requires the unrelated fixed provider for every
question. Tool choice can depend on user intent; Java executes the chosen declared
supply chain. No Docker names, template identities, host addresses, role identifiers
or question keywords were added to Java selection logic. Prior configuration is kept
in private validation artifacts for restoration.

## Deadlines and diagnostics

`chatchat.agent-runtime.tool-result-review-timeout-ms` defaults to 60,000 ms. A
caller-scoped budget is shared by semantic review, coverage and parameter binding and
composes with the existing request deadline. It uses the existing deadline executor;
there is no nested worker-pool wrapper. The scope is restored after success/failure.
Cancellation requests transport interruption, but socket termination still depends on
the underlying client. A phase timeout is distinct from user/global cancellation.

- `CANDIDATE_REVIEW_UNAVAILABLE`: review did not complete; this is not a no-match.
- `MCP_CAPABILITY_SCOPE_INSUFFICIENT`: semantic review found no suitable template in
  the authorized publication. Fix the publication/Agent scope instead of executing
  unrelated templates or pretending the database is unavailable.
- Actual empty/failed tool results still fail their existing fact/transport checks.

## Verification

Targeted Agent/runtime tests: **267 passed**. Publisher/release tests: **15 passed**.
These include zero model calls for declared baseline collection, preservation of
canonical asset selection, no automatic unique-asset guessing, required/unknown
parameter deferral, opt-in and unknown-version handling, deadlines for text/native
calls, and restoration of the caller scope. A Runtime workflow test verifies eight
sources become eight authorized execution calls, each retaining binding evidence.

An expanded regression run encountered three failures in
`CommandTemplateDiscoveryDatabaseQueryTest`. An isolated checkout of the unchanged
baseline `1f7bdadd3` reproduces the same **3/8** failures:

- `returnsRuntimeManagedFinancialTemplateWithoutExternalDatasourceRegistration`
- `businessCategoryRanksDatabaseTemplatesWithoutSuppressingCrossCategoryCandidates`
- `databaseQuerySearchFallsBackToRelevantRegistryEntriesWhenSearchIndexReturnsNoHits`

These pre-existing environment-projection/category-recall expectations remain open.
The complete repository test suite is not claimed green. Build, rollout and live
acquisition evidence are recorded only after they have actually completed.
