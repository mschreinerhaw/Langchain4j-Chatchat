# ChatChat News Runtime Release

## Directory layout

- `bin/`: Linux and Windows start/stop/restart/status scripts
- `config/`: external Spring Boot configuration, environment file and database scripts
- `data/`: persistent local runtime data
- `lib/`: executable application JAR
- `logs/`: rolling application logs and bounded startup diagnostics
- `run/`: PID file

## First production start

1. Install Java 17 or later and configure `JAVA_HOME` when Java is not on `PATH`.
2. Choose `h2` or `mysql`. H2 is the default; for MySQL, initialize it with `config/sql/mysql/chatchat-runtime-news.sql`.
3. Edit `config/application-h2.yml` or `config/application-mysql.yml`, then edit `config/application.yml` for OpenSearch, internal account and optional Embedding settings.
4. News Runtime and MCP initially use the same bootstrap internal account so the release can start directly. Before exposing a production service, replace the bootstrap secret on both services with the same `ENC(...)` ciphertext and external key file. News Runtime refuses to start with an empty internal secret.
5. Start with `bin/start.sh` on Linux or `bin/start.bat` on Windows.

`config/application.yml` deliberately contains concrete values rather than environment-variable placeholders. `config/env.properties`
only controls JVM memory and optional command-line arguments.

## Log retention and compression

Production logging is configured by `config/logback-spring.xml`. The active log is
`logs/chatchat-runtime-news.log`; it rolls at midnight or when it reaches 100 MB. Rolled files are gzip-compressed
under `logs/archive/`, retained for 14 days, and capped at 2 GB in total. Expired files are also cleaned during
application startup. The production logger does not duplicate application logs to stdout/stderr.

The start scripts pass the Logback file and log directory as absolute locations, so logging does not depend on the
caller's working directory. `logs/chatchat-runtime-news-startup.log` only captures the Spring banner or failures that
occur before Logback starts and is overwritten on every start; it is not the application log. A continuously growing
`chatchat-runtime-news.out` belongs to an older release script and can be removed after the old process is stopped and
the new release has been verified.

The limits can be overridden through JVM options in `config/env.properties`, for example:

```text
JAVA_OPTS=-Xms512m -Xmx2g -DLOG_MAX_FILE_SIZE=200MB -DLOG_MAX_HISTORY=30 -DLOG_TOTAL_SIZE_CAP=5GB
```

Linux scripts may need executable permission after extracting a ZIP: `chmod +x bin/*.sh`.
Use the `.tar.gz` release on Linux to preserve executable file permissions.

## Commands

```text
bin/start.sh       bin/start.bat
bin/status.sh      bin/status.bat
bin/restart.sh     bin/restart.bat
bin/stop.sh        bin/stop.bat
```

Additional Spring Boot arguments can be passed to `start`/`restart`, for example:

```bash
bin/start.sh --spring.profiles.active=h2 --server.port=18091
bin/start.sh --spring.profiles.active=mysql
```
