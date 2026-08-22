alter table mcp_tool alter column input_schema_json TEXT;
alter table mcp_tool alter column output_schema_json TEXT;

create table if not exists mcp_tool_workflow_contract (
    lock_version bigint not null,
    contract_version bigint not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    published_at timestamp(6) with time zone,
    id varchar(64) not null,
    tool_id varchar(64) not null,
    schema_version varchar(64) not null,
    workflow_role varchar(32) not null,
    protocol_family varchar(64),
    input_envelope varchar(32),
    status varchar(16) not null,
    contract_checksum varchar(64) not null,
    published_by varchar(128),
    input_schema_json TEXT,
    output_schema_json TEXT,
    extensions_json TEXT,
    primary key (id),
    constraint uk_mcp_tool_contract_version unique (tool_id, contract_version),
    constraint fk_mcp_tool_contract_tool foreign key (tool_id) references mcp_tool(id)
);

create index if not exists idx_mcp_tool_contract_active
    on mcp_tool_workflow_contract(tool_id, status);
create index if not exists idx_mcp_tool_contract_checksum
    on mcp_tool_workflow_contract(contract_checksum);
