-- Node-local SQLite schema v1. Additive initialization only; no Docker operations.
CREATE TABLE IF NOT EXISTS plans(id TEXT PRIMARY KEY, actor TEXT, spec TEXT, snapshot TEXT, preview TEXT, expires REAL, used INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, actor TEXT, request_key TEXT UNIQUE, request_hash TEXT, status TEXT, action TEXT, target TEXT, created REAL, updated REAL, result TEXT, error TEXT);
CREATE TABLE IF NOT EXISTS samples(id INTEGER PRIMARY KEY, at REAL, resource TEXT, payload TEXT);
CREATE INDEX IF NOT EXISTS sample_time ON samples(at);
CREATE INDEX IF NOT EXISTS sample_resource ON samples(resource,at);
PRAGMA user_version=1;
