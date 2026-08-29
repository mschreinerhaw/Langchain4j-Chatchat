# ChatChat Agents Runtime OS responsibility audit

## Architectural rule

The model owns semantic proposals. Runtime OS owns authorization, executable state,
tool argument compilation, retries, deadlines, evidence identity, completion and the
terminal result. `EMPTY_RESULT`, `NOT_EXECUTED`, `BLOCKED`, `FAILED` and partial data
are never interchangeable.

Every orchestration component must expose a stable boundary, accept an immutable request
or context, and return an explicit result. Line-count ratchets are regression alarms,
not the architecture itself: a component is complete only when it has one coherent
reason to change. Compatibility delegates may remain in the old class only while tests
or downstream binaries still require the old method shape.

## Current boundaries

| Boundary | Owner | Excluded responsibilities |
|---|---|---|
| Public Agent API | `AgentOrchestrator` | planning, execution algorithms, evidence rendering |
| Lifecycle composition | `AgentOrchestrationEngine` | public API compatibility |
| Planning port | `AgentPlanningPort` / `AgentPlanningRequest` | MCP invocation and answer rendering |
| Prompt compilation | `AgentPlannerPromptBuilder` | model calls, JSON repair, candidate selection |
| Planner wire protocol | `PlannerEnvelopeParser` / `InterpretationPlanPayloadNormalizer` | model invocation, scoring, workflow execution |
| Planning domain contracts | `AgentDecision` / `PlannerValidationContext` / `PlanCandidate` | execution state and answer rendering |
| Plan candidate scoring | `AgentPlanCandidateScorer` | model calls, plan execution, mutable runtime state |
| Plan evolution audit | `AgentPlanEvolutionAuditor` | plan execution and persistence scheduling |
| Plan snapshot persistence | `InterpretationPlanSnapshotService` | planning and workflow scheduling |
| Workflow scheduling | `AgentWorkflowDecisionEngine` | condition parsing and tool alias rules |
| Workflow conditions | `WorkflowConditionEvaluator` | workflow mutation and execution |
| MCP execution | `AgentToolExecutor` | planner and final answer policy |
| Mandatory recovery | `MandatoryWorkflowRecoveryCoordinator` | presentation and model prompt construction |
| Answer finalization port | `AgentAnswerFinalizationPort` | tool execution and plan scheduling |
| Answer review execution | `AnswerReviewCoordinator` | candidate selection and user-facing rendering |
| User-facing answer policy | `AnswerUserFacingPolicy` | reviewer invocation and workflow execution |
| Deterministic reports | `DeterministicAnswerReportRenderer` | answer selection and model review |
| Result presentation | `AgentResultPresentationService` | model review and workflow completion |
| Evidence graph | `AgentEvidenceGraphService` | lifecycle decisions and UI rendering |
| Interpretation evidence analysis | `InterpretationPlanEvidenceAnalyzer` | scheduling and final-answer rendering |
| Durable run lifecycle | `AgentRunLifecycleCoordinator` | planning and domain workflow execution |
| Kernel scope binding | `AgentRunScopeBinder` | execution and persistence |

## Enforced migration ratchets

- Public facade and extracted domain components: at most 1,000 lines.
- Workflow decision engine: at most 1,000 lines.
- Planner migration host: at most 2,025 lines and may only shrink.
- Answer finalizer migration host: at most 2,075 lines and may only shrink.
- Orchestration engine migration host: at most 7,550 lines and may only shrink.

The ratchets are build failures, not documentation targets. New behavior must enter a
bounded collaborator rather than enlarge a migration host.

## Temporary migration register

| Migration host | Current ratchet | Owner | Expiry | Next boundary | Exit condition |
|---|---:|---|---|---:|---|
| `AgentPlanner` | 2,025 | Runtime OS / Planning | 2026-10-31 | responsibility-based | guard repair and candidate attribution leave the model loop |
| `AgentAnswerFinalizer` | 2,075 | Runtime OS / Answer | 2026-10-31 | responsibility-based | evidence audit and answer quality orchestration become bounded policies |
| `AgentOrchestrationEngine` | 7,550 | Runtime OS / Composition | 2026-12-31 | responsibility-based | analysis reduction and MCP execution coordination leave the composition root |

These entries are temporary architecture waivers. A waiver may only be renewed by an
architecture decision record that identifies the blocking dependency and lowers, rather
than raises, the next boundary. A low line count never substitutes for a single clear
responsibility and dependency direction.

## Phased completion

1. **Boundary establishment (complete):** public facade, planning/finalization ports,
   condition evaluation, mandatory-workflow recovery, evidence services and result
   presentation are separated and guarded by architecture tests.
2. **Planning and answer migration (in progress):** protocol parsing, payload normalization,
   candidate scoring, user-facing evidence policy, deterministic rendering and reviewer
   execution are bounded collaborators. Guard repair, attribution and quality coordination remain.
3. **Composition-root migration (in progress):** lifecycle, Kernel scope binding, evidence
   evaluation, plan evolution audit and snapshot persistence have left the engine. Dataset
   projection/reduction and MCP execution coordination remain.
4. **Final convergence:** remove temporary host ratchets after each host becomes a thin
   composition boundary, then remove compatibility delegates no downstream contract exercises.

Passing behavior tests does not complete phases 2-4. Completion requires both behavior gates
and the corresponding source-boundary ratchet.

## Package audit findings

The remaining highest-risk migration hosts are `AgentOrchestrationEngine`,
`InterpretationPlanRuntime`, `ToolRuntimeService`, `AgentPlanner`,
`AgentAnswerFinalizer`, `ToolObservationBuilder` and `InterpretationPlanValidator`.
They are stable legacy surfaces, not approved extension points. New workflow behavior
must be implemented through the Runtime OS ports and focused subpackages. The next
semantic migrations are analysis dataset projection/reduction, plan guard repair and
candidate attribution, answer evidence audit/quality coordination, tool transport
execution and plan checkpoint recovery.

## Release gates

1. Compile all dependent modules.
2. Run planner, workflow, answer and architecture suites.
3. Run failure-isolated MCP batch tests for success, empty, blocked, failed and not-executed children.
4. Run Runtime lifecycle tests for cancellation, deadline, retry and persisted events.
5. Run the entire `chatchat-agents` test suite.
6. Run the repository reactor test; an unrelated module failure must be reported with its exact module and cause.
