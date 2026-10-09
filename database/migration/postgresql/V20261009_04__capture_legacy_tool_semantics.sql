-- Freeze the existing tool identities that may continue using name-rule execution roles.
create table if not exists agent_runtime_legacy_tool (
    tool_name varchar(256) not null primary key
);
create table if not exists agent_runtime_migration_marker (
    marker_key varchar(64) not null primary key
);
insert into agent_runtime_legacy_tool (tool_name)
select distinct t.local_tool_name from mcp_tool t
where t.local_tool_name is not null
  and not exists (select 1 from agent_runtime_migration_marker where marker_key = 'legacy-tool-capture-v1')
  and not exists (select 1 from agent_runtime_legacy_tool l where l.tool_name = t.local_tool_name);
insert into agent_runtime_migration_marker (marker_key)
select 'legacy-tool-capture-v1'
where not exists (select 1 from agent_runtime_migration_marker where marker_key = 'legacy-tool-capture-v1');
