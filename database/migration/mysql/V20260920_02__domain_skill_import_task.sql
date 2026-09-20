create table if not exists ds_domain_skill_import_task (
    created_at datetime(6) not null,
    started_at datetime(6),
    completed_at datetime(6),
    updated_at datetime(6) not null,
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
    primary key (id),
    index idx_domain_skill_import_task_tenant_status (tenant_id, status, updated_at)
) engine=InnoDB;
