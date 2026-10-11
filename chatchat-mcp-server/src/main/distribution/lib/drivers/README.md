# JDBC drivers

Neo4j, OpenSearch and Elasticsearch do not use this directory. Their official clients are
bundled with MCP and managed by `NativeDatabaseClient`. Choose `neo4j-native`,
`opensearch-native` or `elasticsearch-native` in database assets. Neo4j requires a Bolt/neo4j
URI (for example `bolt://host:7687`); search engines use HTTP(S) base addresses.
Legacy search `-http` identifiers are accepted and normalized when saved. Legacy Neo4j
HTTP addresses must be migrated to the actual Bolt endpoint; custom ports are never guessed.

Prefer putting external JDBC driver jars in a database-type subdirectory:

```text
lib/drivers/dm/
lib/drivers/kingbase/
lib/drivers/oceanbase/
lib/drivers/tdsql/
lib/drivers/tidb/
lib/drivers/inceptor/
```

When a SQL datasource has `databaseType=dm`, the MCP server first scans `lib/drivers/dm`.
If that directory does not exist or has no jar files, it falls back to scanning `lib/drivers`.

Keep all dependency jars required by one driver in the same subdirectory. For example, if an
Inceptor driver requires Calcite Avatica, put both the Inceptor jar and Avatica jars under
`lib/drivers/inceptor/` so they do not affect unrelated databases.

If a jar is added after startup, invoke `database_query` once with `reload_drivers=true`, or
restart the MCP server.
