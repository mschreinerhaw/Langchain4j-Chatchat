create table if not exists ds_domain_skill_source (
    created_at datetime(6) not null,
    id varchar(64) not null,
    skill_id varchar(64) not null,
    tenant_id varchar(64) not null,
    source_type varchar(32) not null,
    original_hash varchar(64) not null,
    original_file_name varchar(300),
    source_reference varchar(2000),
    original_artifact LONGBLOB not null,
    parsed_document_json LONGTEXT not null,
    primary key (id),
    index idx_domain_skill_source_skill (tenant_id, skill_id, created_at)
) engine=InnoDB;

create table if not exists ds_domain_skill_compilation (
    created_at datetime(6) not null,
    id varchar(64) not null,
    skill_id varchar(64) not null,
    source_id varchar(64) not null,
    tenant_id varchar(64) not null,
    compilation_mode varchar(40) not null,
    ir_schema_version varchar(48) not null,
    compiler_version varchar(64) not null,
    compiler_model varchar(200),
    skill_ir_json LONGTEXT not null,
    primary key (id),
    index idx_domain_skill_compilation_skill (tenant_id, skill_id, created_at)
) engine=InnoDB;
