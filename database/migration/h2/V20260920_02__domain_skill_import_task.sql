create table if not exists ds_domain_skill_import_task (
    created_at timestamp(6) with time zone not null,
    started_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone not null,
    id varchar(64) not null,
    owner_id varchar(64) not null,
    tenant_id varchar(64) not null,
    skill_id varchar(64),
    import_type varchar(24) not null,
    status varchar(24) not null,
    category varchar(120) not null,
    requested_name varchar(200),
    source_reference varchar(2000),
    error_message varchar(2000),
    primary key (id)
);

create index if not exists idx_domain_skill_import_task_tenant_status
    on ds_domain_skill_import_task (tenant_id, status, updated_at);
