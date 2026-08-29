# ChatChat Agents Runtime OS responsibility audit

## Architectural rule

The model owns semantic proposals. Runtime OS owns authorization, executable state,
tool argument compilation, retries, deadlines, evidence identity, completion and the
terminal result. `EMPTY_RESULT`, `NOT_EXECUTED`, `BLOCKED`, `FAILED` and partial data
are never interchangeable.

Every orchestration component must expose a stable port, accept an immutable request
or context, return an explicit result, and remain below 1,000 source lines once its
migration ratchet reaches that boundary. Compatibility delegates may remain in the
old class only while tests or downstream binaries still require the old method shape.

## Current boundaries

| Boundary | Owner | Excluded responsibilities |
|---|---|---|
| Public Agent API | `AgentOrchestrator` | planning, execution algorithms, evidence rendering |
| Lifecycle composition | `AgentOrchestrationEngine` | public API compatibility |
| Planning port | `AgentPlanningPort` / `AgentPlanningRequest` | MCP invocation and answer rendering |
| Prompt compilation | `AgentPlannerPromptBuilder` | model calls, JSON repair, candidate selection |
| Workflow scheduling | `AgentWorkflowDecisionEngine` | condition parsing and tool alias rules |
| Workflow conditions | `WorkflowConditionEvaluator` | workflow mutation and execution |
| MCP execution | `AgentToolExecutor` | planner and final answer policy |
| Mandatory recovery | `MandatoryWorkflowRecoveryCoordinator` | presentation and model prompt construction |
| Answer finalization port | `AgentAnswerFinalizationPort` | tool execution and plan scheduling |
| Result presentation | `AgentResultPresentationService` | model review and workflow completion |
| Evidence graph | `AgentEvidenceGraphService` | lifecycle decisions and UI rendering |

## Enforced migration ratchets

- Public facade and extracted domain components: at most 1,000 lines.
- Workflow decision engine: at most 1,000 lines.
- Planner migration host: at most 3,000 lines and may only shrink.
- Answer finalizer migration host: at most 2,800 lines and may only shrink.
- Orchestration engine migration host: at most 8,700 lines and may only shrink.

The ratchets are build failures, not documentation targets. New behavior must enter a
bounded collaborator rather than enlarge a migration host.

## Temporary migration register

| Migration host | Current ratchet | Owner | Expiry | Next boundary | Exit condition |
|---|---:|---|---|---:|---|
| `AgentPlanner` | 3,000 | Runtime OS / Planning | 2026-10-31 | 2,000 | protocol parser and candidate attribution are ports |
| `AgentAnswerFinalizer` | 2,800 | Runtime OS / Answer | 2026-10-31 | 1,800 | evidence policy and quality review are ports |
| `AgentOrchestrationEngine` | 8,700 | Runtime OS / Composition | 2026-12-31 | 6,000 | analysis reduction and plan lifecycle leave the composition root |

These entries are temporary architecture waivers. A waiver may only be renewed by an
architecture decision record that identifies the blocking dependency and lowers, rather
than raises, the next boundary. The final boundary for every row is 1,000 lines.

## Phased completion

1. **Boundary establishment (complete):** public facade, planning/finalization ports,
   condition evaluation, mandatory-workflow recovery, evidence services and result
   presentation are separated and guarded by architecture tests.
2. **Planning and answer migration:** move protocol parsing, candidate scoring, evidence
   policy and quality review into bounded collaborators; lower both ratchets after every
   extraction.
3. **Composition-root migration:** move dataset reduction, evidence evaluation, DAG repair,
   checkpointing and mandatory workflow execution out of `AgentOrchestrationEngine`.
4. **Final convergence:** replace all temporary ratchets with the 1,000-line domain-component
   limit and remove compatibility delegates that no downstream contract still exercises.

Passing behavior tests does not complete phases 2-4. Completion requires both behavior gates
and the corresponding source-boundary ratchet.

## Package audit findings

The remaining highest-risk migration hosts are `InterpretationPlanRuntime`,
`AgentOrchestrationEngine`, `ToolRuntimeService`, `AgentPlanner`,
`AgentAnswerFinalizer`, `ToolObservationBuilder` and `InterpretationPlanValidator`.
They are stable legacy surfaces, not approved extension points. New workflow behavior
must be implemented through the Runtime OS ports and focused subpackages. The next
mechanical migrations are evidence-iteration analysis, analysis dataset reduction,
planner protocol parsing, plan candidate attribution, answer evidence policy, tool
transport execution and plan checkpointing.

## Release gates

1. Compile all dependent modules.
2. Run planner, workflow, answer and architecture suites.
3. Run failure-isolated MCP batch tests for success, empty, blocked, failed and not-executed children.
4. Run Runtime lifecycle tests for cancellation, deadline, retry and persisted events.
5. Run the entire `chatchat-agents` test suite.
6. Run the repository reactor test; an unrelated module failure must be reported with its exact module and cause.
