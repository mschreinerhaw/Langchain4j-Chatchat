alter table mcp_tool_workflow_contract alter column input_schema_json clob;
alter table mcp_tool_workflow_contract alter column output_schema_json clob;
alter table mcp_tool_workflow_contract alter column extensions_json clob;
alter table mcp_tool alter column input_schema_json clob;
alter table mcp_tool alter column output_schema_json clob;

alter table mcp_service_config add column contract_auto_publish boolean default true not null;
alter table mcp_service_config_version add column contract_auto_publish boolean default true not null;
