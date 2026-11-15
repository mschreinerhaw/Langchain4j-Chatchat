-- Retained non-JPA category tables used by existing MCP compatibility clients. No data copied.

create table if not exists mcp_api_service_category (
        enabled boolean not null,
        sort_order integer not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        id varchar(64) not null,
        code varchar(128) not null unique,
        name varchar(200) not null,
        description varchar(1000),
        keywords_json text,
        primary key (id)
    );

create table if not exists mcp_data_query_category (
        enabled boolean not null,
        sort_order integer not null,
        created_at timestamp(6) with time zone not null,
        updated_at timestamp(6) with time zone not null,
        id varchar(64) not null,
        code varchar(128) not null unique,
        domain varchar(128) not null,
        name varchar(200) not null,
        description varchar(1000),
        keywords_json text,
        primary key (id)
    );
