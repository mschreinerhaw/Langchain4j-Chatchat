# Capability-driven Runtime

## Unified entry protocol

Agent requests first generate a workflow-independent problem analysis plan, then select a workflow and its capabilities:

`Question + conversation context → PROBLEM_ANALYSIS_PLAN → SELECT_WORKFLOW → PLAN → RESOLVE_CAPABILITY → EXECUTE → EVALUATE → COMPLETE`

The front planner executes as an owned child phase on the calling task's worker.
It publishes `RUNNING` before inference and does not return until the model call
has settled. There is no separate 30-second wait timeout or four-worker rejection
pool. Concurrency belongs to the task scheduler; transport timeouts and retries
belong to the configured model client. Explicit task cancellation propagates as
cancellation, not a failed or successful plan. It uses the Agent's model binding,
the question, recent conversation and summary. It emits a structured public plan:
objective, subject, domain, concise explanation, tasks, evidence/data requirements,
expected results and any clarification question. It does not select tools or
execute data acquisition. This is not a private reasoning transcript.

The entry waits for planning before routing, and then for the selected provider's
execution before evaluation and response publication. An observation completing
one phase does not complete the parent task. A live child must never be converted
into a final response merely because a local wait interval elapsed.

`CapabilityWorkflowRouter` accepts only a validated `ProblemAnalysisPlan`, never
raw query text. `toolInput.workflowFamily` is now a preference in the analysis
input, not an override. Engine configuration does not determine workflow family.
Missing/invalid plans fail closed; ambiguous questions request clarification.
There is no keyword fallback or default Data Analysis execution.

Distinct objectives are preserved in the plan. The current atomic-provider
migration cannot safely execute a multi-family plan, so it asks which objective to
handle first instead of silently reducing the request or executing actions in parallel.
Action is a peer workflow, not a mandatory final phase of the other three.

| Family | Required capabilities | Optional capabilities |
| --- | --- | --- |
| DOCUMENT | document_scope, document_retrieval, evidence_verify | domain_guidance |
| DATA_ANALYSIS | data_acquisition, data_analysis, evidence_verify | domain_guidance |
| ASSET_GUIDANCE | asset_resolve, asset_metadata_read | asset_usage_read, domain_guidance |
| ACTION | action_authorization, action_input_validation, action_execute, action_verify | — |

The current migration adapts existing governed pipelines as **atomic composite
providers**. It does not yet execute an independently composed per-capability DAG.
Provider declarations describe supported responsibilities, not authorization or
proof that a resource is available. Existing resource scope, grants, confirmation,
tool selection Top-K and runtime budgets still apply during execution.

## Provider selection and bounded execution

- Document and Action use the existing governed Agent runtime; configured Skill
  Intelligence cannot intercept them.
- Data Analysis always enters the original governed Runtime, even with a configured
  Skill engine and published bound skills. Skills contribute authorized domain
  planning knowledge; they are not a competing execution provider.
- The authoritative execution chain remains `GraphPlanningPort → InterpretationPlan
  → plan validation → InterpretationAnalysisGraph`. Fixed MCP workflow dependencies,
  parameter binding, data acquisition, analysis, synthesis and publication stay with
  that chain. Skills cannot replace the plan or acquire data through a parallel loop.
- Asset Guidance uses the template/metadata workflow. Domain skills are optional
  enrichment. A missing template can produce a default metadata requirement;
  acquisition stays within the existing bounded metadata path.
- Role-chat still cannot acquire data through MCP.
  Domain skills enhance its existing prompt/context path, not a separate Skill runtime.
- A composite provider must support every required capability. A missing required
  capability prevents its execution; an optional capability does not.
- One selected provider runs once. A failed or empty result does not automatically
  trigger another provider or restart the workflow. No business API/SQL execution
  is introduced as an Asset Guidance fallback.

## Result protocol

`metadata.workflowOutcome` records the outcome type, reason, missing required and
optional capabilities, and whether a usable result exists. Runtime projects this
into `metadata.agent.publicStatus` for synchronous responses and task results.

An explanatory answer, a skill heading, or `NO_EXECUTABLE_PLAN` cannot alone count
as success. Required capability gaps prevent full success; optional gaps do not.
Action success additionally requires a successful tool record, and Document success
requires sources. Existing runtime verification remains authoritative for the
underlying operation. Time/model budgets and confirmation are preserved.

Metadata also exposes `problemAnalysisPlan`, `workflowFamily`, `capabilityPlan`, `capabilityProvider`,
`capabilityProviderKind`, and `runtimeWorkflowProtocolVersion=capability_workflow.v1`.
`runtimeLifecycle` describes the protocol stages; it is not a fabricated execution
event history. Existing pipeline events remain the actual execution record.
Async runs emit `PROBLEM_ANALYSIS_PLAN` before selecting/executing a workflow.
Role-chat also plans before selecting an analysis enhancement, but retains its
no-MCP execution boundary. Plain LLM chat and explicitly selected direct-tool calls
are not automatically converted into these Agent workflows.

No database migration is required. Rebuild and deploy the backend to activate the
change; previously persisted failed tasks are not rewritten.

## No business-specific Asset Guidance trigger

`AssetGuidanceIntent` and its business-phrase matching have been removed. The Agent
entry selects Asset Guidance from the problem analysis plan. The lower-level
analysis adapter honors a supplied intent, declared capability or typed plan;
asset names and phrases cannot switch an existing execution into guidance.
The old generic baseline analyzer remains for legacy unplanned non-guidance callers;
this change does not redesign those callers or the existing graph engine.
