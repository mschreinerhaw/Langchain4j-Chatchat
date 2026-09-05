# Runtime OS Workflow Execution Architecture

Analytical workflow design and migration priorities are defined in
[Runtime OS 分析流程设计决策](runtime-os-analytical-flow-design.md). It separates deterministic
Flow control from model judgment and preserves this document's execution-truth guarantees.

## 1. Objective

ChatChat Runtime OS must make MCP workflow execution deterministic, recoverable, auditable, and
safe for model-assisted analysis. The model may classify intent, select among admissible semantic
candidates, explain evidence, and summarize results. It must not decide whether a transport call
really ran, whether a payload is complete, whether a permission check passed, or whether missing
data means an empty business result.

The primary commercial invariant is:

> Every returned business conclusion must be traceable to a committed execution result. Every
> missing conclusion must have a machine-readable reason that distinguishes empty data, incomplete
> execution, authorization denial, invalid input, timeout, cancellation, and infrastructure failure.

## 2. Runtime OS ownership

| Subsystem | Owns | Must not own |
| --- | --- | --- |
| Kernel | ABI, scope, protocol compatibility, health | business planning |
| Planning | candidate plan generation and protocol parsing | execution truth |
| Validation | DAG, schema, policy and dependency validation | transport calls |
| Scheduling | Ready-node calculation, branch barriers, fairness | semantic fabrication |
| Invocation | canonical MCP argument compilation and authorization | final analysis |
| Execution | retries, timeout, idempotency, batch isolation | interpreting empty data |
| State | events, attempts, checkpoints, resume tokens | model prompting |
| Evidence | lossless result registration and provenance | transport retries |
| Governance | tenant, user, role, token, budget and audit decisions | answer prose |
| Analysis | deterministic projection and governed model analysis | mutating evidence |
| Answer | evidence-bound synthesis, review and citations | changing workflow state |
| Composition | lifecycle coordination and port wiring | feature algorithms |

## 3. Canonical node lifecycle

Every workflow node follows one state machine. A state transition is persisted before downstream
nodes may consume it.

```text
PLANNED
  -> READY
  -> RUNNING
      -> SUCCEEDED
      -> EMPTY
      -> PARTIAL
      -> BLOCKED
      -> FAILED
      -> TIMED_OUT
      -> CANCELLED

PLANNED / READY
  -> NOT_EXECUTED
```

Terminal meanings are not interchangeable:

- `SUCCEEDED`: invocation completed and the declared result contract is satisfied.
- `EMPTY`: invocation completed successfully and authoritatively returned zero business records.
- `PARTIAL`: some committed evidence exists, but pagination, child calls, dimensions, or result
  contracts remain incomplete.
- `BLOCKED`: Runtime OS intentionally prevented execution because of authorization, confirmation,
  policy, dependency, schema, or budget constraints.
- `FAILED`: the invocation was attempted but did not produce a valid committed result.
- `TIMED_OUT`: the deadline was reached; committed child evidence remains usable.
- `CANCELLED`: cancellation was accepted; committed evidence remains usable.
- `NOT_EXECUTED`: the node never ran because its branch was not selected or a predecessor barrier
  made execution impossible.

The current string-based statuses must migrate to one versioned Runtime protocol type. Until that
migration is complete, architecture tests maintain a finite compatibility mapping and reject new
synonyms.

## 4. MCP interaction pipeline

Every MCP call crosses the same controlled pipeline:

```text
Plan candidate
 -> authoritative DAG validation
 -> Ready-node scheduling
 -> capability and tool resolution
 -> tenant/user/role/token authorization
 -> template and asset admission
 -> dependency evidence binding
 -> schema-driven argument compilation
 -> idempotency and attempt allocation
 -> MCP invocation
 -> transport/result normalization
 -> evidence commit
 -> result-contract review
 -> downstream release
```

