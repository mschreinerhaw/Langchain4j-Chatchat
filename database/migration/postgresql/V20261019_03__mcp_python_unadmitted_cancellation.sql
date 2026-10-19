-- Serialize missing-operation cancellation and future admission with the existing task fence.
create table if not exists mcp_python_managed_cancellation (
 id varchar(64) primary key,
 operation_id varchar(128) not null,
 fencing_token bigint not null,
 receipt_json text not null,
 manifest_json text not null
);
