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

A future Temporal adapter replaces the default Spring `WorkflowRuntime` bean. The local bean is
conditional on no other implementation being present and on
`chatchat.agent-runtime.workflow-engine=local`. Selecting a distributed adapter without installing
its bean intentionally fails application startup instead of silently falling back to local execution.

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

## Migration sequence

1. Implement `TemporalWorkflowRuntime` in a separate adapter module.
2. Register the `agent-run-v1` Workflow and its Activities.
3. Keep `AgentRunStore` as the read model during dual-write migration.
4. Project Temporal history into existing business events; do not expose engine Worker terminology.
5. Verify duplicate start, crash recovery, cancellation, activity retry and version-upgrade tests.
6. Move lifecycle authority to Temporal only after reconciliation tests prove one terminal result.

The final answer/publication operation requires its own business idempotency key and database unique
constraint. Workflow durability reduces duplicate execution but does not replace idempotent external
side effects.
