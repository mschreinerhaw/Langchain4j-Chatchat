-- Apply once before deploying the modular data capability center.
create table mcp_data_capability (
        updated_at datetime(6) not null,
        code varchar(100) not null,
        definition_json longtext not null,
        type enum ('GRAPH','RELATIONAL','TRADING_CALENDAR','TRINO','UNSTRUCTURED') not null,
        primary key (code)
    ) engine=InnoDB;

create table mcp_data_capability_execution (
        preview bit not null,
        duration_ms bigint not null,
        finished_at datetime(6),
        started_at datetime(6) not null,
        status varchar(20) not null,
        id varchar(36) not null,
        capability_code varchar(100) not null,
        error varchar(2000),
        result_json longtext,
        primary key (id)
    ) engine=InnoDB;

create table mcp_data_capability_import (
        dry_run bit not null,
        failed integer not null,
        succeeded integer not null,
        created_at datetime(6) not null,
        id varchar(36) not null,
        results_json longtext not null,
        publication_error varchar(2000),
        primary key (id)
    ) engine=InnoDB;

create table mcp_market_trading_day (
        date date not null,
        trading bit not null,
        market varchar(32) not null,
        id varchar(80) not null,
        holiday varchar(500),
        primary key (id)
    ) engine=InnoDB;

create index idx_mcp_data_capability_execution on mcp_data_capability_execution (capability_code, started_at);

alter table mcp_market_trading_day
       add constraint UKak5dcqxc34nq15mk5ds510gse unique (market, date);
