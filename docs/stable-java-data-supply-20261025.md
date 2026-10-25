# Java stable data acquisition and candidate review

> Paused at the user's request on 2026-10-25. Latest code `9877a9012` is committed
> but not deployed; its final package build was interrupted. Production remains
> `715e09f92`. Full Agent acquisition/report acceptance is still outstanding.
> See [the 2026-10-26 handoff](stable-java-data-supply-resume-20261026.md).

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

- Keep canonical asset identity already selected in the governed query IR. A unique
  literal reference to the full canonical name/display name in the original user
  request can also resolve the returned authorized identity deterministically.
  This requires the complete successful typed asset page and an enabled asset;
  duplicate identities/names, multiple references, truncated pages, short incidental
  labels, prefixes, fuzzy matches, and qualified/negative requests remain unresolved.
  A sole unnamed candidate is never enough. Existing permissions still govern execution.
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

Final targeted Agent/runtime tests: **294 passed**. Publisher/release tests: **15 passed**.
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
The complete repository test suite is not claimed green.

## Additional live planning regression

The first post-release real Agent task failed before acquisition: all three model
plan candidates omitted the bridge's asset stage. `BusinessCapabilityScopePass`
then removed the default template invocation of that bridge in favor of the fixed
publication, also losing the authoritative asset dependency. This is distinct from
the original semantic candidate rejection and must not be reported as a successful
end-to-end task.

Staged publisher expansion now runs before fixed-scope narrowing. The stage contract
is resolved from the same direct/nested publisher metadata and declared parameters
used elsewhere. The asset invocation remains upstream of the fixed publication;
only template authority is narrowed. No provider is selected using question
keywords, host names, or business identifiers. A regression exercises omitted stage,
nested MCP metadata, preserved asset -> publication -> execution dependencies,
template binding ownership, Runtime validation, and repeated optimization.
The focused planner/optimizer suite passes **27/27** tests.

The production-shaped workflow regression initially failed: configured generic
tool dependencies removed the selected publication's asset/template execution
edges, causing an unnecessary parent directory query to be materialized. That
intermediate package was withheld from deployment. Configured DAG repair now
preserves compatible, selected fixed-publication supply edges in addition to the
user's general dependencies. It does not preserve arbitrary model edges or choose
another publication. The regression now also covers the production dependency
shape and passes. No database migration is needed; release policy uses existing
versioned tool definitions.

## Production acquisition verification

The first managed rollout of source `f6643e6bf` succeeded with all seven application
services healthy. Its consistency backup verified 19,497 files; the hashes, images
and start times of 41 unrelated containers were preserved. Running API/MCP JAR
hashes and immutable image digests were checked, including absence of JAR bind
overrides. The old release remains available to the managed rollback flow.

An authenticated production Runtime API check independently followed the authorized
asset discovery -> new published template list -> SSH template execution chain.
All **eight** returned parameter-free templates executed successfully with exit
code zero and `dataCompleteness.complete=true`, returning **31,274 bytes** of real
command output. Raw output and authenticated snapshots remain private ignored
validation artifacts. This proves the Java supply interfaces work; it does not by
itself prove that an Agent report completed or that arbitrary questions are covered.

## Managed rollout before the final asset-review fix

- Deployed source: `715e09f92` on `fix/stable-java-data-acquisition`.
- Managed deployment task: `3167a9ff-8366-4449-9d40-953c93d8b90f`, `SUCCEEDED`.
- Package: `release-20261025-stable-supply-715e09f92`.
- Package SHA-256: `b353658fd690782cd5c26d76dae3094b3e70facb3d6fa5811673bde22370207a`.
- Backup: `stack-3167a9ff-8366-4449-9d40-953c93d8b90f`; **19,516** files verified,
  original data preserved. Previous managed releases remain available for rollback.
- All seven application services healthy. Actual running JAR hashes, immutable
  images, and the active deployment entrypoint verified after rollout. All **41**
  unrelated containers retained their IDs, images, and start times.
- The withheld `286793f07` package was not deployed and its package approval was
  removed before publishing the final package.
- API image: `localhost:18443/runtime-os/api@sha256:1fb1973876b2f1f11cf6cfdc6dd410e2a9ae9b52d1681e66a1eb5a446a1d0fdf`.
- MCP image: `localhost:18443/runtime-os/mcp@sha256:a8f4e23f4f0daab2b66d3178bec4e2157185cf0c8ea6f990fb5db3117f347432`.
- API JAR SHA-256: `665b3cdb15db3f82b6f69383fcaf1ecb17f82f4280dc5f53794e87cdc8865e0b`.
- MCP JAR SHA-256: `fc5854e7c3b1e117e74126bf3d0689073bfa857ed10b6a416c27107f6d72272f`.

Standard Maven packaging and both frontend builds passed. The last package reused
the unchanged API npm dependency installation, then rebuilt its frontend normally.
Final targeted regression: **309 tests passed**, no failures or skips.

The immediate post-rollout task `39916241-8689-32bf-b405-c92c02d9eec5`
ended `NO_PRESENTABLE_RESULT` during local registry warm-up. Local tools registered
while authorization was running, but the previously computed missing-required-tool
map was retained. Tool policy resolution now reconciles once when the registry
revision changes; it rechecks binding and authorization and does not add unbound tools.

After warm-up, task `6c2eb79c-72ed-32fe-a91f-6d532d231832` passed DAG validation,
but asset review timed out after **60,012 ms**. Its diagnostic correctly distinguished
`CANDIDATE_REVIEW_UNAVAILABLE` from semantic no-match, but acquisition had not
completed. These tasks are not counted as successful acquisition/report validation.

The reviewer now uses the existing protocol navigator to resolve the invocation's
discovery stage, rather than the bridge's static template-discovery role. Declared
canonical asset selections and exact user references can therefore take the asset
admission path for dual-stage publishers. Exact-reference admission has its own
audit authority; the Runtime evaluator still projects only returned identities.
