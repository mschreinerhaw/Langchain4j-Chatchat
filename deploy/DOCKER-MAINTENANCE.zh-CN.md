# Docker 部署与手工维护

维护已有 `/opt/runtime-os-docker-stage` 实例，请先看[当前服务器逐容器维护命令](CONTAINER-OPERATIONS.zh-CN.md)。本文的新安装镜像、空卷初始化和 `docker run` 模板不能直接覆盖现有部署。

以下命令在 Linux Docker 主机使用 Bash 执行。主入口是 `deploy/docker/application/compose.yaml`，Compose V2 负责完整栈。容器名通过 Compose 查询，不依赖某台服务器的 IP。镜像版本与现有客户端兼容；发布时将镜像变量替换为已审核的固定 digest。

## 依赖和启动顺序

| 服务/组件 | 用途和数据 | 必需性 | 启动条件 |
| --- | --- | --- | --- |
| PostgreSQL 16 | API/MCP 独立数据库、独立登录角色 | 当前完整栈必需 | schema 完成标记存在，TCP `pg_isready` 成功 |
| OpenSearch 2.12 | API 文档检索、外置会话正文；MCP 可选远程索引 | 当前完整栈必需 | TLS HTTP 可用，安全初始化任务成功，认证后集群至少 yellow |
| Redis 7.4 | MCP 已发布查询缓存的 `db-query-cache:*` ACL | 完整栈一并提供；实际启用取决于已有缓存配置，默认 RocksDB 不自动切换 | 认证 PING 成功 |
| News Runtime | 新闻获取及调度 | 当前功能栈必需 | HTTP 接口可用；自身默认 H2/内嵌 Temporal |
| MCP | 授权工具、快照和 Python 执行 | 必需 | PostgreSQL/Redis 健康、安全初始化完成、News 就绪 |
| API | 业务编排、授权及结果验收 | 必需 | PostgreSQL 健康、安全初始化完成、MCP HTTP/gRPC 就绪 |
| Coding Agent | Cline SDK | Coding 功能必需 | API 就绪；自身 `/healthz` 成功 |
| node-proxy / gateway | 内部 Node 回环代理 / 对外统一入口 | 必需 | API/MCP/Coding 依次就绪后启动 |
| Python Sandbox 镜像与 Docker Engine | 每次受控任务启动独立容器 | 数据分析必需 | MCP 挂载 Docker socket；发布的 Python 环境指定已安装镜像 |
| MySQL 8.4 | 旧 MySQL 部署及 News 可选 MySQL profile | 替代方案，非 PostgreSQL 的额外必选库 | 见旧基础设施 Compose |
| 外部 Temporal + 专用 PostgreSQL | 持久工作流历史 | 选择 external 模式时需要 | 专用数据库健康后启动 Temporal，再启动使用它的 Java 服务 |
| H2、RocksDB、Lucene、Parquet/CSV | 嵌入式数据库、索引和数据快照 | 随功能使用 | 已在应用/数据目录中，无独立 Docker 服务 |
| 模型网关/供应商 HTTP、授权业务数据源 | 模型调用和业务取数 | 按已登记配置 | 使用现有授权地址和凭据；没有通用容器镜像，不能从任意示例推断生产地址 |

