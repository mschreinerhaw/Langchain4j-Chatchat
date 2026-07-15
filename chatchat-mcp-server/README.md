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

## Web Search anti-blocking controls

The built-in `web_search` tool supports Playwright browser rendering, browser-like request headers, proxy pools, IP rotation, retry-on-block, QPS/concurrency/day limits, isolated cookies, allow-list checks, and request audit logs.

Example proxy pool:

```yaml
chatchat:
  tools:
    web-search:
      browser:
        enabled: true
        navigation-timeout-ms: 15000
        accept-language: en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7
        referer: https://www.bing.com/
      proxy-pool:
        enabled: true
        default-pool: search
        proxies:
          - id: search-http-1
            pool: search
            type: HTTP
            host: 10.0.0.10
            port: 8080
          - id: search-socks-1
            pool: search
            type: SOCKS5
            host: 10.0.0.11
            port: 1080
      rate-limit:
        max-concurrency: 5
        qps: 1.0
        daily-limit: 1000
      cookie:
        enabled: true
        isolation: proxy_task
        persist: true
      site-search:
        enabled: true
        max-pages-to-inspect: 3
        max-secondary-pages: 3
        max-links-per-page: 5
      allow-list:
        enabled: true
        domains: [bing.com, duckduckgo.com, reuters.com, bloomberg.com]
```

When `browser.enabled` is true, `web_search` first tries Playwright Chromium rendering (`page.navigate`, `NETWORKIDLE`, `page.content`) and falls back to the original HTTP fetcher if browser rendering is unavailable or fails.

`web_crawler`, `crawl_url`, and browser-assisted site intelligence use the same compatibility policy. If the Playwright browser cache is missing, Chromium cannot start, or browser navigation fails, page retrieval continues through the Java HTTP/JSoup implementation. Set `CHAT_WEB_CRAWLER_BROWSER_FALLBACK_TO_JAVA=false` only when strict browser-only execution is required. Page-result cache read/write failures are treated as cache misses and do not stop retrieval.

Playwright browser binaries can be pre-downloaded into a fixed directory instead of using the OS default cache. The MCP package defaults to `playwright-browsers`; when this directory contains `windows` and `linux` subdirectories, the runtime automatically selects the current OS subdirectory. Override it in `config/env.local` when packages need a different absolute path:

```properties
PLAYWRIGHT_BROWSERS_PATH=C:/chatchat/playwright-browsers
```

```properties
PLAYWRIGHT_BROWSERS_PATH=/opt/chatchat/playwright-browsers
```

Use the same path while downloading the browser bundle:

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = "C:/chatchat/playwright-browsers/windows"
mvn -pl chatchat-tools -am exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
```

```sh
export PLAYWRIGHT_BROWSERS_PATH=/opt/chatchat/playwright-browsers/linux
mvn -pl chatchat-tools -am exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

The selected OS subdirectory must contain the browser builds required by the Playwright Java version in the parent `pom.xml`. After upgrading Playwright, re-run the install command so build folders such as `chromium-*`, `chromium_headless_shell-*`, `ffmpeg-*`, and `winldd-*` match the runtime version. If Linux bundles are extracted on Windows, run `playwright-browsers/fix-linux-permissions.sh` once after copying them to Linux.

`web_search` only launches Playwright Chromium. When the selected cache directory already contains a Chromium build, the runtime sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` for the Playwright child process so Playwright does not try to backfill unused browser builds such as Firefox or WebKit. To force the same behavior even before the cache is checked, add this to `config/env.local`:

```properties
CHAT_WEB_SEARCH_BROWSER_SKIP_DOWNLOAD=true
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
```

When `site-search.enabled` is true, `web_search` inspects top result pages for search forms, submits the original keyword through detected search inputs, and merges same-domain secondary result links back into `results` and `reference_urls`. This helps securities exchange and market-data websites whose useful pages only appear after an in-page search.

Each request logs keyword, phase, target domain, proxy id, status code, duration, and failure reason. When `audit.include-in-result` is true, the MCP response also includes `web_search_audit`.

## Financial evidence tool contract

Agents should call `retrieve_financial_evidence` for finance-backed answers such as listed-company disclosures, filings, announcements, securities-market facts, and finance source evidence. It wraps search, crawl, cleanup, chunking, reranking, and contract normalization, then returns only the compact evidence package intended for LLM reasoning.

For non-financial information retrieval, agents should compose `web_search`, `site_intelligence_resolver`, `generic_web_site_search`, and `web_crawler` directly instead of using the financial evidence chain.

```json
{
  "schema_version": "evidence_contract_v1",
  "query": "...",
  "mode": "fast",
  "evidence_chunks": [
    {
      "chunk_id": "web-1",
      "content": "...",
      "score": 0.82,
      "source_url": "https://example.com/page",
      "domain": "example.com",
      "citations": [
        {
          "chunk_id": "web-1",
          "url": "https://example.com/page",
          "confidence": 0.82
        }
      ]
    }
  ],
  "citations": [],
  "reference_urls": []
}
```

`search_and_extract` remains registered as a debug/compatibility tool and returns raw search results, crawled pages, raw evidence, rerank metadata, and observability fields. With the default `expose-agent-compatible-only=true`, only the compact `retrieve_financial_evidence` tool is exposed to agents for the financial evidence workflow.
