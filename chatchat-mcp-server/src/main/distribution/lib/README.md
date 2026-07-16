# lib directory

The release package keeps runtime files under `lib`:

```text
lib/app/chatchat-mcp-server.jar
lib/plugins/
lib/drivers/
lib/drivers/dm/
lib/drivers/kingbase/
lib/drivers/oceanbase/
lib/drivers/tdsql/
lib/drivers/tidb/
lib/drivers/inceptor/
```

Put optional shared dependency jars in `lib/plugins/`. Jars in this directory are added to the
MCP application class path by Spring Boot `PropertiesLauncher` at startup. This is appropriate
for reusable SDKs and dependency jars needed by an external JDBC driver. Restart the MCP server
after adding, replacing, or removing a plugin jar; rebuilding the application is not required.

Keep actual JDBC driver jars in `lib/drivers/{databaseType}` so driver isolation and
`reload_drivers=true` continue to work. A dependency intentionally shared by multiple drivers may
be placed in `lib/plugins/`.

Override or extend the plugin path when needed:

```text
CHATCHAT_MCP_PLUGIN_PATH=./lib/plugins
CHATCHAT_MCP_ADDITIONAL_LOADER_PATH=/opt/chatchat/shared-libs
```

Put external JDBC driver jars in `lib/drivers/{databaseType}` before starting the MCP server.
The MCP server first scans the matching database-type subdirectory, then falls back to
`lib/drivers` when the subdirectory does not exist or is empty.

Examples:

```text
lib/drivers/mysql/mysql-connector-j-8.x.x.jar
lib/drivers/postgresql/postgresql-42.x.x.jar
lib/drivers/oracle/ojdbc11.jar
lib/drivers/sqlserver/mssql-jdbc-12.x.x.jre11.jar
lib/drivers/dm/DmJdbcDriver18-8.x.x.jar
lib/drivers/tdsql/mysql-connector-j-8.x.x.jar
lib/drivers/tidb/mysql-connector-j-8.x.x.jar
lib/drivers/inceptor/inceptor-sdk-studio-x.x.jar
lib/drivers/inceptor/calcite-avatica-core-x.x.jar
```

Keep all dependency jars required by one driver in the same database-type directory. This
prevents a driver such as Inceptor from requiring Calcite Avatica while testing an unrelated
Dameng or Kingbase datasource.

The JDBC driver root directory is `./lib/drivers` by default. Override it with:

```text
CHAT_TOOLS_DATABASE_QUERY_DRIVER_LIB_PATH=./lib/drivers
```