API、MCP、News 有运行时双向 HTTP 调用，Compose 只建立无环的就绪依赖。Java 健康探针接受 HTTP 200/401/403，MCP 还检查 gRPC 端口；它们证明进程接口就绪，不代替业务验收。依赖健康等待与一次性任务完成语义见 [Docker 官方说明](https://docs.docker.com/compose/how-tos/startup-order/)；已运行的依赖后来故障，不会由 `depends_on` 自动停止/重启所有消费者。

## 1. 目录、配置及密钥

```bash
export REPO_ROOT=/opt/src/Langchain4j-Chatchat
export STAGE_ROOT=/opt/runtime-os
cd "$REPO_ROOT"
umask 077
mkdir -p "$STAGE_ROOT"/{config,secrets,api/data,mcp/data,news/data,news/config}
mkdir -p "$STAGE_ROOT"/{api/logs,mcp/logs,news/logs}
cp deploy/docker/application/deployment.env.example "$STAGE_ROOT/config/deployment.env"
cp deploy/docker/application/{public-gateway.conf,node-proxy.conf} "$STAGE_ROOT/config/"
# 编辑 deployment.env 的镜像、物理网卡、数据路径；准备 api.env、mcp.env、news.env。
# api.env/mcp.env 可保留非数据库配置；不要再设置旧数据库 URL 或密码覆盖本栈。
touch "$STAGE_ROOT/config/"{api,mcp,news}.env
```

生成以下新部署密钥（已有部署从密钥管理系统导入，不覆盖原文件）。不要将整个真实 env 文件提交到 Git，也不要在工单中粘贴 `compose config` 的完整输出。

```bash
for name in postgresql-admin postgresql-api postgresql-mcp redis opensearch-admin bootstrap-admin coding-shared coding-monitor.token; do
  test ! -e "$STAGE_ROOT/secrets/$name" || { echo "Refusing to replace $name"; exit 1; }
  openssl rand -hex 32 | tr -d '\n' > "$STAGE_ROOT/secrets/$name"
done
cp "$STAGE_ROOT/secrets/coding-shared" "$STAGE_ROOT/secrets/coding-node"
chown root:32101 "$STAGE_ROOT/secrets/coding-node"
chmod 640 "$STAGE_ROOT/secrets/coding-node"
chown root:32101 "$STAGE_ROOT/secrets/coding-monitor.token"
chmod 640 "$STAGE_ROOT/secrets/coding-monitor.token"
chmod 700 "$STAGE_ROOT/secrets"
# 官方 PostgreSQL entrypoint 会降权，两个应用密码必须可被其 postgres 用户读取。
# 镜像须已离线导入或从批准仓库安装；自定义镜像用实际 POSTGRESQL_IMAGE。
POSTGRES_GID=$(docker run --rm --network none --entrypoint id "${POSTGRESQL_IMAGE:-postgres:16.9}" -g postgres)
chown root:"$POSTGRES_GID" "$STAGE_ROOT/secrets/"postgresql-{admin,api,mcp}
chmod 640 "$STAGE_ROOT/secrets/"postgresql-{admin,api,mcp}
```

`api-internal.key`、`mcp-internal.key`、模型配置、News 配置和授权 license 使用现有配置管理流程提供；加密后的配置值必须与对应密钥匹配，不要随机替换密钥。`bootstrap-admin` 仅用于创建/修复无密码管理员，已有有效密码不会被改写。Compose 用 Spring config tree 读取数据库、OpenSearch 和初始化密码，不把明文放入 YAML 或进程启动参数。

监控控制台为 Runtime 网关的 `/monitor/`。网关只将 `/monitor`、`/monitor/` 和 `/v1/monitor/` 白名单经 API 网络空间中的 8792 监控监听转给 Coding Agent；执行接口仍仅由 Java 访问回环 8791。浏览器通过独立的 `coding-monitor.token` 登录，监控令牌不等同于执行密钥；Cookie 和 Origin/Host 保持同源。监控历史保存在独立持久卷。TLS 入口可设置 `CODING_MONITOR_SECURE_COOKIE=true`。

向 `OPENSEARCH_CERTS_DIR` 安装 [OpenSearch 证书清单](docker/opensearch/certs/README.md)，UID 1000 必须能读取目录和必要文件。不要将 CA 私钥部署到容器。生成 Java truststore 时保留 JRE 原有公有 CA，避免影响模型供应商 HTTPS；下面在安装了 JDK 的维护主机执行：

```bash
keytool -importkeystore -noprompt \
  -srckeystore "$JAVA_HOME/lib/security/cacerts" -srcstorepass changeit \
  -destkeystore "$STAGE_ROOT/config/opensearch-truststore.p12" -deststoretype PKCS12 -deststorepass changeit
keytool -importcert -noprompt -alias runtime-opensearch-ca \
  -file "$STAGE_ROOT/secrets/opensearch-certs/root-ca.pem" \
  -keystore "$STAGE_ROOT/config/opensearch-truststore.p12" -storepass changeit
chmod 644 "$STAGE_ROOT/config/opensearch-truststore.p12"
sudo sysctl -w vm.max_map_count=262144
```

truststore 仅包含公开证书，其校验口令不是用户认证密码；正式主机按系统配置管理持久化 `vm.max_map_count`。保持 TLS 验证，不使用 `curl -k` 或禁用 OpenSearch Security。证书与安全初始化依据 [OpenSearch 官方部署说明](https://docs.opensearch.org/latest/install-and-configure/install-opensearch/docker/)。

OpenSearch 镜像以 UID 1000 运行：将 `opensearch-admin` 文件设置为 `root:1000`、0640，证书目录 `root:1000`、0750，必要的 PEM 文件 0640。Compose 的文件型 secret 使用 bind mount，不能依赖 `mode` 字段替代宿主文件权限。

## 2. 完整栈启动

```bash
dc() { docker compose --env-file "$STAGE_ROOT/config/deployment.env" -f "$REPO_ROOT/deploy/docker/application/compose.yaml" "$@"; }
dc config --quiet
dc pull
dc up -d --wait --wait-timeout 600
dc ps --all
dc logs --tail=100 postgresql redis opensearch opensearch-security-init
```

必需依赖没有 profile 开关，`up` 会创建它们。项目命名卷保存 PostgreSQL、Redis、OpenSearch 数据；应用 bind mount 保存 H2、RocksDB、Lucene、快照及结果。此 Compose 不连接已有独立数据库容器：在已有服务器应用前，应将原库/索引备份恢复到本项目，并核对角色、数据库名称、会话正文索引及连接地址。不能用新空卷替代原数据。

首次 PostgreSQL 空卷初始化两个独立数据库 `live_runtime_api`/`live_runtime_mcp`，导入仓库基础 schema，不导入行业示例数据。已存在卷不会再次运行初始化脚本，这是 [PostgreSQL 官方镜像的约定](https://github.com/docker-library/docs/blob/master/postgres/README.md)。SQL 可能滞后于实体，首次隔离部署按审核迁移补齐，正式部署使用 `validate`；不要删除数据卷来“重新初始化”。

只有全部 SQL 成功后才写入 PGDATA 中的 `.runtime-schema-initialized` 标记，健康检查同时要求该标记，避免初始化失败后 PostgreSQL 自动重启却被误判健康。导入已有 PGDATA 时，由 DBA 验证数据库、账号隔离及 schema 完整性后再创建该标记。SQL 目录必须可被容器 postgres 用户读取；从 Windows 复制脚本时保留 LF 换行（仓库 `.gitattributes` 已声明）。

OpenSearch 初始化只在安全索引未初始化时执行；已可认证的安装只检查健康，密码不匹配则失败，不覆盖已有角色或用户。API/MCP 等待该任务成功完成后启动。缓存配置应登记 `redis:6379`、ACL 用户 `chatchat` 和 `/run/secrets/redis_password`，不是把密码明文写入业务表。

## 3. 分步手工启动与原生 docker run

推荐逐步执行同一 Compose，使持久化、凭据、网络和健康条件保持一致：

```bash
dc up -d --wait postgresql redis opensearch
dc up -d opensearch-security-init
dc wait opensearch-security-init || { echo 'OpenSearch initialization failed'; exit 1; }
dc up -d --wait news mcp api coding-agent node-proxy gateway
```

需要脱离 Compose 手工维护时，以下原生命令部署一套**独立**基础设施；不能同时占用同一数据目录或端口。`manual-*` 卷和容器不属于 Compose 项目。变量来自上述部署路径；镜像可按审核版本替换。

```bash
docker network create runtime-manual-control
docker volume create runtime-manual-postgresql
docker volume create runtime-manual-redis
docker volume create runtime-manual-opensearch

docker run -d --name runtime-manual-postgresql --restart unless-stopped \
  --network runtime-manual-control --network-alias postgresql -p 127.0.0.1:15432:5432 \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD_FILE=/run/secrets/postgresql_admin_password \
  -e POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 \
  -v runtime-manual-postgresql:/var/lib/postgresql/data \
  -v "$STAGE_ROOT/secrets/postgresql-admin:/run/secrets/postgresql_admin_password:ro" \
  -v "$STAGE_ROOT/secrets/postgresql-api:/run/secrets/postgresql_api_password:ro" \
  -v "$STAGE_ROOT/secrets/postgresql-mcp:/run/secrets/postgresql_mcp_password:ro" \
  -v "$REPO_ROOT/deploy/docker/application/postgresql-init.sh:/docker-entrypoint-initdb.d/10-runtime.sh:ro" \
  -v "$REPO_ROOT/database/init/postgresql:/opt/runtime-schema:ro" postgres:16.9
docker exec runtime-manual-postgresql pg_isready -h 127.0.0.1 -U postgres -d postgres
docker exec runtime-manual-postgresql sh -ec 'test -f "$PGDATA/.runtime-schema-initialized"'

docker run -d --name runtime-manual-redis --restart unless-stopped \
  --network runtime-manual-control --network-alias redis -p 127.0.0.1:16379:6379 \
  -v runtime-manual-redis:/data \
  -v "$STAGE_ROOT/secrets/redis:/run/secrets/redis_password:ro" \
  -v "$REPO_ROOT/deploy/docker/redis/redis.conf:/usr/local/etc/redis/redis.conf:ro" \
  -v "$REPO_ROOT/deploy/docker/redis/entrypoint.sh:/usr/local/bin/chatchat-redis-entrypoint.sh:ro" \
  --entrypoint sh redis:7.4-alpine /usr/local/bin/chatchat-redis-entrypoint.sh
docker exec runtime-manual-redis sh -ec 'REDISCLI_AUTH=$(cat /run/secrets/redis_password) redis-cli --user chatchat --no-auth-warning ping'

docker run -d --name runtime-manual-opensearch --restart unless-stopped \
  --network runtime-manual-control --network-alias opensearch -p 127.0.0.1:19200:9200 \
  --memory 2g --ulimit memlock=-1:-1 --ulimit nofile=65536:65536 \
  -e DISABLE_INSTALL_DEMO_CONFIG=true -e 'OPENSEARCH_JAVA_OPTS=-Xms1g -Xmx1g' \
  -v runtime-manual-opensearch:/usr/share/opensearch/data \
  -v "$REPO_ROOT/deploy/docker/opensearch/opensearch.yml:/usr/share/opensearch/config/opensearch.yml:ro" \
  -v "$STAGE_ROOT/secrets/opensearch-certs:/usr/share/opensearch/config/certs:ro" \
  opensearchproject/opensearch:2.12.0
# 等待 TLS HTTP 可响应，再执行一次性认证初始化；失败则不启动应用。
docker run --rm --name runtime-manual-opensearch-init --network runtime-manual-control \
  -v "$STAGE_ROOT/secrets/opensearch-certs:/usr/share/opensearch/config/certs:ro" \
  -v "$STAGE_ROOT/secrets/opensearch-admin:/run/secrets/opensearch_admin_password:ro" \
  -v "$REPO_ROOT/deploy/docker/application/opensearch-init-security.sh:/opt/runtime/init-security.sh:ro" \
  --entrypoint bash opensearchproject/opensearch:2.12.0 /opt/runtime/init-security.sh
```

手工检查 PostgreSQL 就绪时应同时确认初始化日志结束；第一次容器初始化失败后，修复原因并人工审查已有 PGDATA，不盲目重试 schema。原生 `docker run` 不会自动等待健康，必须逐步验证后再连接应用。自有 Java/Node 镜像的完整挂载、权限和网卡身份继续使用 Compose，不另维护一套容易偏离的应用启动参数。

## 4. 可选依赖

MySQL 是替代数据库方案，使用已有 schema、账号隔离和 Redis/TLS 配置；在选择 MySQL 时更新 Java datasource/profile，不能把 PostgreSQL URL 原样保留：

```bash
docker compose --env-file "$REPO_ROOT/.env.production" -f "$REPO_ROOT/deploy/docker/docker-compose.yaml" up -d mysql redis opensearch
docker compose --env-file "$REPO_ROOT/.env.production" -f "$REPO_ROOT/deploy/docker/docker-compose.yaml" up -d opensearch-security-init
```

该命令的密钥/证书位置与完整 PostgreSQL 栈不同，见 [MySQL 基础设施手册](docker/README.md)。不要同时对同一个 OpenSearch 索引执行两套安全初始化。

外部 Temporal 的开发启动命令如下；这个已有示例含开发凭据，只用于隔离开发，不用于生产：

```bash
docker compose -f "$REPO_ROOT/deploy/temporal/docker-compose.yml" up -d
docker compose -f "$REPO_ROOT/deploy/temporal/docker-compose.yml" ps
```

News 设置 `CHATCHAT_RUNTIME_NEWS_TEMPORAL_SERVER_MODE=external` 和可达的 `CHATCHAT_RUNTIME_NEWS_TEMPORAL_TARGET`。Agent 平台设置 `CHATCHAT_AGENT_RUNTIME_WORKFLOW_ENGINE=temporal` 与 `CHATCHAT_TEMPORAL_TARGET`。生产外部 Temporal 需要独立持久库、TLS 和认证，见 [Temporal 手册](temporal/README.md)。它不作为默认 embedded 模式的必需依赖。

Python Sandbox 是任务级容器，不是长驻调度器：

```bash
docker build -t runtime-os-python:RELEASE_TAG -f "$REPO_ROOT/deploy/docker/python-runtime/Dockerfile" "$REPO_ROOT/deploy/docker/python-runtime"
docker image inspect runtime-os-python:RELEASE_TAG
# 离线主机导入审核过的镜像后，在 MCP 环境管理中登记并发布；不要手工启动后台 Python Agent。
docker load -i /private/images/python-runtime.tar
```

镜像构建输入、字体与验证见 [Python Runtime 手册](docker/python-runtime/README.md)。Docker Engine/CLI 为宿主运行依赖；SQLGlot、PDF/Office/字体等随镜像安装，不再额外启动同名服务。模型供应商和授权生产数据源由平台配置，Node 不加入数据库网络，不直接连接生产数据源。

## 5. 日常维护、备份与恢复

```bash
dc ps --all
dc logs --since=10m --tail=200 api mcp coding-agent
dc exec postgresql pg_isready -h 127.0.0.1 -U postgres -d postgres
dc exec redis sh -ec 'REDISCLI_AUTH=$(cat /run/secrets/redis_password) redis-cli --user chatchat --no-auth-warning ping'
dc restart redis
# 上下文已有任务先完成/取消，再重启对应 Java/Worker；重建后刷新 Coding 边界规则并运行签名回调 gate。
dc stop gateway node-proxy coding-agent api mcp news
dc stop redis opensearch postgresql
```

备份 PostgreSQL（归档包含账号密码哈希，目录保持 0700），恢复前停止所有写入并核对目标库：

```bash
mkdir -p /private/runtime-backups
chmod 700 /private/runtime-backups
dc exec -T postgresql pg_dumpall -U postgres > /private/runtime-backups/postgresql.sql
# 恢复到已检查的目标，默认不自动覆盖旧库：
# dc exec -T postgresql psql -v ON_ERROR_STOP=1 -U postgres < /private/runtime-backups/postgresql.sql
```

OpenSearch 使用其 Snapshot API 和已配置的 snapshot repository；在线复制数据卷不是一致备份。Redis 使用持久化 AOF/RDB 并做恢复演练。API/MCP/News 停止后备份各自数据目录，PostgreSQL 会话索引、RocksDB 正文引用和 OpenSearch 正文需保持一致。`dc down` 保留命名数据卷但会停止本栈数据库；不执行 `down -v`。卷名由 Compose project 决定，变更项目名会创建另一套空卷。

应用升级后还应验证 license、授权数据供给、模型调用、Python 产物验收和签名回调；健康检查成功不表示这些业务验收已通过。
