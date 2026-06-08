# lib directory

The release package keeps runtime files under `lib`:

```text
lib/app/chatchat-mcp-server.jar
lib/drivers/
```

Put external JDBC driver jars in `lib/drivers` before starting the MCP server.

Examples:

```text
lib/drivers/mysql-connector-j-8.x.x.jar
lib/drivers/postgresql-42.x.x.jar
lib/drivers/ojdbc11.jar
lib/drivers/mssql-jdbc-12.x.x.jre11.jar
```

The `database_query` tool scans `./lib/drivers` by default. Override the path with:

```text
CHAT_TOOLS_DATABASE_QUERY_DRIVER_LIB_PATH=./lib/drivers
```
