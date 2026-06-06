# ChatChat MCP Server

## H2 database password

The MCP server uses a password-protected H2 file database by default.

Default connection settings:

```text
JDBC URL: jdbc:h2:file:./data/h2/chatchat-mcp-server;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1
Username: sa
Password: chatchat_mcp_h2_pwd
```

Override them in production:

```powershell
$env:CHAT_MCP_DATASOURCE_USERNAME = "sa"
$env:CHAT_MCP_DATASOURCE_PASSWORD = "your_strong_password"
```

H2 Console is disabled by default. Enable it only for local maintenance:

```powershell
$env:CHAT_MCP_H2_CONSOLE_ENABLED = "true"
```

Then open:

```text
http://localhost:8090/h2-console
```

If an existing local H2 database was created with an empty password, update it once before using the new password:

```sql
ALTER USER SA SET PASSWORD 'your_strong_password';
```

For a fresh deployment, just set `CHAT_MCP_DATASOURCE_PASSWORD` before the first startup.

## Release package

Build the standalone release package:

```powershell
mvn -pl chatchat-mcp-server -am -DskipTests package
```

The package is generated at:

```text
chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT-release.zip
```

Package layout:

```text
bin/start.bat
bin/start.sh
config/application.yml
lib/
chatchat-mcp-server.jar
```

Put external JDBC driver jars into `lib/`. The `database_query` tool can then query an external database by passing `jdbc_url`, `username`, `password`, and optionally `driver_class`.

If a jar is added after startup, call `database_query` once with `reload_drivers=true` to rescan `lib/`.
