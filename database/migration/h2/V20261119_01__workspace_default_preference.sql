-- Legacy values have no explicit-choice marker and inherit the current platform default.
ALTER TABLE workspace_user_preference ADD COLUMN IF NOT EXISTS default_workspace_explicit BOOLEAN;
