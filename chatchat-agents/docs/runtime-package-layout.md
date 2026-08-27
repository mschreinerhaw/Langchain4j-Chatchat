# Runtime package layout

The three top-level packages represent different architectural layers and must remain separate:

- `orchestration`: application workflow and model/tool coordination.
- `runtime`: audited execution, runtime state, and infrastructure.
- `evidence`: reusable evidence-domain types and rules; it must not depend on runtime implementations.

`runtime` is organized by functional ownership:

| Package | Responsibility |
| --- | --- |
| `runtime` | Stable runtime API: request, result, handle, executor, facade, and snapshot |
| `runtime.execution` | Default runtime implementation, scheduling, and tenant fairness |
| `runtime.run` | Run aggregate, status, steps, queries, and outcome projection |
| `runtime.event` | Run events and event bus ports/implementations |
| `runtime.store` | Run persistence, retention, and in-memory/RocksDB stores |
| `runtime.observation` | Observations, grounding, and evidence storage ports |
| `runtime.answer` | Answer review, candidate collection, and draft policy |
| `runtime.analysis` | Result-analysis adapters and evidence spill storage |
| `runtime.governance` | MCP evidence governance and isolation scope |
| `runtime.tool` | Tool execution policy, auditing, throttling, and service |
| `runtime.config` | Runtime, workflow, and executor configuration |
| `runtime.plan` | Interpretation plan lifecycle and recovery |
| `runtime.batch` | Failure-isolated batch execution |
| `runtime.toolcall` | Tool argument compilation and invocation bridge |
| `runtime.protocol` | Runtime-facing protocol ports and envelopes |
| `runtime.evidence` | Runtime-specific diagnostic evidence normalization |
| `runtime.trace` | Runtime trace projections |
| `runtime.evaluation` | Offline and production quality evaluation |

## Placement rules

1. Keep only stable caller-facing contracts in the `runtime` root package.
2. Put implementations beside the state and policies they own; do not create generic `util` or `impl` packages.
3. Runtime subpackages may depend on the root API. The root API must not depend on implementation subpackages except for domain return types already exposed by `AgentRuntime`.
4. Do not move orchestration workflow classes into runtime. Runtime must remain independent from `orchestration`.
5. Put reusable evidence concepts in the top-level `evidence` package. Use `runtime.evidence` only when the type is coupled to runtime plan/batch diagnostics.
