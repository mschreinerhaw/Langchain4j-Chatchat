-- Nullable preserves the legacy enabled behavior. Originals and compiled IR remain separate.
ALTER TABLE ds_domain_skill ADD COLUMN IF NOT EXISTS runtime_compilation_enabled BOOLEAN;
ALTER TABLE ds_domain_skill ADD COLUMN IF NOT EXISTS compilation_status VARCHAR(24);
ALTER TABLE ds_domain_skill ADD COLUMN IF NOT EXISTS compilation_error VARCHAR(2000);
ALTER TABLE ds_domain_skill ADD COLUMN IF NOT EXISTS native_source_id VARCHAR(64);
ALTER TABLE ds_domain_skill ADD COLUMN IF NOT EXISTS native_metadata_json TEXT;
