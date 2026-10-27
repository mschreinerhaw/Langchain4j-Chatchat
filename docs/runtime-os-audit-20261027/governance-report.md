# Runtime OS architecture convergence — 2026-10-27

## Baseline and evidence boundary

At audit start, the running API was release `runtime-template-supply-20261027-r6`, SHA-256
`af96fe102dfaa8ac5b56e7ea5020fac415c45993c4c976b4e03687809418cef5`.
Its recorded source is `bb5bf19774e124989dc5f54c95fa97006f3cd407` plus
`deployed-source.patch` (SHA-256 `071aa51875519eff1db014df60e41e770a4ca02bab60aef88d12bf58063df63c`).
This is a patched deployment, not a clean Git commit. The patch has been applied
successfully in the separate `runtime-governance-baseline-20261027` worktree.
Local HEAD `04a833148` has a different aggregate diff. Per-file comparison confirms
all 17 changed production source files match after CRLF normalization. Existing uncommitted edits are candidate changes,
never evidence of deployed behavior. See `deployed-baseline.json`.

## Actual execution chain

`AgentChatModeHandler` / task API -> `AgentOrchestrationEngine` ->
`PublishedDataAcquisitionPlanner.compile` or generic model plan ->
`InterpretationPlanRuntime` -> authorized asset discovery (OpenSearch) ->
published/bound template discovery -> model `selectAndBind` ->
`SemanticCandidateAdmissionPolicy` and `TemplateInvocationBridge` ->
protocol workflow plugin -> MCP gateway -> actual template execution ->
`AnalysisEvidenceCoordinator` -> `AnalysisCoverageCoordinator` ->
`CodingAnalysisService` -> immutable input snapshot / Parquet / Schema / Manifest ->
`ClineCodingAdapter` -> native Cline SDK -> `python_execute` ->
`ClinePythonCallbackController` -> `CodingPythonExecutionBridge` -> MCP
`PythonManagedExecutionService` -> Docker -> `CodingEvidenceCollector` ->
authorized `artifact_read` -> Cline report completion -> final publication.

Fixed publication bindings intentionally need not rediscover assets through
global OpenSearch. A full OpenSearch acceptance case must use a discovery task,
not claim that a fixed-binding replay tested this branch.

## Confirmed architectural defects

| ID | Evidence at audit start | Defect and consequence | Minimal remedy |
|---|---|---|---|
| A01 | `coding-agent-node/src/runner.js:221`, `ClinePythonCallbackController:175` | Diagnostic Python and final report delivery share a final-output gate. A successful print-only program is treated as missing output, while its stdout is unavailable to the model. | Make the existing final-output contract explicit in the initial task and unify technical repair feedback. Do not certify a diagnostic process as a completed analysis. An exploration delivery mode needs a separate bounded, authorized contract before introduction. |
| A02 | `ClinePythonCallbackController:191,211`, `sdk-adapter.js:12`, `code-repair.js:121` | Java asks for a complete replacement program while the native path requires exact patches. Tool schema admits contradictory proposals. | Express mutually exclusive source/repair/rollback proposals; keep parent-version/hash checks; return one patch-compatible instruction. |
| A03 | `AnalysisCoverageCoordinator:84,100` | Failed data acquisition becomes a synthetic status dataset and launches Coding with no business data. | Return a typed supply outcome before Coding when required supply failed. Preserve verified empty datasets. No substitute template execution. |
| A04 | `SchemaDrivenTemplateParameterBinder:253,320`, `TemplateInvocationBridge:449` | Literal containment is used as universal parameter truth. It rejects single characters, arrays and normalized dates. | Distinguish model interpretation of user input from exact tool-result provenance. Validate input quote, schema, IDs and authorization; Java cannot prove semantic equivalence by substring. |
| A05 | `CodingAnalysisService:378`, `AnalysisCoverageCoordinator:266` | Process, artifact, SDK session and parent task status are translated inconsistently; unresolved execution can surface as generic analysis failure. | Preserve operation identity and explicit reconciliation; never replay an unknown operation. |
| A06 | `CodingFinancialResultVerifier:38,63`, `ComputedResultBoundary:35` | Generic infrastructure contains domain formulas and a universal source-copy notion of analytical value. | Keep schema/integrity checks. Verify legacy validation-plan references before moving domain-specific rules; make permitted output explicit in task contract. |
| A07 | selection context in Engine; binder status schema | Pagination completeness is not represented in the selection decision. | Add a real later-page regression before changing retrieval. Do not infer catalog-wide no-match from one page. |

The primary template route already returns directly to unified model selection.
Old coverage-audit helpers in HEAD are not proof of an additional live model call.
`SemanticCandidateAdmissionPolicy`, `LocalToolResultReviewer` and
`MandatoryWorkflowResultReviewer` include deterministic checks that must survive.

## Component decision register

Inventory JSON is a lexical index with references, not proof of reachability or
permission to delete Spring/SPI components. Unclassified components remain
`REVIEW_REQUIRED`. Runtime, API, MCP, Node and frontend state projections must all
be inspected before claiming total coverage.

