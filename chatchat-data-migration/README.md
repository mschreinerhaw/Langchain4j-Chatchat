# ChatChat 数据库迁移程序

独立 Java 17 命令行模块，用 JDBC 在 MySQL 与 PostgreSQL 之间双向迁移 ChatChat API 或独立 MCP Server 的关系数据，也支持导出可携带的数据文件，供测试、生产等环境之间导入。无需启动 API/MCP 服务，也无需 Python。

## 构建

在仓库根目录执行：

```bash
mvn -pl chatchat-data-migration -am package
```

可执行文件：`chatchat-data-migration/target/chatchat-data-migration-1.0.0-SNAPSHOT.jar`。

## 准备

先停止 API/MCP 写入并备份两端数据库。目标库不存在时，程序会创建库；目标库缺少初始化脚本定义的表时，会创建表、索引和外键。目标库已有的表和数据会保留；已有表的列结构仍按迁移规则校验。初始化 SQL 位于 `database/init/mysql/` 与 `database/init/postgresql/`，并打包进 JAR。

连接配置使用环境变量，可分别为 API/MCP 设置不同数据库名：

| MySQL | PostgreSQL |
| --- | --- |
| `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER` | `PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`、`PGSCHEMA`（默认 `public`） |
| `MYSQL_PASSWORD` 或 `MYSQL_PASSWORD_FILE` | `PGPASSWORD` 或 `PGPASSWORD_FILE` |
| 可用 `MYSQL_URL` 覆盖 JDBC URL | 可用 `PG_URL` 覆盖 JDBC URL |

默认数据库名为 `live_runtime_api` 或 `live_runtime_mcp`，默认用户名为 `chatchat_api` 或 `chatchat_mcp`。若使用 `*_PASSWORD_FILE`，文件内容作为密码，末尾换行会去除。

自动建库需要相应的 MySQL `CREATE DATABASE` 或 PostgreSQL `CREATEDB` 权限。若普通连接账户没有建库权限，可另外设置 `MYSQL_ADMIN_USER`、`MYSQL_ADMIN_PASSWORD_FILE`（或 `MYSQL_ADMIN_PASSWORD`），以及 PostgreSQL 的 `PG_ADMIN_USER`、`PG_ADMIN_PASSWORD_FILE`（或 `PG_ADMIN_PASSWORD`）。管理员连接地址可用 `MYSQL_ADMIN_URL`、`PG_ADMIN_URL` 指定；未设置时，程序会从普通 JDBC URL 推导服务器地址，PostgreSQL 管理连接使用 `postgres` 数据库。独立管理员建库后，普通连接账户仍需拥有目标库的连接、建表和写入权限；PostgreSQL 创建的新库会以 `PGUSER` 为 owner。

执行 `--dry-run` 时不会创建库或表；如果目标库或表不存在，会列出待创建对象并结束。去掉 `--dry-run` 后才执行初始化。建库和 MySQL DDL 可能在后续数据复制失败后仍保留；重新执行可继续补建缺失表，但不会删除现有对象。

如果只需初始化库、表和内置记录，不从另一个库迁移，可单独执行：

```bash
java -jar chatchat-data-migration/target/chatchat-data-migration-1.0.0-SNAPSHOT.jar \
  --init-only --engine postgresql --module api --dry-run
java -jar chatchat-data-migration/target/chatchat-data-migration-1.0.0-SNAPSHOT.jar \
  --init-only --engine postgresql --module api
```

MySQL 改为 `--engine mysql`，MCP 改为 `--module mcp`。内置记录逐条按 `id` 检查，只插入缺失记录，不覆盖已有配置。

## 执行

```bash
JAR=chatchat-data-migration/target/chatchat-data-migration-1.0.0-SNAPSHOT.jar

java -jar "$JAR" --direction mysql-to-postgresql --module api --dry-run
java -jar "$JAR" --direction mysql-to-postgresql --module api

java -jar "$JAR" --direction postgresql-to-mysql --module api --dry-run
java -jar "$JAR" --direction postgresql-to-mysql --module api --replace-target
```

