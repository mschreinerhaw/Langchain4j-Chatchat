# MCP tool workflow-contract migration

Apply the script for the target database before deploying the matching application build:

- MySQL: `mysql/V20260822_01__mcp_tool_workflow_contract.sql`
- H2: `h2/V20260822_01__mcp_tool_workflow_contract.sql`

The application keeps existing `mcp_tool` rows online by creating one ACTIVE version during
their first successful discovery. A newly discovered tool is stored as DRAFT and is not exposed
to agents until an administrator publishes it.

Publication uses optimistic compare-and-set semantics:

`POST /api/v1/enterprise/mcp-tools/{toolId}/contracts/{version}/publish?expectedActiveVersion={current}`

Use `expectedActiveVersion=0` for the first publication. Rolling back uses the same endpoint with
an older version and the currently active version as `expectedActiveVersion`. MySQL additionally
enforces one ACTIVE contract per tool with a generated-column unique constraint.

Operational checks after migration:

```sql
select tool_id, sum(case when status = 'ACTIVE' then 1 else 0 end) active_count
from mcp_tool_workflow_contract
group by tool_id
having active_count > 1;
```

The query must return no rows. Do not delete RETIRED versions; they are the rollback history.
