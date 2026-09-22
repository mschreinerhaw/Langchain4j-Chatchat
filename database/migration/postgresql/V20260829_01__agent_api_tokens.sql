create table agent_api_token (
    id varchar(64) not null primary key,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    username varchar(64) not null,
    display_name varchar(128) not null,
    token_name varchar(128) not null,
    token_hash varchar(64) not null unique,
    token_preview varchar(32) not null,
    status varchar(32) not null,
    expires_at timestamp(6) with time zone,
    last_used_at timestamp(6) with time zone,
    last_used_ip varchar(128),
    last_used_path varchar(512),
    used_count bigint default 0 not null,
    created_by varchar(64) not null,
    created_by_name varchar(128) not null,
    revoked_at timestamp(6) with time zone,
    revoked_by varchar(64),
    rotated_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create index idx_agent_api_token_user on agent_api_token (tenant_id, user_id);
create index idx_agent_api_token_status on agent_api_token (status, expires_at);
