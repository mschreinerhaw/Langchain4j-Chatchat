# JDBC driver directory

Put external JDBC driver jars in this directory before starting the MCP server.

Examples:

```text
lib/mysql-connector-j-8.x.x.jar
lib/postgresql-42.x.x.jar
lib/ojdbc11.jar
lib/mssql-jdbc-12.x.x.jre11.jar
```

The `database_query` tool scans `./lib` by default. Override the path with:

```text
CHAT_TOOLS_DATABASE_QUERY_DRIVER_LIB_PATH=./lib
```
