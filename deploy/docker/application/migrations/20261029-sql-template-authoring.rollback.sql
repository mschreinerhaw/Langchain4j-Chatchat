-- Non-destructive application rollback, portable to PostgreSQL/MySQL.
-- Drain publication/test requests first. Keep every SQL snapshot and test receipt.
-- Only publications produced by this authoring lifecycle are taken offline.
UPDATE mcp_tool_release SET state='OFFLINE'
 WHERE kind='TEMPLATE_CAPABILITY' AND id IN
 (SELECT released_capability_id FROM mcp_sql_template WHERE publication_group_id IS NOT NULL);
UPDATE mcp_sql_template SET enabled=false WHERE publication_group_id IS NOT NULL;
-- Retain additive columns/index/history. Dropping them would destroy authored versions.