No planner, rewriter, fallback path, or compatibility adapter may bypass these stages. In
particular, template names are parameters, not executable tool identities, and a discovered asset
or template is not execution evidence.

### Template discovery-to-execution continuity

Every template execution path uses the same Runtime-owned admission contract. Before invocation,
Runtime must prove all of the following:

1. the scalar template id occurs in the successful, authorized discovery response;
2. the template or discovery envelope declares an executor compatible with the selected allowed
   tool; deployment namespace prefixes may differ, semantic executor identity may not;
3. the template is active and executable, and its parameter schema is structurally valid;
4. every required parameter is compiled from user evidence, predecessor evidence, or a declared
   template default;
5. the asset/environment execution context is copied from authorized discovery metadata;
6. batch children retain one result slot each and failures never erase successful sibling evidence.

An invented or stale model template id may be replaced only when discovery returned exactly one
executable contract. Multiple executable candidates require an evidence-reviewed id; Runtime never
chooses the first result implicitly. A transport-level success is committed to the model as
`DATA_RETURNED`, `EMPTY_RESULT`, or `PARTIAL` according to the result contract, while rejected,
failed and not-executed children retain their distinct machine-readable states and reason codes.

## 5. Failure and recovery matrix

| Situation | Runtime OS behavior | Model-visible fact |
| --- | --- | --- |
| Tool not in request scope | block before invocation | `BLOCKED/TOOL_NOT_AVAILABLE` |
| Permission or token rejected | block and audit identity | `BLOCKED/AUTHORIZATION_DENIED` |
| Confirmation required | suspend resumably | `BLOCKED/CONFIRMATION_REQUIRED` |
| Required argument absent | recover only from cited user/predecessor evidence; otherwise block | missing fields and provenance |
| Invalid argument type | deterministic schema repair when lossless; otherwise block | repair record or validation error |
| Asset candidates ambiguous | semantic review over returned candidates; never guess identity | candidate set and unresolved dimensions |
| No matching template | retain discovery evidence; retry only when query repair is justified | `EMPTY/TEMPLATE_NOT_FOUND` |
| Template found, executor missing | block; never invoke template name | `BLOCKED/EXECUTOR_UNAVAILABLE` |
| Template executor mismatch | reject before invocation; retain candidate audit | `BLOCKED/TEMPLATE_EXECUTOR_MISMATCH` |
| Template parameter schema malformed | reject before parameter compilation | `BLOCKED/TEMPLATE_PARAMETER_SCHEMA_INVALID` |
| Multiple executable templates remain | require evidence-reviewed selection; never pick first | `BLOCKED/TEMPLATE_SELECTION_AMBIGUOUS` |
| Model template id was not discovered | replace only from one unique executable contract; otherwise block | binding repair or `BLOCKED/TEMPLATE_ID_NOT_DISCOVERED` |
| MCP transport error | bounded retry according to retryability and deadline | attempt history and final transport state |
| MCP success with zero rows | commit authoritative empty result | `EMPTY`, never “tool failed” |
| Result contract incomplete | commit raw evidence, mark partial, target missing fields | `PARTIAL/RESULT_CONTRACT_INCOMPLETE` |
| Pagination remains | continue within budget or mark partial with cursor | page count, cursor and completeness |
| Batch child fails | isolate child and continue independent children | ordered child outcome ledger |
| Required predecessor fails | enforce dependency policy; do not fabricate downstream input | predecessor terminal state |
| Optional predecessor fails | continue only if target input contract remains satisfied | partial evidence limitation |
| Model review rejects usable data | retain committed evidence and use deterministic fallback review | review disagreement, not data loss |
| Model timeout after tool success | finalize from committed evidence where possible | timeout plus preserved evidence |
| Process restart | resume from the latest consistent checkpoint boundary | resume token and reused step IDs |

## 6. Data completeness contract

Before model analysis, Runtime OS produces an immutable analysis envelope containing:

