# 当前服务器逐容器维护命令

适用于已部署的 `/opt/runtime-os-docker-stage`，核对日期：2026-10-26。以下命令在服务器使用 Bash 执行，使用现有容器、镜像、凭据和数据。配置统一从本目录管理；数据目录保留现有挂载，不随配置迁移。

```bash
cd /opt/runtime-os-docker-stage
docker compose --env-file config/deployment.env -f compose.yaml config --quiet
```

当前根目录 `compose.yaml` 是现网维护清单：默认管理 `node-proxy` 和 `gateway`，其他服务放在 `existing-containers` profile。不要直接开启该 profile 执行全栈 `up`：共享数据库尚未由此 Compose 项目接管。`docker compose ps` 默认也不能代替全量容器检查。

## 1. 服务名称、配置及启动顺序

| 顺序 | 服务 | 当前容器名 | 主机配置位置（相对标准目录） |
| --- | --- | --- | --- |
| 1 | PostgreSQL | `postgres` | `config/postgresql.env`；数据仍在 `/data/postgresql` |
| 1 | Redis | `redis` | `config/redis.env`；密码还涉及现有容器启动参数，修改 env 不会自动换密 |
| 1 | OpenSearch | `opensearch-node` | `config/opensearch.env`；数据、日志、插件仍在 `/data/opensearch/` |
| 1 | S3/MinIO | `silo` | `config/silo.env`、`config/silo/app-policy.json`、`silo/secrets/` |
| 2 | News | `runtime-os-app-stage-news-1` | `config/news.env`、`config/news/` |
| 3 | MCP | `runtime-os-app-stage-mcp-1` | `config/mcp.env`、`config/mcp/` |
| 4 | API | `runtime-os-app-stage-api-1` | `config/api.env`、`config/api/`、`config/secrets/` |
| 5 | Coding Agent | `runtime-os-app-stage-coding-agent-1` | `config/coding-agent.env`、`config/secrets/` |
| 6 | 内部 Nginx | `runtime-os-app-stage-node-proxy-1` | `config/node-proxy.conf` |
| 7 | 入口 Nginx | `runtime-os-app-stage-gateway-1` | `config/public-gateway.conf` |

同一顺序的基础依赖可以分别启动；每层检查通过再继续。API、MCP 的运行期回调不能仅由启动顺序保证，最后必须验证认证回调。Python Sandbox 是按任务创建的隔离容器，不启动一个常驻 Python 服务。当前 News 使用内嵌工作流；独立 Temporal/MySQL 属于可选部署，见[新安装维护文档](DOCKER-MAINTENANCE.zh-CN.md)。其他业务的 ZooKeeper 不在本次维护范围。

## 2. 每个容器单独启动及验证

`docker start` 适用于容器仍然存在但已停止的情况，保留原参数和数据；已经运行的容器无需再次启动。出现 `No such container` 时转到第 5 节，不要临时创建同名空库。

### PostgreSQL

```bash
docker start postgres
docker exec postgres pg_isready -U postgres -d postgres
docker logs --tail 100 postgres
```

现有数据库为 `runtime_os_acceptance_api` / `runtime_os_acceptance_mcp`，当前安装 PostgreSQL 18。`pg_isready` 验证服务接受连接，不代替应用账号权限检查。

### Redis

```bash
docker start redis
docker exec -it redis redis-cli --askpass ping
docker logs --tail 100 redis
```

交互输入现有密码，预期 `PONG`；不要把密码直接写进命令或文档。当前版本为 6.2.6，沿用原数据卷和认证方式。

### OpenSearch

```bash
docker start opensearch-node
docker logs --tail 100 opensearch-node
# 输入当前用户名及 CA 文件路径；curl 交互询问密码。
read -r -p 'OpenSearch username: ' OS_USER
read -r -p 'CA certificate absolute path: ' OS_CA
curl --fail --show-error --cacert "$OS_CA" --user "$OS_USER" \
  'https://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=30s'
unset OS_USER OS_CA
```

当前版本为 2.19.1，已启用 TLS 和认证。使用匹配现有证书的 CA 和主机名；如证书未包含 `localhost`，将 URL 改为其证书域名并配置解析。上面的 CA 路径需要维护者提供，并非本目录已安装 CA 的声明。验证须得到 HTTP 成功响应及 `yellow`/`green` 状态，同时确认 `timed_out=false`。

### S3 对象存储

```bash
docker start silo
curl --fail --show-error http://127.0.0.1:9000/minio/health/ready
docker logs --tail 100 silo
```

### News

```bash
docker start runtime-os-app-stage-news-1
docker exec -i runtime-os-app-stage-news-1 bash -s -- \
  8091 '' /internal/v1/news/health < config/service-healthcheck.sh
docker logs --tail 100 runtime-os-app-stage-news-1
```

### MCP

```bash
docker start runtime-os-app-stage-mcp-1
docker exec -i runtime-os-app-stage-mcp-1 bash -s -- \
  8090 9091 /api/v1/license/status < config/service-healthcheck.sh
docker logs --tail 100 runtime-os-app-stage-mcp-1
```

MCP 依赖 Docker socket 执行受控 Sandbox，宿主机 Python 数据路径必须与维护清单一致，不自行改成其他共享目录。

### API

```bash
docker start runtime-os-app-stage-api-1
docker exec -i runtime-os-app-stage-api-1 bash -s -- \
  8080 '' /api/v1/enterprise/auth/me < config/service-healthcheck.sh
docker logs --tail 100 runtime-os-app-stage-api-1
```