| Component / group | Caller and responsibility | Decision / replacement | Impact and automated verification |
|---|---|---|---|
| PublishedDataAcquisitionPlanner | Engine; compile publisher-owned bindings | Keep; explain non-applicable compile result | Fixed binding must not reselect asset; unsupported publication retains fallback |
| SchemaDrivenTemplateParameterBinder | Engine; model template selection and parameter inference | Keep one decision; remove obsolete bind-only path | Multi-template, illegal ID, missing parameter, normalization tests |
| SemanticCandidateAdmissionPolicy | candidate evaluators; candidate-set membership | Keep; never Top1 or singleton admission | No-match, missing selection and unauthorized ID tests |
| MandatoryWorkflowResultReviewer / LocalToolResultReviewer | workflow and plan runtime; deterministic protocol facts | Keep checks; no independent semantic veto | QA and fixed workflow regression |
| AnalysisEvidenceCoordinator / AnalysisCoverageCoordinator | Session; projection then dispatch/result handling | Merge projection and supply accounting into existing evidence owner | Snapshot/empty-source/failed-supply tests; no Coding on failed required supply |
| MCP result analysis adapters | result bridge; normalize protocol envelopes | Merge repeated interpretation; retain provenance | Same status, row count and handle after adaptation |
| TemplateWorkflowPluginRegistry and API/SQL/Python/SSH/HTTP adapters | plan runtime; execute declared protocol | Keep deterministic registry and protocol adapters | Real protocol tests; ambiguous plugin resolution rejects |
| ClineCodingAdapter / DurableCodingTaskService | Coding service; leases, CAS, durable dispatch and recovery | Keep; these are not another Coding reasoning loop | Restart, stale lease, idempotency and unknown operation tests |
| Node runner / SDK adapter / CodeHistory | native Cline SDK; tools and immutable code versions | Keep existing SDK loop; unify repair protocol | Real execution + exact patch repair; no duplicate Python after completion |
| CodingEvidenceCollector / schema verifier | callback; process stop proof, artifact/schema/provenance | Keep deterministic result authenticity | Tampered file/hash, missing output and invalid reference rejection |
| CodingFinancialResultVerifier | versioned validation plan | Pending historical-plan audit; move explicit domain policy out of generic default | Legacy plan retains original verifier until migration |
| ComputedResultBoundary | collector; copied-source heuristic | Pending task-contract redesign | Legitimate row extraction and prohibited bulk source reproduction |
| Model-assisted context/retrieval bridges | Session before discovery; parameter suggestion/query rewrite | Conditional merge/skip when already bound | Recall comparison, synonyms, pagination and model-call trace |
| QA critic / quality / legacy analysis coordinators | ordinary QA and old analysis paths | Retain live non-Coding consumers; do not blanket-delete | QA, fixed workflow and old task resume |
| PublishedDataSupplyReviewer | no current production caller; selected parameter-free templates | Delete candidate, already deleted in inherited working tree; model selection replaces it | No auto-admission of all parameter-free candidates; compile/reference checks |
| Old Engine coverage-audit / cross-asset / bind-only helpers | obsolete branch after current early template return | Delete only confirmed unreachable helpers; unified selection replaces them | Candidate selection and multi-asset tests; no loss of model coverage |
| AnalysisOutputAdmissionPolicy | no external production lexical reference found | Pending reflection/config check, then delete | Production reference/SPI inventory and QA regressions |

## Minimal execution and status ownership

Model: understand -> select real candidates + infer parameters -> Coding analysis.
Java: authorized retrieval -> ID/binding/Schema checks -> real MCP supply ->
immutable files -> existing durable Coding submission -> verified artifact ->
publication. No second semantic veto and no Java business substitute.

Supply status is distinct from process status and report availability. A failed
selection is not an MCP execution failure. A verified zero-row dataset is an
empty successful delivery. An unknown operation remains reconcilable under its
original identity and cannot be replayed to improve apparent success rate.

## Stages and rollback

1. Freeze deployed commit+patch and artifact hashes; retain inherited diff.
2. Correct decision/parameter and source/repair contracts; stop Coding dispatch
   after failed required supply. Run focused selection, schema, workflow and SDK tests.
3. Remove only proven dead review/binding code and merge duplicate projection.
   Keep QA, permissions, budgets, leases and evidence validation intact.
4. Deploy only with no active task or Python executor, retaining old API and Node
   artifacts, configuration, image identifiers and hash manifest. Restore both
   versions together on failure; never rewrite historical evidence.
5. Run a real discovery -> OpenSearch -> model -> MCP -> native Cline -> Python ->
   verified report task. Persist all phase identities and queryable evidence.

## Real acceptance gates

Trace root task/run, plan step, candidate selection, tool execution, snapshot hash,
Coding task, model operation, Python operation, code hash, manifest hash,
validation reference and published report. Compare official task/Coding monitor
status to Node observations and MCP operation records. No generated-code event
alone proves execution. No process success alone proves output acceptance.

Required negative cases: no-match, missing fields, invalid ID, permission denial,
missing parameter, genuine empty result, protocol failure, stale patch, restart,
unknown-result reconciliation and cross-tenant Silo access. Real integration and
unit tests are reported separately; missing evidence fails acceptance.

The earlier QA probe is not a business-supply E2E pass. The actual business E2E
and negative runs are recorded in [regression-results.md](regression-results.md).
The updated Chinese audit and staged task register are in [README.md](README.md),
including the unresolved publication/asset binding and retry snapshot defects.
