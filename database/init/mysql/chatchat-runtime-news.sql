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
