create table if not exists mcp_python_managed_fence (
 id varchar(64) primary key, tenant_id varchar(128) not null, owner_id varchar(128) not null,
 run_id varchar(128) not null, task_id varchar(128) not null, fencing_token bigint not null
);
create table if not exists mcp_python_managed_operation (
 id varchar(64) primary key, tenant_id varchar(128) not null, owner_id varchar(128) not null,
 run_id varchar(128) not null, task_id varchar(128) not null, operation_id varchar(128) not null,
 attempt_number integer not null, fencing_token bigint not null, request_hash varchar(64) not null,
 environment_id varchar(64) not null, environment_version integer not null,
 image_digest varchar(71) not null, container_name varchar(64) not null,
 workspace_path varchar(2000) not null, code_hash varchar(64) not null,
 deadline_at timestamp(6) not null, timeout_seconds integer not null,
 container_started_at timestamp(6), budget_reservation_ref varchar(256) not null,
 manifest_hash varchar(64), status varchar(32) not null, cancel_requested boolean not null,
 executor_stopped boolean not null, exit_code integer, failure varchar(2000),
 cleanup_failed boolean not null, revision bigint not null,
 created_at timestamp(6) not null, updated_at timestamp(6) not null,
 index idx_mcp_python_managed_pending(status, updated_at)
);
