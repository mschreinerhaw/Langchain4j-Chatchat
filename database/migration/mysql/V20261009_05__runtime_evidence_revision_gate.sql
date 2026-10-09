-- Runtime OS evidence facts, process snapshots and durable revision gate.
CREATE TABLE IF NOT EXISTS runtime_evidence_partition (
 id varchar(64) PRIMARY KEY,
 tenant_id varchar(128) NOT NULL,
 user_id varchar(128) NOT NULL,
 run_id varchar(128) NOT NULL,
 revision bigint NOT NULL DEFAULT 0,
 analysis_rounds integer NOT NULL DEFAULT 0,
 max_rounds integer NOT NULL DEFAULT 3,
 observation_revision bigint NOT NULL DEFAULT 0,
 consumed_revision bigint NOT NULL DEFAULT 0,
 observation_hash varchar(64),
 analysis_action varchar(16),
 analysis_reason varchar(64),
 terminal boolean NOT NULL DEFAULT false,
 CHECK (analysis_rounds >= 0 AND analysis_rounds <= max_rounds AND max_rounds BETWEEN 1 AND 3 AND consumed_revision <= observation_revision)
);
CREATE TABLE IF NOT EXISTS runtime_evidence_entry (
 id varchar(64) PRIMARY KEY,
 partition_id varchar(64) NOT NULL,
 entry_kind varchar(16) NOT NULL,
 entry_id varchar(256) NOT NULL,
 revision bigint NOT NULL,
 stored_at bigint NOT NULL,
 occurred_at bigint NOT NULL,
 evidence_type varchar(128),
 source_node varchar(256),
 sha256 varchar(64) NOT NULL,
 record_json longtext NOT NULL,
 lineage_json longtext
);
CREATE INDEX idx_runtime_evidence_partition ON runtime_evidence_entry(partition_id,entry_kind,stored_at);
