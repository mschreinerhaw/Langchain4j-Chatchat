-- NULL agent_id preserves existing shared grants; non-NULL grants apply only to that Agent.
alter table resource_grant add column if not exists agent_id varchar(128);
create index if not exists idx_resource_grant_agent on resource_grant (tenant_id, agent_id, principal_id);