上述 Java 探针接受 200/401/403 并检查监听端口；它们仅验证服务可响应，受保护接口的 401/403 不代表业务验收成功。

### Coding Agent

```bash
docker start runtime-os-app-stage-coding-agent-1
docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' \
  runtime-os-app-stage-coding-agent-1
docker logs --tail 100 runtime-os-app-stage-coding-agent-1
```

等待状态为 `healthy`。容器内置探针读取执行密钥进行认证；浏览器监控使用另一个令牌 `config/secrets/coding-monitor.token`。不要开放 3100 到公网，也不要将执行密钥作为监控令牌。

### 内部 Nginx：node-proxy

```bash
docker start runtime-os-app-stage-node-proxy-1
docker exec runtime-os-app-stage-node-proxy-1 nginx -t
docker logs --tail 100 runtime-os-app-stage-node-proxy-1
```

代理共享 API 的网络命名空间，8791 供 Java 调用，8792 仅允许监控路由。API 若仅停止后启动，随后重启此代理并验证；API 若删除并重建，必须按第 4 节重建此代理以绑定新的容器 ID。

### 入口 Nginx：gateway

```bash
docker start runtime-os-app-stage-gateway-1
docker exec runtime-os-app-stage-gateway-1 nginx -t
curl --fail --show-error --location --output /dev/null http://127.0.0.1:18100/monitor
curl --fail --show-error --output /dev/null http://127.0.0.1:18101/admin/index.html
docker logs --tail 100 runtime-os-app-stage-gateway-1
```

API/监控入口为主机 18100，MCP 入口为 18101。监控页面为 `/monitor/`，页面加载后仍需监控令牌登录。

## 3. 单独停止、重启和排障

以下任选一个实际容器名，示例为 API：

```bash
CONTAINER=runtime-os-app-stage-api-1
docker stop --time 60 "$CONTAINER"
docker start "$CONTAINER"
# 已运行服务需要重启时，使用此命令代替上面两条：
docker restart --time 60 "$CONTAINER"
docker inspect --format '{{.Name}} status={{.State.Status}} exit={{.State.ExitCode}} restarts={{.RestartCount}}' "$CONTAINER"
docker logs --since 10m --tail 200 "$CONTAINER"
unset CONTAINER
```

停止前先停止新任务接入并等待运行任务完成。全栈停机按网关→内部代理→Coding→API→MCP→News→存储依赖逆序；共享 PostgreSQL/Redis/OpenSearch 须确认其他业务不再使用后才能停止。上面是命令选择示例，不是要求依次执行所有命令。

## 4. Nginx 缺失创建、重建及认证验证

现有应用就绪后，可以分别创建缺失代理；不会启动 `existing-containers` profile：

```bash
cd /opt/runtime-os-docker-stage
docker compose --env-file config/deployment.env -f compose.yaml \
  up -d --no-deps --wait --wait-timeout 120 node-proxy
docker compose --env-file config/deployment.env -f compose.yaml \
  up -d --no-deps --wait --wait-timeout 120 gateway
```

API 重建或代理挂载配置替换后，用以下命令重建相关代理。仅 `docker restart node-proxy` 无法修改其记录的 API 容器 ID：

```bash
docker compose --env-file config/deployment.env -f compose.yaml \
  up -d --no-deps --force-recreate --wait --wait-timeout 120 node-proxy
docker compose --env-file config/deployment.env -f compose.yaml \
  up -d --no-deps --force-recreate --wait --wait-timeout 120 gateway
```

API/MCP 重建改变地址后，重建网关以更新后端解析。API/Coding 重建后，还须刷新已有网络隔离规则并验证签名回调；回调测试不调用模型：

```bash
bash templates/deploy/docker/application/restrict-coding-worker.sh
umask 077
mkdir -p maintenance/manual-checks
python3 scripts/verify-coding-callback.py \
  --worker runtime-os-app-stage-coding-agent-1 \
  --report "maintenance/manual-checks/callback-$(date -u +%Y%m%dT%H%M%SZ).json"
```

验证失败时保留报告并排查 URL、认证和网络规则，不继续切换正式流量。Java 与 Coding 的共享密钥必须一致；监控令牌不能用于执行回调。完整验收还应执行授权业务任务，不能把健康探针成功等同于分析任务成功。

## 5. 容器不存在、配置更新与数据保护

`docker start`/`restart` 不会重新读取 `config/*.env` 创建新环境变量。更改环境、镜像、网络或挂载需要重建容器。应用和数据库的重建参数以本机受保护的根 `compose.yaml` 为准，先核对原数据挂载、镜像 ID、权限、网络、密钥和备份，再逐服务接管；当前文档不提供会遗漏这些参数的简化 `docker run`。

新安装需要的逐服务 `docker run` 模板在 [Docker 部署与手工维护](DOCKER-MAINTENANCE.zh-CN.md)，其版本和空卷初始化条件与本机不同，不能直接替代当前容器。不要在当前实例执行 `docker compose down -v`、删除数据卷，或启动历史 `*-before-*` 回滚副本。真实 env、完整 Compose 展开结果和凭据不提交 Git。

维护后查看实际运行容器（包括不属于当前 Compose 项目的依赖）：

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
docker compose --env-file config/deployment.env -f compose.yaml config --quiet
```

本文命令依据当前实例核对；文档交付期间只执行只读检查，没有为验证文档重启业务容器。历史迁移记录见本机 `maintenance/monitor-config-20261026/`。
