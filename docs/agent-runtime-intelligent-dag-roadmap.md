# Agent Runtime intelligent DAG roadmap

## Positioning

Agent Runtime is a decision-analysis workflow engine, not a distributed stream-processing replacement. Spark and Flink are useful references for execution correctness, recovery, state, scheduling, and observability; their shuffle, event-time, watermark, and data-locality machinery should only be adopted when the workload actually needs it.

The current Runtime is already stronger than a basic LLM workflow in authoritative workflow restoration, deterministic validation/repair, dependency and edge contracts, evidence lineage, bounded replanning, confirmation gates, partial-evidence handling, and versioned DAG snapshots. Its main gap is that several execution invariants were code constants rather than an externally pinned, immutable governance contract.

## Capability comparison

| Area | Current Agent Runtime | Spark/Flink reference | Target for decision analysis |
| --- | --- | --- | --- |
| Topology | Typed steps, hard dependencies, edge/dependency contracts, cycle validation | Compiled job graph and execution graph | Keep authoritative topology immutable; permit bounded, audited subgraph expansion only |
| Scheduling | Deterministic ready-set selection and optional parallel waves | Slot/resource-aware scheduling and pipelined regions | Add tenant quotas, node cost estimates, concurrency pools, and fairness |
| Failure isolation | Dependency failure policy and partial-evidence continuation | Failover regions and restart strategies | Compute affected failure region; continue independent branches by contract |
| State/recovery | Run events, successful step checkpoints, reusable results | Durable operator state, checkpoints, savepoints | Persist node attempts, input/dependency fingerprints, commit state, and resume token |
| Delivery semantics | Idempotency checks exist in tool/runtime contracts | At-least-once/exactly-once and transactional sinks | Define per-node `READ_ONLY`, `IDEMPOTENT`, or `TRANSACTIONAL` delivery class |
| Backpressure | Global step/tool/time budgets | Credit/backpressure and bounded buffers | Add evidence-size, model-token, tool-QPS, and queue-depth pressure signals |
| Adaptive execution | Model decision plus deterministic repairs/rewrite | Adaptive query execution/rescaling | Separate immutable base DAG from versioned runtime expansion patches |
| Consistency | Evidence and contract checks | Barriers and coordinated snapshots | Add execution epoch and commit barrier before final analysis |
| Replay | Plan versions and event history | Deterministic recovery from checkpoints | Pin contract/tool/schema/model fingerprints and reject incompatible replay |
| Observability | DAG snapshots, repair events, diagnostic coverage | Operator metrics and job timeline | Add node latency, retries, queue time, evidence yield, confidence, and cost |

## Delivery priorities

Implementation status: the database-backed immutable governance contract, durable node-attempt state machine, transactional execution-epoch commit barrier, complete checkpoint fingerprint, and recoverable execution are implemented. Only barrier-committed node results may enter checkpoints or final analysis. Checkpoint schema v3 binds plan version, node definition, actual resolved input, dependency results, tool contract, secret-free model configuration, governance snapshot, and logical execution environment into one aggregate identity. Runtime reconciles those checkpoints with MySQL `COMMITTED` Attempts, derives the latest topologically consistent boundary and a content-addressed `resume.v1` token, then continues from the first uncommitted node. Missing Attempts, token tampering, or fingerprint drift fail closed and trigger safe recomputation.

### P0 — correctness foundation

1. Database-backed immutable governance contract, startup checksum verification, and execution snapshot pinning.
2. Explicit node-attempt state machine: `CREATED -> READY -> RUNNING -> PREPARED -> COMMITTED`, plus terminal failure/cancel/skip states.
3. Failure-region calculation instead of treating the first node failure as a task-wide terminal event.
4. Durable checkpoint identity based on plan version, node definition, actual resolved input, dependency-result fingerprints, tool contract, model configuration, governance contract, and execution environment. **Implemented in `plan_step_checkpoint_v3`.**
5. Commit barrier before final answer so only committed evidence is summarized.

### P1 — intelligent decision DAG

1. Bounded subgraph expansion with a signed patch record; the model proposes, deterministic guards approve.
2. Multi-objective planning over evidence coverage, confidence, latency, monetary cost, and risk.
3. Alternative branches and hypothesis comparison instead of a single linear answer path.
4. Policy-driven human confirmation gates and resumable approval state.
5. Replay simulator and fault injection for timeout, duplicate delivery, stale checkpoint, partial batch, and contract drift.

### P2 — scale and operations

1. Tenant-aware resource pools, fairness, tool QPS limits, and queue backpressure.
2. HA scheduler leadership and leased node execution.
3. Adaptive concurrency from observed latency/error rate.
4. Streaming/event-time semantics only for genuinely continuous decision workflows.

## Immutable contract lifecycle

`runtime_dag_governance_contract` is the authoritative immutable header and `runtime_dag_governance_contract_rule` stores its typed rule records. A contract version is never edited in place. The backend reconstructs nested objects and arrays from ordered path records. Startup requires exactly one enabled contract, validates the canonical reconstruction SHA-256 and runtime-supported invariants, then pins its identity and rules into every execution and persisted DAG snapshot.

An upgrade creates a new row, passes compatibility and replay tests, then switches activation in a controlled migration. Existing snapshots retain the old contract fingerprint. Runtime code must fail closed when it cannot implement the pinned contract; it must never silently substitute current defaults.

The first contract, `runtime_dag_governance.v1`, fixes authority order, topology validation, ready-node semantics, independent-branch continuation, deterministic repair, authoritative-topology protection, bounded retry, idempotency requirements, audit events, and snapshot pinning.
