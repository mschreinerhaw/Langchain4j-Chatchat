# Orchestration package layout

`orchestration` coordinates planning, tools, evidence, analysis, and answer production. Its classes are grouped by functional ownership:

| Package | Responsibility |
| --- | --- |
| `orchestration` | Workflow entry point, result adapter, planner, and core workflow decision contract/engine |
| `orchestration.planning` | Budgets, runtime guards, and interpretation-plan workflow validation |
| `orchestration.workflow` | Workflow state tracking, tool selection, and configuration failures |
| `orchestration.answer` | Answer contracts, evidence ledgers, quality evaluation, repair, and finalization |
| `orchestration.analysis` | Structured-data projection, deterministic insights, task dispatch, reduction, and context sizing |
| `orchestration.evidence` | Evidence aggregation, trust, sufficiency, and completion loops |
| `orchestration.retrieval` | Metadata/MCP discovery, parameter binding, and model-assisted retrieval |
| `orchestration.tool` | Tool-name and argument resolution, fingerprints, observations, and analysis adaptation |
| `orchestration.model` | Chat-model selection and deadline enforcement |
| `orchestration.protocol` | Orchestration-owned runtime protocol defaults and configuration |

## Placement rules

1. Keep the root package limited to the orchestration entry point and tightly coupled core engines/contracts.
2. Place new implementations in the child package that owns their behavior; avoid generic `util` and `impl` packages.
3. Expose cross-package behavior through explicit `public` members; keep implementation-only helpers private.
4. Keep runtime execution independent: `runtime` must not import `orchestration`.
5. Put reusable evidence-domain types in top-level `com.chatchat.agents.evidence`; use `orchestration.evidence` for workflow-specific evidence decisions. Cross-layer answer contracts belong to `protocol`.
