# Runtime OS Workflow Engine Boundary

## Ownership

The model is a planner. It may propose or revise an `InterpretationPlan`, but it does not own
execution state, retries, cancellation, idempotency or terminal publication.

`WorkflowRuntime` is the Runtime OS execution port. `AgentRunStore` remains the persistent business
state authority while the default `LocalWorkflowRuntime` is active. The local adapter must never
create a second persistent workflow state store.

## Current execution contract

An Agent submission is projected to a workflow start request with:

- workflow id: Agent `runId`;
- workflow type: `agent-run-v1`;
- tenant scope: Agent `tenantId`;
- idempotency key: `tenantId:requestId`;
- input: `AgentRunRequest`.

Only one active execution may exist for the same workflow id. A duplicate start with the same
business identity shares the existing completion. Reusing the id with another tenant, workflow type
or idempotency key is rejected.

Terminal Agent state is written before workflow completion is exposed. `WAITING_CONFIRMATION` also
closes automatic resubmission; it may only continue through an explicit confirmation transition.

## Temporal adapter boundary

The `chatchat-runtime-temporal` module provides the durable Temporal adapter. Set
`CHATCHAT_AGENT_RUNTIME_WORKFLOW_ENGINE=temporal` to replace the local Spring
`WorkflowRuntime` bean. Its Workflow id is the Agent `runId`; Temporal rejects reuse of that id and
duplicate submissions attach to the existing result. The Activity heartbeats so cancellation reaches
the existing Agent cancellation checks. A terminal `AgentRunStore` record is returned before any
redispatch is executed, making process recovery idempotent at the business-run boundary.

The current adapter intentionally uses one coarse-grained `agent-run-v1` Activity around the mature
Agent executor. This gives durable ownership, restart attachment and cancellation without copying
or forking orchestration logic. Activity retry defaults to one attempt because the existing tool
side effects do not yet all have independent idempotency keys. Raise it only after those external
operations have been audited.

The adapter must preserve these mappings:

| Runtime OS concept | Temporal concept |
| --- | --- |
| Agent run | Workflow Execution |
| Interpretation plan branch | Child Workflow |
| LLM, MCP or database call | Activity |
| approval or external continuation | Signal or Update |
| cancellation | Workflow cancellation |
| durable delay | Timer |
| run/query status | Query |

LLM calls, network calls, database calls and clock/random access must remain in Activities. Temporal
Workflow code must contain deterministic control flow only.

## Migration status and next granularity

Completed in phase two:

1. `TemporalWorkflowRuntime` is isolated in its own adapter module.
2. `agent-run-v1` is a stable registered definition and `AgentRunStore` remains the business read model.
3. Duplicate starts, durable attachment, terminal replay and cancellation are covered by tests.
4. Engine terminology remains internal; existing progress projection emits business analysis stages.

Later fine-grained migration may map plan branches to Child Workflows and idempotent LLM/MCP/database
operations to Activities. It must not create a second planning implementation.

## Phase three migration status

Phase three is incremental because changing Temporal command order without version markers would
make existing Workflow histories non-replayable. The first two migration slices are active:

1. The stable Agent Run Workflow now delegates new executions to an
   `runtime-os-agent-execution-v1` Child Workflow. `Workflow.getVersion` preserves replay of
   phase-two histories that scheduled the coarse Activity directly.
2. A registered `runtime-os-tool-execution-v1` Workflow and `runtime-os-tool-execute-v1` Activity
   provide the independent durable tool-call boundary. The Activity injects its stable idempotency
   key and attempt into `ToolRuntimeRequest`.
3. Tool Activity retry admission is fail-closed. Writes, tools without metadata and read-only tools
   without an explicit `workflowRetrySafe` or `idempotent` declaration remain at one attempt.
   Explicitly idempotent read-only tools may request up to five attempts. The inner
   `ToolRuntimeService` retry loop is disabled for these calls so retry layers cannot multiply.
   The Activity revalidates serialized retry admission against the live runtime-owned tool metadata
   before invocation; stale or caller-forged retry claims fail non-retryably without calling the tool.

