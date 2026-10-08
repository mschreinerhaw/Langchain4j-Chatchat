create table if not exists mcp_argument_binding_policy (
    policy_key varchar(64) not null primary key,
    policy_json text not null
);

insert into mcp_argument_binding_policy (policy_key, policy_json) values (
    'default',
    '{"logicalContextKeys":["env","environment","cluster","namespace","target","targetType","target_type","assetName","asset_name","name","hostSelector","host_selector","database","databaseType","dbType","dialect","databaseRole","database_role","service","labels"],"concreteTargetFields":["hostId","host","hostname","ip","ipAddress","address","datasourceId","jdbcUrl","url","connectionString","endpointId","uri"],"rawExecutionFields":["command","rawCommand","shell","sql","rawSql","body","bodyTemplate"],"targetKindFields":["targetKind","target_kind","queryDomain","query_domain","domain","resourceType","resource_type","resourceKind","resource_kind"],"filterProtocolFields":["trace","routingTrace","routing_trace","candidates","routingCandidates","routing_candidates","finalDecision","final_decision","selectedTargetKind","selected_target_kind","targetKind","target_kind","assetType","asset_type","confidence","filtersSchemaVersion","filters_schema_version","mcpContext","mcp_context","tenantId","tenant_id","userId","user_id","requestId","request_id","conversationId","conversation_id","toolName","tool_name","remoteTool","remote_tool"]}'
);
