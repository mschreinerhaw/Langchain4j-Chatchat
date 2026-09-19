-- Standalone chatchat-runtime-news schema (MySQL 8+)
create table if not exists news_source (
  id bigint not null auto_increment,
  capability_id bigint not null default 1,
  source_code varchar(64) not null,
  source_name varchar(128) not null,
  source_type varchar(32) not null,
  entry_url varchar(2000) not null,
  allowed_domain varchar(255), schedule_cron varchar(128), enabled bit not null,
  configuration_json varchar(8000), last_cursor varchar(2000),
  last_collected_at datetime(6), created_at datetime(6) not null, updated_at datetime(6) not null,
  primary key (id), unique key uk_news_source_code (source_code)
) engine=InnoDB default charset=utf8mb4;

create table if not exists news_source_rule (
  id bigint not null auto_increment, source_id bigint not null,
  list_selector varchar(1000), link_selector varchar(1000), title_selector varchar(1000),
  content_selector varchar(1000), author_selector varchar(1000), publish_time_selector varchar(1000),
  url_pattern varchar(1000), updated_at datetime(6) not null,
  primary key (id), unique key uk_news_source_rule (source_id)
) engine=InnoDB default charset=utf8mb4;

create table if not exists news_collect_record (
  id bigint not null auto_increment, source_id bigint not null, source_url varchar(2000) not null,
  url_hash varchar(64) not null, content_hash varchar(64), publish_time datetime(6),
  collect_status varchar(32) not null, analysis_status varchar(32) not null,
  document_id varchar(128), collected_at datetime(6), error_message varchar(4000),
  primary key (id), unique key uk_news_url_hash (url_hash),
  key idx_news_collect_record_collected_at (collected_at)
) engine=InnoDB default charset=utf8mb4;

create table if not exists news_analysis_task (
  id bigint not null auto_increment, document_id varchar(128) not null, source_id bigint not null,
  status varchar(32) not null, created_at datetime(6) not null, updated_at datetime(6) not null,
  error_message varchar(4000), primary key (id), unique key uk_news_analysis_document (document_id)
) engine=InnoDB default charset=utf8mb4;

-- Governed financial-market storage. Dataset-specific business columns are added by
-- FinancialDataStore when the first observation arrives. Weekly archive tables are
-- deliberately created lazily after those dynamic columns are known.
create table if not exists market_asset_catalog (
  id bigint not null auto_increment,
  dataset_code varchar(64) not null, asset_name varchar(160) not null,
  business_description varchar(4000) not null, business_tags_json varchar(4000) not null,
  database_name varchar(128) not null, table_name varchar(128) not null,
  update_frequency varchar(128), source_names_json varchar(4000),
  last_observation_date date, last_collected_at datetime(6), archive_table_name varchar(128),
  hot_retention_days integer, archive_retention_days integer, history_granularity varchar(64),
  created_at datetime(6) not null, updated_at datetime(6) not null,
  primary key (id), unique key uk_market_asset_code (dataset_code)
) engine=InnoDB default charset=utf8mb4;

create table if not exists data_schema_registry (
  id bigint not null auto_increment,
  dataset_code varchar(64) not null, table_name varchar(128) not null,
  field_name varchar(128) not null, source_field varchar(128) not null,
  field_type varchar(32) not null, business_description varchar(1000),
  schema_version integer not null, created_at datetime(6) not null, updated_at datetime(6) not null,
  primary key (id), unique key uk_data_schema_field (dataset_code, field_name),
  key idx_data_schema_dataset (dataset_code)
) engine=InnoDB default charset=utf8mb4;

create table if not exists security_master (
  id bigint not null auto_increment,
  exchange_code varchar(8) not null, security_code varchar(16) not null,
  security_name varchar(160) not null, security_full_name varchar(300),
  security_type varchar(32) not null, board_name varchar(64), listing_date date,
  industry_name varchar(160), source_url varchar(1000) not null,
  source_refreshed_at datetime(6) not null, created_at datetime(6) not null, updated_at datetime(6) not null,
  primary key (id), unique key uk_security_master_code (exchange_code, security_code),
  key idx_security_master_code (security_code), key idx_security_master_name (security_name)
) engine=InnoDB default charset=utf8mb4;

create table if not exists market_quote_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_market_quote_daily_record (record_key, collected_date),
  key idx_market_quote_daily_collected_date (collected_date, observation_date),
  key idx_market_quote_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists stock_valuation_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_stock_valuation_daily_record (record_key, collected_date),
  key idx_stock_valuation_daily_collected_date (collected_date, observation_date),
  key idx_stock_valuation_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists index_valuation_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_index_valuation_daily_record (record_key, collected_date),
  key idx_index_valuation_daily_collected_date (collected_date, observation_date),
  key idx_index_valuation_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists margin_trade_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_margin_trade_daily_record (record_key, collected_date),
  key idx_margin_trade_daily_collected_date (collected_date, observation_date),
  key idx_margin_trade_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists stock_dividend_event (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_stock_dividend_event_record (record_key, collected_date),
  key idx_stock_dividend_event_collected_date (collected_date, observation_date),
  key idx_stock_dividend_event_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists etf_scale_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_etf_scale_daily_record (record_key, collected_date),
  key idx_etf_scale_daily_collected_date (collected_date, observation_date),
  key idx_etf_scale_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists market_statistics_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_market_statistics_daily_record (record_key, collected_date),
  key idx_market_statistics_daily_collected_date (collected_date, observation_date),
  key idx_market_statistics_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists bond_market_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_bond_market_daily_record (record_key, collected_date),
  key idx_bond_market_daily_collected_date (collected_date, observation_date),
  key idx_bond_market_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists bond_market_overview_monthly (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_bond_market_overview_monthly_record (record_key, collected_date),
  key idx_bond_market_overview_monthly_collected_date (collected_date, observation_date),
  key idx_bond_market_overview_monthly_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists bond_yield_curve_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_bond_yield_curve_daily_record (record_key, collected_date),
  key idx_bond_yield_curve_daily_collected_date (collected_date, observation_date),
  key idx_bond_yield_curve_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists bond_counter_quote_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_bond_counter_quote_daily_record (record_key, collected_date),
  key idx_bond_counter_quote_daily_collected_date (collected_date, observation_date),
  key idx_bond_counter_quote_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists bond_settlement_daily (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_bond_settlement_daily_record (record_key, collected_date),
  key idx_bond_settlement_daily_collected_date (collected_date, observation_date),
  key idx_bond_settlement_daily_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;

create table if not exists bond_collateral_monthly (
  id bigint not null auto_increment, collected_date date not null,
  observation_date date not null, collected_at datetime(6) not null,
  source_id bigint not null, source_code varchar(64) not null,
  source_url varchar(2000) not null, record_key varchar(64) not null,
  payload_json longtext not null,
  primary key (id, collected_date), unique key uk_bond_collateral_monthly_record (record_key, collected_date),
  key idx_bond_collateral_monthly_collected_date (collected_date, observation_date),
  key idx_bond_collateral_monthly_observation_date (observation_date)
) engine=InnoDB default charset=utf8mb4
partition by hash(to_days(collected_date)) partitions 32;
