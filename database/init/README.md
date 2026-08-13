# Database initialization

The project uses two independent database boundaries. Choose the script matching both the application and database engine:

| Application | MySQL 8+ | H2 2.x | Tables |
| --- | --- | --- | ---: |
| ChatChat API | `mysql/chatchat-api.sql` | `h2/chatchat-api.sql` | 48 |
| Standalone MCP Server | `mysql/chatchat-mcp-server.sql` | `h2/chatchat-mcp-server.sql` | 33 |

Run these scripts only against a new, empty database. They contain the complete current JPA schema, including indexes and unique constraints, and intentionally do not drop existing objects.

Example:

```bash
mysql --default-character-set=utf8mb4 -u USER -p DATABASE < database/init/mysql/chatchat-api.sql
```

```bash
java -cp h2.jar org.h2.tools.RunScript \
  -url jdbc:h2:file:./data/chatchat \
  -user sa \
  -script database/init/h2/chatchat-api.sql
```

The API and standalone MCP Server may use different physical databases. Do not initialize both schemas into one database unless that deployment intentionally shares them.

The scripts are generated and checked by `DatabaseSchemaGeneratorTest` in the corresponding application module. After schema changes, regenerate and review both dialects before changing production from `ddl-auto: update` to `ddl-auto: validate`.

## Immutable runtime summary contract

`runtime_summary_contract` is the authoritative store for record-grounded summary rules. On startup the API loads the single enabled `record_grounded_analysis` contract. It inserts `record_grounded_analysis.v1` only when no enabled database contract exists; an existing row is never updated from application defaults.

The SHA-256 checksum is validated before the contract is used. A checksum mismatch, mutable active row, or multiple active rows stops startup instead of silently replacing the rules. `skill_config.workflow_config_json.resultHandlingPolicy.recordAnalysisPolicy` is retained only as a compatibility projection of the database contract.

```sql
SELECT contract_id, contract_key, contract_version, enabled, immutable,
       checksum_sha256, JSON_PRETTY(rules_json)
FROM runtime_summary_contract
ORDER BY created_at DESC;
```

## Immutable Agent Runtime DAG governance contract

`runtime_dag_governance_contract` is the authoritative store for critical Agent Runtime DAG invariants. Startup loads exactly one enabled `runtime_dag_governance` contract and bootstraps `runtime_dag_governance.v1` only when that contract key has never existed.

The loader verifies `immutable=true`, canonical JSON SHA-256, and the runtime-supported topology, scheduling, repair, retry, and persistence invariants. Missing, ambiguous, mutable, tampered, or unsupported active data stops startup. Every execution and persisted plan DAG snapshot is pinned to the active contract id, version, checksum, and rules.

```sql
SELECT contract_id, contract_key, contract_version, enabled, immutable,
       checksum_sha256, JSON_PRETTY(rules_json)
FROM runtime_dag_governance_contract
ORDER BY created_at DESC;
```

Never update an existing immutable row. Add a new version as a new row, validate runtime compatibility, and switch the enabled version through a controlled database migration.

## Durable DAG node attempts

`runtime_dag_node_attempt` journals every node execution independently of the final task result. Its state is monotonic:

`CREATED -> READY -> RUNNING -> PREPARED -> COMMITTED`

`FAILED`, `CANCELLED`, and `SKIPPED` are terminal alternatives. A unique tenant/run/node/attempt number prevents an execution from overwriting an earlier retry, while the JPA revision rejects stale concurrent state transitions. Do not edit Attempt rows manually; recovery and the commit barrier rely on their persisted state and fingerprints.

Nodes remain `PREPARED` until every required Attempt in the same execution epoch passes the transactional commit barrier. The barrier locks and validates the complete Attempt set before changing it to `COMMITTED`; a missing, cross-run, stale, or failed Attempt rejects the whole epoch. Only committed results are eligible for checkpoints and final analysis.
