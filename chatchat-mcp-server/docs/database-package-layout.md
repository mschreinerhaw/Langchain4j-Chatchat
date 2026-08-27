# Database-query package layout

`com.chatchat.mcpserver.database` owns governed database-query definitions,
execution, MCP publication, business categorization, and administrative APIs.

| Package | Responsibility |
| --- | --- |
| `database.definition` | Query definitions, SQL steps, parameter mappings, result semantics, persistence, lifecycle, and sample seeding |
| `database.execution` | Query invocation, workflow execution, caching integration, and audit coordination |
| `database.publication` | MCP naming, tool specification, publication configuration, registration, and index refresh |
| `database.category` | Data-query category lifecycle, resolution, and category administration |
| `database.admin` | Database-query administrative HTTP API and test execution orchestration |

## Placement rules

1. Keep the `database` root free of concrete types.
2. Keep query models, persistence, and lifecycle rules together in `definition`.
3. Keep invocation and runtime workflow behavior in `execution`.
4. Keep MCP naming, specification, and registration behavior in `publication`.
5. Keep category management separate from query administration and avoid generic layer-only packages.
