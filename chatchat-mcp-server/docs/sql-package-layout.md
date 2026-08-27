# SQL package layout

`com.chatchat.mcpserver.sql` owns datasource configuration, SQL templates,
execution, metadata indexing and resolution, calendar parameters, MCP publication,
and administration.

| Package | Responsibility |
| --- | --- |
| `sql.datasource` | Datasource configuration, persistence, lifecycle, and schema compatibility migration |
| `sql.template` | SQL template configuration, persistence, seeding, validation, and rendering |
| `sql.execution` | Query/script execution, safety checks, and execution result contracts |
| `sql.parsing` | Shared statement splitting and qualified table-name parsing |
| `sql.metadata` | Metadata indexing, refresh, search, asset registry, and system metadata queries |
| `sql.resolution` | Table resolution, semantic matching, datasource affinity, routing fusion, and usage feedback |
| `sql.calendar` | Trading-calendar configuration and dynamic date parameter resolution |
| `sql.tool` | SQL MCP tool publication and execution routing |
| `sql.admin` | Administrative HTTP API spanning SQL capabilities |

## Placement rules

1. Keep the `sql` root free of concrete types.
2. Place configuration entities and repositories beside the capability they configure.
3. Keep execution safety and result contracts in `execution`; reusable syntax handling belongs in `parsing`.
4. Keep metadata acquisition in `metadata` and table-selection decisions in `resolution`.
5. Keep cross-capability MCP routing in `tool` and administrative HTTP orchestration in `admin`.
