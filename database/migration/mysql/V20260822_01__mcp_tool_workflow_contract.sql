alter table mcp_tool
    modify input_schema_json longtext,
    modify output_schema_json longtext;

create table if not exists mcp_tool_workflow_contract (
    lock_version bigint not null,
    contract_version bigint not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    published_at datetime(6),
    id varchar(64) not null,
    tool_id varchar(64) not null,
    schema_version varchar(64) not null,
    workflow_role varchar(32) not null,
    protocol_family varchar(64),
    input_envelope varchar(32),
    status varchar(16) not null,
    contract_checksum varchar(64) not null,
    published_by varchar(128),
    input_schema_json longtext,
    output_schema_json longtext,
    extensions_json longtext,
    active_tool_id varchar(64) generated always as
        (case when status = 'ACTIVE' then tool_id else null end) stored,
    primary key (id),
    constraint uk_mcp_tool_contract_version unique (tool_id, contract_version),
    constraint uk_mcp_tool_contract_single_active unique (active_tool_id),
    constraint fk_mcp_tool_contract_tool foreign key (tool_id) references mcp_tool(id),
    index idx_mcp_tool_contract_active (tool_id, status),
    index idx_mcp_tool_contract_checksum (contract_checksum)
) engine=InnoDB;
