# Database initialization

The project uses two independent database boundaries. Choose the script matching both the application and database engine:

| Application | MySQL 8+ | H2 2.x | Tables |
| --- | --- | --- | ---: |
| ChatChat API | `mysql/chatchat-api.sql` | `h2/chatchat-api.sql` | 46 |
| Standalone MCP Server | `mysql/chatchat-mcp-server.sql` | `h2/chatchat-mcp-server.sql` | 25 |

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
