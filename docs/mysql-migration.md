# H2 to MySQL Migration

Default startup remains H2. Enable MySQL from the startup script when needed; turn migration on only
once when copying existing H2 data.

```bash
bin/start.sh --database=mysql
bin/start.sh --database=h2
```

```bat
bin\start.bat --database=mysql
bin\start.bat --database=h2
```

The short aliases `--mysql` and `--h2` are also supported. `bin/restart.*` forwards the same
database argument to `start.*`.

## MySQL + OpenSearch development startup

For local feature development that must use MySQL and OpenSearch together, run both services with
the `dev,mysql,search-opensearch` profiles.

Runtime API:

```powershell
cd D:\IdeaProjects\LangChain4j-AIChat\Langchain4j-Chatchat\chatchat-api
mvn -DskipTests -Dspring-boot.run.profiles=dev,mysql,search-opensearch spring-boot:run
```

MCP Server:

```powershell
cd D:\IdeaProjects\LangChain4j-AIChat\Langchain4j-Chatchat\chatchat-mcp-server
mvn -DskipTests -Dspring-boot.run.profiles=dev,mysql,search-opensearch spring-boot:run
```

Windows one-click script for starting both services:

```bat
run-chat-api-mcp.bat -Action restart -Database mysql -SearchEngine opensearch
```

Equivalent explicit profile form:

```bat
run-chat-api-mcp.bat -Action restart -Profile dev,mysql,search-opensearch
```

The same combination can be selected from packaged startup scripts:

```bash
bin/start.sh --database=mysql --search-engine=opensearch
```

```bat
bin\start.bat --database=mysql --search-engine=opensearch
```

Development profile isolation:

- API MySQL database: `live_runtime_api`
- MCP MySQL database: `live_runtime_mcp`
- API OpenSearch index: `chatchat_documents_dev`
- MCP OpenSearch index prefix: `chatchat_mcp_dev_`

Other useful development combinations:

```text
dev                            -> H2 + local Lucene
dev,search-opensearch           -> H2 + OpenSearch
dev,mysql,search-lucene         -> MySQL + local Lucene
dev,mysql,search-opensearch     -> MySQL + OpenSearch
```

## Runtime API

Use database `live_runtime_api`.

Use `--database=mysql`. The MySQL URL, driver, username, password, JPA dialect, and
`ddl-auto=update` are defined in `chatchat-api/src/main/resources/application-mysql.yml`
and `packaging/config/application-mysql.yml`. H2 startup uses the default `prod` profile.

To copy existing H2 data once:

```properties
CHATCHAT_DATASOURCE_MIGRATION_ENABLED=true
```

Or pass `--chatchat.datasource-migration.enabled=true` on the startup command. The default source
H2 URL is `jdbc:h2:file:./data/h2/chatchat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1`.

## MCP Server

Use database `live_runtime_mcp`.

Use `--database=mysql`. The MySQL URL, driver, username, password, JPA dialect, and
`ddl-auto=update` are defined in `chatchat-mcp-server/src/main/resources/application-mysql.yml`
and `chatchat-mcp-server/src/main/distribution/config/application-mysql.yml`. H2 startup uses the
default `prod` profile.

To copy existing H2 data once:

```properties
CHATCHAT_DATASOURCE_MIGRATION_ENABLED=true
```

Or pass `--chatchat.datasource-migration.enabled=true` on the startup command. The default source
H2 URL is `jdbc:h2:file:./data/h2/chatchat-mcp-server;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1`.

The migrator copies a table only when the target MySQL table is empty. Set
`CHATCHAT_DATASOURCE_MIGRATION_REPLACE_EXISTING=true` or
`--chatchat.datasource-migration.replace-existing=true` only when the target data can be deleted before copying.
