create table if not exists ds_domain_skill_source (
    created_at timestamp(6) with time zone not null,
    id varchar(64) not null,
    skill_id varchar(64) not null,
    tenant_id varchar(64) not null,
    source_type varchar(32) not null,
    original_hash varchar(64) not null,
    original_file_name varchar(300),
    source_reference varchar(2000),
    original_artifact BLOB not null,
    parsed_document_json LONGTEXT not null,
    primary key (id)
);

create index if not exists idx_domain_skill_source_skill
    on ds_domain_skill_source (tenant_id, skill_id, created_at);

create table if not exists ds_domain_skill_compilation (
    created_at timestamp(6) with time zone not null,
    id varchar(64) not null,
    skill_id varchar(64) not null,
    source_id varchar(64) not null,
    tenant_id varchar(64) not null,
    compilation_mode varchar(40) not null,
    ir_schema_version varchar(48) not null,
    compiler_version varchar(64) not null,
    compiler_model varchar(200),
    skill_ir_json LONGTEXT not null,
    primary key (id)
);

create index if not exists idx_domain_skill_compilation_skill
    on ds_domain_skill_compilation (tenant_id, skill_id, created_at);
