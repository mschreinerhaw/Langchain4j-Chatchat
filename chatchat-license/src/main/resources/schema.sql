CREATE TABLE IF NOT EXISTS license_issue_audit (
    id VARCHAR(36) PRIMARY KEY,
    license_no VARCHAR(128) NOT NULL,
    customer_code VARCHAR(128),
    product VARCHAR(64) NOT NULL,
    edition VARCHAR(64),
    server_id VARCHAR(128) NOT NULL,
    max_users INTEGER,
    max_agents INTEGER,
    modules_json CLOB NOT NULL,
    issued_date DATE,
    expire_date DATE NOT NULL,
    key_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    issued_by VARCHAR(128) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    download_count INTEGER NOT NULL DEFAULT 0,
    last_downloaded_at TIMESTAMP,
    document_sha256 VARCHAR(64) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_license_audit_issued_at ON license_issue_audit (issued_at);
CREATE INDEX IF NOT EXISTS idx_license_audit_license_no ON license_issue_audit (license_no);
CREATE INDEX IF NOT EXISTS idx_license_audit_status ON license_issue_audit (status);
