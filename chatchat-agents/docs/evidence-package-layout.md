# Evidence package layout

`com.chatchat.agents.evidence` contains reusable evidence-domain models and deterministic policies. It remains independent from runtime and orchestration implementations.

| Package | Responsibility |
| --- | --- |
| `evidence.answer` | Evidence-backed answer contracts, grounding, citation binding, and deterministic assembly |
| `evidence.execution` | Evidence execution contracts, validation, locks, paths, policies, and reports |
| `evidence.graph` | Evidence graph model, constraints, views, and graph execution |
| `evidence.normalization` | Canonical chunks, sources, evidence types, and normalization |
| `evidence.format` | Stable evidence, graph, and Evidence OS output formatting |
| `evidence.sql` | SQL extraction, classification, state, and validation |
| `evidence.governance` | Evidence audit/governance metadata, document selection, and prompt-injection detection |

## Placement rules

1. Keep the `evidence` root free of concrete types; new classes must have clear functional ownership.
2. Evidence code may depend on shared protocol contracts, but must not import `runtime` or `orchestration` implementations.
3. Keep answer synthesis separate from evidence normalization and graph execution.
4. Put SQL-specific logic in `evidence.sql`; do not add database-specific branches to the generic graph or answer packages.
5. Prefer explicit domain types over generic `util` or `impl` packages.
