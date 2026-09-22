# ChatChat 数据库迁移程序

独立 Java 17 命令行模块，用 JDBC 在 MySQL 与 PostgreSQL 之间双向迁移 ChatChat API 或独立 MCP Server 的关系数据。无需启动 API/MCP 服务，也无需 Python。

## 构建

在仓库根目录执行：

```bash
mvn -pl chatchat-data-migration -am package
```

可执行文件：`chatchat-data-migration/target/chatchat-data-migration-1.0.0-SNAPSHOT.jar`。

## 准备

先停止 API/MCP 写入，备份两端数据库，并为目标库建立**同一应用版本**的表结构。初始化 SQL 位于 `database/init/mysql/` 与 `database/init/postgresql/`。不要在目标库预先导入种子数据；源库中的记录会一并迁移。

连接配置使用环境变量，可分别为 API/MCP 设置不同数据库名：

| MySQL | PostgreSQL |
| --- | --- |
| `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER` | `PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`、`PGSCHEMA`（默认 `public`） |
| `MYSQL_PASSWORD` 或 `MYSQL_PASSWORD_FILE` | `PGPASSWORD` 或 `PGPASSWORD_FILE` |
| 可用 `MYSQL_URL` 覆盖 JDBC URL | 可用 `PG_URL` 覆盖 JDBC URL |

默认数据库名为 `live_runtime_api` 或 `live_runtime_mcp`，默认用户名为 `chatchat_api` 或 `chatchat_mcp`。若使用 `*_PASSWORD_FILE`，文件内容作为密码，末尾换行会去除。

## 执行

```bash
JAR=chatchat-data-migration/target/chatchat-data-migration-1.0.0-SNAPSHOT.jar

java -jar "$JAR" --direction mysql-to-postgresql --module api --dry-run
java -jar "$JAR" --direction mysql-to-postgresql --module api

java -jar "$JAR" --direction postgresql-to-mysql --module api --dry-run
java -jar "$JAR" --direction postgresql-to-mysql --module api --replace-target
```

迁移 MCP 时将 `--module api` 改为 `--module mcp` 并切换连接配置。`--replace-target` 会在同一个目标库事务中清除旧数据并重新复制；未指定时，目标表必须全部为空。`--batch-size` 默认 500；`--mysql-timezone` 默认 `Asia/Shanghai`，用于 MySQL `DATETIME` 与 PostgreSQL 带时区时间字段之间转换。

程序先校验表和可写列是否一致，再按外键顺序迁移，每张表核对源库、已复制及目标库的行数，并重置 PostgreSQL 自增序列。若写入失败，目标库行变更会回滚。它只执行一次性关系数据复制；OpenSearch 索引、RocksDB 原文、外部附件以及 News/Market 数据库不在范围内。切换服务配置前，请核验这些存储及权限、Skill、MCP 功能。