The mature Agent executor still runs inside the execution Child Workflow's coarse Activity.
`InterpretationPlanRuntime` no longer calls
`ToolRuntimeService` directly: primary step calls, retrieval-quality fallbacks and template-contract
resolution all cross the serializable `plan_tool_execution.v1` port. With the local engine this port
delegates in process. With Temporal selected it starts or reattaches to a stable
`runtime-os-tool-execution-v1` Workflow, which executes the governed tool Activity and reuses its
persisted result for the same idempotency identity. The waiting Agent Activity heartbeats while the
tool Workflow is running and cancels it when parent cancellation is observed.

The second slice moves DAG control authority out of the coarse Activity. A shared pure
`DeterministicPlanDagStateMachine` defines graph compilation, Ready-node waves, descendant regions
and commit-barrier admission. Local execution uses the same kernel in process; Temporal execution
opens a stable `runtime-os-plan-dag-control-v1` Workflow through the serializable
`plan_dag_control.v1` port. Workflow Updates durably record each node-state revision, calculate the
next Ready set and decide `COMMIT_ALL`, `COMMIT_INDEPENDENT` or `REJECT_WAVE`. The final synchronized
state is closed and returned as the Workflow result, so recovery has an authoritative terminal DAG
snapshot. The Activity remains the execution plane for model arbitration and mature step logic, but
it no longer owns Ready-node or commit-barrier decisions.

The next slice moves model arbitration and remaining step preparation/persistence behind dedicated
Activities, then promotes the deterministic scheduling loop into the execution Child Workflow. At
that point tool executions can become direct Child Workflows instead of externally started durable
Workflows.

The Temporal adapter package is classified by responsibility: `config`, `core`, `contract`,
`workflow`, `activity` and `adapter`. The root package contains documentation only; an architecture
test prevents implementations from accumulating there again. Explicit Temporal Workflow and
Activity type names remain unchanged across this Java package migration.

The fine-grained plan execution Workflow type is registered as `runtime-os-plan-execution-v1`.
It executes business stages when a `PlanExecutionPhaseHandler` is supplied; without one it fails
immediately with the non-retryable `PLAN_PHASE_HANDLER_UNAVAILABLE` application failure. It owns
the deterministic `while (remaining)` loop, validates Ready-wave admission, evaluates the barrier
and invokes tool executions as direct Child Workflows. Model arbitration, step preparation and node
journal/checkpoint persistence use versioned `plan_*.v1` contracts and three separately
named Activities. `AgentOrchestrationEngine` now implements the resumable Agent contract and
delegates Activity behavior to `AgentPlanPhaseActivityCoordinator`, which reuses the mature
parameter-binding, template-batch and evidence-review runtime. For `agent-run-v1`, the Agent
Execution Workflow runs a deterministic `bootstrap -> Plan Child Workflow -> resume` loop; a plan
rewrite yields another continuation without invoking the top-level planner again. The legacy coarse
Activity remains only behind Temporal versioning for replay of histories created before this change,
and non-Agent registered workflow types continue to use the generic Activity route.

## Configuration

```text
CHATCHAT_AGENT_RUNTIME_WORKFLOW_ENGINE=temporal
CHATCHAT_TEMPORAL_TARGET=127.0.0.1:7233
CHATCHAT_TEMPORAL_NAMESPACE=default
CHATCHAT_TEMPORAL_TASK_QUEUE=chatchat-agent-runtime
CHATCHAT_TEMPORAL_ACTIVITY_TIMEOUT_SECONDS=86400
CHATCHAT_TEMPORAL_HEARTBEAT_SECONDS=5
CHATCHAT_TEMPORAL_ACTIVITY_MAX_ATTEMPTS=1
```

Keep `workflow-engine=local` for an explicit rollback. Switching engines does not migrate active local
runs; drain them first or reconcile them through `AgentRunStore`.

The final answer/publication operation requires its own business idempotency key and database unique
constraint. Workflow durability reduces duplicate execution but does not replace idempotent external
side effects.
