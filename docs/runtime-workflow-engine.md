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
