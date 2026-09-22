-- One ACTIVE MCP contract per tool; PostgreSQL partial indexes express this directly.
create unique index if not exists uk_mcp_tool_contract_single_active
    on mcp_tool_workflow_contract(tool_id) where status = 'ACTIVE';
