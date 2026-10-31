-- Run once through the migration runner; runtime metadata migrator checks column existence.
ALTER TABLE ds_domain_skill ADD COLUMN runtime_compilation_enabled BOOLEAN;
ALTER TABLE ds_domain_skill ADD COLUMN compilation_status VARCHAR(24);
ALTER TABLE ds_domain_skill ADD COLUMN compilation_error VARCHAR(2000);
ALTER TABLE ds_domain_skill ADD COLUMN native_source_id VARCHAR(64);
ALTER TABLE ds_domain_skill ADD COLUMN native_metadata_json LONGTEXT;
