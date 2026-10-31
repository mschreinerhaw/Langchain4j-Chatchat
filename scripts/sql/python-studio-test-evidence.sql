-- Additive API database migration, applied before Hibernate schema validation.
-- Old successful flags have no bound evidence; republishing requires a new trial.
ALTER TABLE ds_python_script ADD COLUMN IF NOT EXISTS last_test_evidence_json TEXT;
