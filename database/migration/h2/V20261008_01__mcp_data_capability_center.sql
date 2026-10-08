-- Apply once before deploying the modular data capability center.
create table mcp_data_capability (
        updated_at timestamp(6) with time zone not null,
        code varchar(100) not null,
        definition_json clob not null,
        type enum ('GRAPH','RELATIONAL','TRADING_CALENDAR','TRINO','UNSTRUCTURED') not null,
        primary key (code)
    );

create table mcp_data_capability_execution (
        preview boolean not null,
        duration_ms bigint not null,
        finished_at timestamp(6) with time zone,
        started_at timestamp(6) with time zone not null,
        status varchar(20) not null,
        id varchar(36) not null,
        capability_code varchar(100) not null,
        error varchar(2000),
        result_json clob,
        primary key (id)
    );

create table mcp_data_capability_import (
        dry_run boolean not null,
        failed integer not null,
        succeeded integer not null,
        created_at timestamp(6) with time zone not null,
        id varchar(36) not null,
        results_json clob not null,
        publication_error varchar(2000),
        primary key (id)
    );

create table mcp_market_trading_day (
        date date not null,
        trading boolean not null,
        market varchar(32) not null,
        id varchar(80) not null,
        holiday varchar(500),
        primary key (id),
        unique (market, date)
    );

create index idx_mcp_data_capability_execution on mcp_data_capability_execution (capability_code, started_at);
