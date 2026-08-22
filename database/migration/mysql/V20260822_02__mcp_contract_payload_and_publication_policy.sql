alter table mcp_tool_workflow_contract
    modify input_schema_json longtext,
    modify output_schema_json longtext,
    modify extensions_json longtext;

alter table mcp_tool
    modify input_schema_json longtext,
    modify output_schema_json longtext;

alter table mcp_service_config
    add column contract_auto_publish bit default b'1' not null;

alter table mcp_service_config_version
    add column contract_auto_publish bit default b'1' not null;
