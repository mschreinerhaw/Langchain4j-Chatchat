-- MySQL 8; apply once after recording the migration in the site's deployment ledger.
-- DDL commits independently on MySQL. Do not apply to a PostgreSQL configuration database.
ALTER TABLE mcp_sql_template
 ADD COLUMN publication_group_id varchar(64) NULL,
 ADD COLUMN publication_version integer NULL,
 ADD COLUMN author_tenant_id varchar(64) NULL,
 ADD COLUMN author_id varchar(64) NULL,
 ADD COLUMN publication_state varchar(24) NULL,
 ADD COLUMN publication_lock bigint DEFAULT 0,
 ADD COLUMN authoring_source_json longtext NULL,
 ADD COLUMN sql_test_evidence_json longtext NULL,
 ADD COLUMN released_capability_id varchar(64) NULL,
 ADD COLUMN draft_request_digest varchar(64) NULL,
 ADD UNIQUE INDEX ux_sql_template_publication_revision(publication_group_id,publication_version);
