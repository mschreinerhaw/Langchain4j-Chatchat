-- Versioned migration; NULL preserves existing shared grants.
alter table resource_grant add column agent_id varchar(128) null;
create index idx_resource_grant_agent on resource_grant (tenant_id, agent_id, principal_id);