迁移 MCP 时将 `--module api` 改为 `--module mcp` 并切换连接配置。`--replace-target` 会在同一个目标库事务中清除旧数据并重新复制；未指定时，目标表必须全部为空。`--batch-size` 默认 500；`--mysql-timezone` 默认 `Asia/Shanghai`，用于 MySQL `DATETIME` 与 PostgreSQL 带时区时间字段之间转换。

程序先补建缺失目标对象并校验可写列，再按外键顺序迁移，每张表核对源库、已复制及目标库的行数，并重置 PostgreSQL 自增序列。复制完成后，仅对目标库中缺失的内置种子记录按 `id` 补齐；已有记录不覆盖。若写入失败，目标库行变更会回滚。它只执行一次性关系数据复制；OpenSearch 索引、RocksDB 原文、外部附件以及 News/Market 数据库不在范围内。切换服务配置前，请核验这些存储及权限、Skill、MCP 功能。

**迁移表范围由 `database/init/mysql/chatchat-api.sql`、`database/init/postgresql/chatchat-api.sql`（MCP 则对应 `chatchat-mcp-server.sql`）中的 `CREATE TABLE` 定义决定。**这些 SQL 会打包进 JAR。数据库中额外存在的表会跳过并打印名称；目标缺失的表会补建，源库缺失的必需表会停止。MCP 的两张旧分类表标记为 `migration-optional`：旧源库没有时可跳过，目标库仍会补建。修改初始化 SQL 后需重新构建并部署 JAR。

`--module api` 只处理 API 脚本中的表，`--module mcp` 只处理独立 MCP 脚本中的表。如果两类表位于同一个源库，要分别执行两次迁移。旧库缺少目标表的可空列或有默认值列时，由目标库填充；若只缺少必填的 `created_at`/`updated_at` 时间列，程序以本次迁移时间填充并打印提示。其他必填列缺失、或源库有目标库没有的列时，会停止，避免无声丢失数据。

## 导出数据文件并跨环境导入

导出时只需连接**当前环境的源库**；导入时只需连接**目标环境的目标库**。文件可在相同数据库类型或 MySQL/PostgreSQL 之间使用。分别对 API、MCP 执行，不能把一个模块的文件导入另一个模块。

测试环境导出、生产环境导入示例：

```bash
JAR=chatchat-data-migration/target/chatchat-data-migration-1.0.0-SNAPSHOT.jar

# 在测试环境：用该环境的 MYSQL_* 连接配置
java -jar "$JAR" --export-file ./api-test.zip --engine mysql --module api

# 将文件安全传输到生产环境；在那里改用生产库的 PG* 连接配置
java -jar "$JAR" --import-file ./api-test.zip --engine postgresql --module api --dry-run
java -jar "$JAR" --import-file ./api-test.zip --engine postgresql --module api --replace-target
```

生产迁移到测试时反向执行：在生产运行 `--export-file`，在测试运行 `--import-file`。如果目标库为空，导入时无需 `--replace-target`；若已有数据，必须显式指定。导入时会补建缺失目标库和表。导出文件已存在时程序会拒绝覆盖。

压缩包包含表列定义、行数和每张表的 SHA-256 校验值。导入先完整校验文件，再校验目标库的表和列；写入按外键顺序批量进行，失败则回滚目标库行变更。`--dry-run` 会完成文件和结构预检，但不会写库。SHA-256 用于发现文件损坏，不提供防篡改认证；跨环境传输时应通过可信通道传送。

**数据文件包含原数据库中的账户、权限、令牌及配置等敏感内容，请按数据库备份管理访问权限。**文件格式不包含 OpenSearch、RocksDB 或外部附件。若这些数据也需跨环境使用，需另行同步相应存储并核对引用关系。
