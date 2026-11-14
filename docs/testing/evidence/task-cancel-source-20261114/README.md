# SQL Studio task cancellation, 2026-11-14

Source deployment: `146a3a3db969d2a442b7a0dda0fed416c9f9100f`, built on
`192.168.195.221` and served through port `18100`.

## Changes

- Restored SQL Coding conversations use the SQL Coding cancel endpoint even when
  their prepared workspace metadata is absent. Project ownership checks still
  apply on that endpoint.
- `EXECUTION_FAILED` is accepted as a durable stop reason, matching the existing
  failure-stop producer. Previously it could throw before stop reconciliation.
- Authorized Studio status polling reconciles pending stops as well as expired
  deadlines. Cancellation also invokes existing recovery. Generic adapter reads
  and completed-history reads remain read-only.
- Recovery recognizes both knowledge-client and SQL Workspace immutable stop
  receipts. Previously a stopped metadata operation could remain unresolved
  because recovery searched only the knowledge receipt namespace.

Recovery still requires a missing/stopped/ended worker and independently verified
operation stop receipts. An unavailable executor or missing proof remains pending;
no journal edits, invented receipts, duplicate dispatch, or repeated SQL execution
are used. Unknown usage retains its reservation instead of being reported as zero
actual usage. Java Skills configuration and the requirement classifier, planner,
and attachment parser are unchanged by this fix.

## Verification evidence

`build.json` records the server source archive, image digests, and test results.
`live-cancel.json` records a new task cancelled after a committed Coding operation
start. `historical-stops.json` records the two original stuck tasks after normal
Studio polling. `browser.json` records the authenticated SQL Studio terminal view.

The source archive excludes precompiled application JAR/class files. Java and both
frontends were compiled on the server; the frontend test/build stage may reuse its
matching Docker cache. The public gateway, API, Node, recovery, and executor images
were updated together. Existing upstream Native Studio Core was retained.

Tests include failure-stop validation, restored-conversation cancel routing,
poll-driven recovery, read-only adapter behavior, workspace receipt fallback,
missing proofs, unknown model operations, and live/unavailable worker scenarios.
These targeted regressions do not establish that every unrelated application
feature or external model/data service is healthy.

## Results

- Java: 147 tests passed, zero failures/errors/skips; recovery suite: 24 cases.
- SQL Studio frontend: 12 tests passed. Final local rerun passed; the identical
  successful server frontend test/build stage was cached.
- Node: 117 passed, 2 skipped, zero failures (119 discovered).
- Active-task API cancel: CANCELLED in 2,363 ms, executorStopped=true,
  reconciliationRequired=false; terminal state remained stable after 5 seconds.
- Historical `54104b0c...`: RUNNING -> CANCELLED, executor stop confirmed.
- Historical `d38a3887...`: RUNNING -> BUDGET_EXCEEDED, executor stop confirmed;
  its original budget-stop reason was preserved.
- Authenticated Edge replay clicked the actual Stop button and observed the SQL
  Coding cancel endpoint return HTTP 200. CANCELLED became visible in 3,337 ms;
  executor stop was confirmed, the Stop button disappeared, and no page errors
  were recorded. See `browser.json` and `browser.png`.