- execution state and reason code;
- tool, service, template, asset and attempt identity;
- request scope and authorization decision reference;
- schema/contract version;
- raw evidence reference plus normalized structured projection;
- record, page, child-call and dataset counts;
- completeness flags and unresolved dimensions;
- truncation/spill status and retrieval handle;
- provenance for every derived field.

The model receives this envelope, not an ambiguous log string. Context compression may summarize
payloads but must preserve counts, states, identifiers, completeness, errors, and evidence handles.
Large evidence is offloaded and referenced; it is never silently truncated and then described as a
complete dataset.

## 7. Scheduling and dependency invariants

1. Java computes Ready nodes from the validated DAG; the model cannot execute an arbitrary node.
2. Required dependencies are barriers. Optional dependencies never become implicit barriers.
3. Branches use explicit branch groups and conditional edges, not list order.
4. A node consumes only committed predecessor evidence with verified identity fingerprints.
5. Rewrites are copy-on-write, versioned, bounded, and cannot remove already committed evidence.
6. Retries allocate new attempts but preserve one idempotency identity for the logical operation.
7. Parallel children are failure-isolated and their results are deterministically ordered.
8. Final synthesis waits for the required terminal barrier, not merely for a model final-answer step.

## 8. Model interaction rules

- Model output is always an untrusted candidate protocol.
- Candidate plans and parameters are schema-validated and scope-validated.
- The model may choose only among Runtime-provided candidate identities.
- The model cannot convert `FAILED`, `BLOCKED`, or `NOT_EXECUTED` into `EMPTY`.
- The model cannot convert partial evidence into a complete conclusion.
- A reviewer rejection cannot delete successful structured evidence.
- When some evidence is usable, the answer reports supported findings plus explicit limitations;
  it must not reject the entire task solely because another dimension failed.

## 9. Observability and troubleshooting

Every stage emits a versioned event with:

`tenantId`, `userId`, `requestId`, `conversationId`, `runId`, `planId`, `planVersion`, `stepId`,
`attemptId`, `toolCallId`, `component`, `phase`, `state`, `reasonCode`, `startedAt`, `finishedAt`,
`durationMs`, `inputFingerprint`, `outputFingerprint`, and evidence/checkpoint references.

One failure must be diagnosable without reconstructing natural-language logs. The run status API
must expose the event cursor, current phase, blocked/terminal reason, pending nodes, completed nodes,
attempt history, evidence availability, and resume capability.

## 10. Core-class decomposition target

- `AgentPlanner`: facade over prompt builder, model gateway, protocol parser, validator and
  attribution selector.
- `AgentWorkflowDecisionEngine`: facade over workflow contract resolver, condition evaluator,
  scheduling policy and decision recorder.
- `AgentAnswerFinalizer`: facade over evidence envelope compiler, candidate generator, reviewer,
  quality selector, citation binder and deterministic fallback.
- `AgentOrchestrationEngine`: Runtime OS composition root and lifecycle coordinator only.

Each production class is limited to one reason to change and normally no more than 1,000 lines.
Exceptions require an architecture-test waiver containing an owner, reason, expiry date and
downward migration target.

## 11. Commercial acceptance gates

The refactor is complete only when automated tests cover at least:

- success, authoritative empty, partial, block, failure, timeout, cancellation and not-executed;
- required/optional dependencies, conditional branches, fan-out/fan-in and parallel isolation;
- pagination and large-result spill without evidence loss;
- template/asset ambiguity, no match, stale identity and unavailable executor;
- parameter repair, missing parameters and prohibited guessed values;
- token/role/tenant authorization and confirmation suspension/resume;
- retryability, idempotency, checkpoint recovery and process restart;
- model malformed JSON, invalid plan, invalid review, timeout and contradictory analysis;
- preservation of usable evidence through every failure and rewrite path;
- final answer claims and citations matching committed evidence.

No prompt-only behavior is considered a sufficient implementation of these guarantees.
