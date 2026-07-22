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

Build the standalone release package from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-mcp-server.ps1
```

Or run Maven directly from the repository root:

```powershell
mvn -pl chatchat-mcp-server -am -DskipTests package
```

Keep `-am` in the Maven command so Maven rebuilds `chatchat-common`, `chatchat-agents`, and `chatchat-tools` with the MCP server. Omitting it can package an older local `chatchat-common` snapshot and cause runtime `ClassNotFoundException` errors.

The package is generated at:

```text
chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT-release.zip
```

Package layout:

```text
bin/start.bat
bin/start.ps1
bin/start.sh
bin/stop.bat
bin/stop.ps1
bin/stop.sh
bin/restart.bat
bin/restart.ps1
bin/restart.sh
bin/status.bat
bin/status.ps1
bin/status.sh
config/application.yml
logs/
lib/app/chatchat-mcp-server.jar
lib/plugins/
lib/drivers/
```

Start, stop, restart, and check status:

```powershell
.\bin\start.bat
.\bin\status.bat
.\bin\stop.bat
.\bin\restart.bat
```

```bash
./bin/start.sh
./bin/status.sh
./bin/stop.sh
./bin/restart.sh
```

Runtime logs are written to `logs/`. Override JVM options with `JAVA_OPTS` and extra Spring Boot arguments with `APP_ARGS`.

Put external JDBC driver jars into `lib/drivers/`. The `database_query` tool can then query an external database by passing `jdbc_url`, `username`, `password`, and optionally `driver_class`.

Put optional shared SDKs and dependency jars into `lib/plugins/`. The startup scripts add this
directory to the MCP application class path, so adding a missing driver dependency no longer
requires rebuilding the MCP server. Restart the MCP server after changing plugin jars. Keep the
driver jar itself under `lib/drivers/{databaseType}`; use `lib/plugins/` for dependencies that must
be visible to the application or shared by multiple drivers.

The default plugin directory can be replaced with `CHATCHAT_MCP_PLUGIN_PATH`, and additional
comma-separated locations can be supplied with `CHATCHAT_MCP_ADDITIONAL_LOADER_PATH`.

If a jar is added after startup, call `database_query` once with `reload_drivers=true` to rescan `lib/drivers/`.

## Tool concurrency governance

The MCP server enforces four concurrency layers before executing a tool:

```text
global MCP requests
tool name
tool asset/runtime level
userId / agentId when provided in arguments
```

Default runtime limits are conservative for resource-heavy tools:

```text
SSH asset tools: 2 concurrent calls per tool/host
SQL datasource tools: 5 concurrent calls per tool/datasource
HTTP tools: 30 concurrent calls
Notification tools: 10 concurrent calls
```

When a limit is reached, calls wait in the configured queue. If the queue is full the MCP call returns `BUSY`; if the wait or execution timeout is exceeded it returns `TIMEOUT`. Tool metadata also exposes `mcp_tool_limit` so Agent Runtime can see the server-side limits.

Key settings live under `chatchat.mcp.server.concurrency`:

```yaml
chatchat:
  mcp:
    server:
      concurrency:
        enabled: true
        global:
          max-concurrency: 64
          queue-size: 128
          queue-timeout-seconds: 5
        runtime-levels:
          ssh:
            max-concurrency: 2
          sql:
            max-concurrency: 5
          http:
            max-concurrency: 30
          notification:
            max-concurrency: 10
        tools:
          ssh_prod_host:
            max-concurrency: 1
            timeout-seconds: 20
            queue-size: 16
```

`max-output-chars`, `retry-attempts`, `failure-threshold`, and `circuit-open-seconds` can be set on defaults, runtime levels, or a specific tool. Keep `retry-attempts` at `0` for SSH/SQL unless the command or query is known to be idempotent.

## OpenSearch search bulkheads

MCP OpenSearch retrieval has separate bounded admission controls for lexical BM25 and vector KNN work. They do not share one large queue: a saturated vector path degrades to BM25, while an exhausted lexical queue returns quickly so the calling asset/template tool can use its existing source-registry fallback. HTTP 429 responses are retried at most once with a short randomized backoff.

```yaml
chatchat:
  mcp:
    lucene:
      open-search:
        search-concurrency:
          enabled: true
          request-timeout-ms: 8000
          retry-429-attempts: 1
          retry-backoff-min-ms: 100
          retry-backoff-max-ms: 300
          lexical:
            max-running: 12
            queue-capacity: 30
            queue-timeout-ms: 1500
          vector:
            max-running: 4
            queue-capacity: 10
            queue-timeout-ms: 500
```

The request timeout above applies to each OpenSearch `_search` request. The independent internet embedding timeout remains controlled by `embedding.request-timeout-ms` and defaults to five minutes.

## Web Search

The MCP exposes one public search tool, `web_search`. MCP Server composes standardized news from `chatchat-runtime-news` with its in-process Market capability and the `financial-data-asset` catalog. The Market module is packaged inside MCP Server and reuses MCP's DataSource, OpenSearch client and credentials; it has no independent server or connection configuration. A `financial_data_asset` result includes its dataset code and governed storage location, and the same tool reads bounded structured observations when `dataset` is supplied. `news_search` and `news_latest` remain internal runtime operations and are not published as MCP tools.

The authoritative write path, dynamic schema rules, database/OpenSearch split, recovery behavior and two-stage Agent read contract are documented in [`chatchat-runtime-market/doc/design.md`](../chatchat-runtime-market/doc/design.md).
