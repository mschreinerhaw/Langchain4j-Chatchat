# ChatChat News Runtime Release

## Directory layout

- `bin/`: Linux and Windows start/stop/restart/status scripts
- `config/`: external Spring Boot configuration, environment file and database scripts
- `data/`: persistent local runtime data
- `lib/`: executable application JAR
- `logs/`: application, stdout and stderr logs
- `run/`: PID file

## First production start

1. Install Java 17 or later and configure `JAVA_HOME` when Java is not on `PATH`.
2. Choose `h2` or `mysql`. H2 is the default; for MySQL, initialize it with `config/sql/mysql/chatchat-runtime-news.sql`.
3. Edit `config/application-h2.yml` or `config/application-mysql.yml`, then edit `config/application.yml` for OpenSearch, internal account and optional Embedding settings.
4. Configure the same internal account on MCP and News Runtime. News Runtime refuses to start with an empty internal secret.
5. Start with `bin/start.sh` on Linux or `bin/start.bat` on Windows.

`config/application.yml` deliberately contains concrete values rather than environment-variable placeholders. `config/env.properties`
only controls JVM memory and optional command-line arguments.

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
