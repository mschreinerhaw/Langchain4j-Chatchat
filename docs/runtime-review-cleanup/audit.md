# Runtime OS Reviewer impact audit (2026-10-26)

Baseline: local 6c5cd2a0f + origin/perf/cline-codegen-20261026 (2a2ddf283). Remote main 86d496089 is already an ancestor. The latest performance branch includes the latest MCP permission branch (1dcf93600).

## Consumers and decisions

| Mechanism | Actual consumer | Action |
|---|---|---|
| AgentOrchestrationEngine.reviewInterpretationPlanToolResult | InterpretationAnalysisSession callback; mandatory discovery recovery; durable AgentPlanPhaseActivityCoordinator | Coding admission becomes deterministic; preserve legacy reviewer for other workflows |
| LocalToolResultReviewer | InterpretationPlanRuntime local fact gate BEFORE external review | Preserve: execution/fact validation |
| CanonicalAssetReferenceReviewer / PublishedDataSupplyReviewer | Canonical identity and published template matching | Preserve: authorized explicit matches only |
| ModelInvocationBudget / ToolReviewPromptContext / review timeout properties | Legacy review, coverage and binding | Preserve: live consumers, not dead configuration |
| AgentAnswerReviewer / DefaultAgentAnswerReviewer | General answer pipeline | Preserve: ordinary QA compatibility |
| SemanticClaimReviewer | Legacy synthesis nodes | Preserve: consumers outside Coding primary path |
| MandatoryWorkflowResultReviewer | Fixed workflow result admission | Preserve: deterministic workflow governance |
| SkillAnalysisContextService.prepare | Agent/Role interaction context | Register capability cards only; no resolve/readResource at binding |
| CodingConfigurationSnapshot | Frozen Cline task context | Strip legacy full documents from default Coding prompt |

No source symbol named tool_reviewer exists in this baseline; its live equivalent is model phase tool_result_review in AgentOrchestrationEngine. Deleting all Reviewer classes would break live workflows. No reviewer class/config is considered disposable solely because Coding no longer uses it.

## Boundaries

Coding callbacks must retain workflow attributes across durable resume, or they silently re-enter legacy model review. Failed executions remain rejected. Ambiguous discovery without an exact declared identity or published supply contract must return a scope clarification rather than silently choosing a candidate. This conservative boundary needs explicit acceptance coverage.

Java continues ToolRuntime authorization, tenant/user scope, immutable snapshots, schema/hash/numeric and Evidence checks. Cline continues Python receipt repair and final interpretation. Binding a Skill grants neither tool nor data authority.
