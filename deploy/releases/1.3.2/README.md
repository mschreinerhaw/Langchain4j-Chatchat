# Runtime OS 1.3.2

发行版复用生产 Compose 的 PostgreSQL 18、Redis、Silo、OpenSearch 2.19.1 和 Temporal 1.29.7。API、MCP 和 News 等待数据库、OpenSearch 安全初始化和 Temporal namespace。API 容器内存上限 6 GiB，Java 最大堆 4 GiB。网关提供智策、MCP 管理和 Coding 任务监控入口。

## 在新主机启动

Linux amd64 可从本地镜像包加载镜像。GitHub Release 提供源码和 Compose 配置包，镜像包由本地交付。

```text
docker image load -i runtime-os-1.3.2-linux-amd64-apps.tar
docker image load -i runtime-os-1.3.2-linux-amd64-frontend.tar
```

源码构建在仓库根目录执行以下命令。构建脚本通过 `https://192.168.36.128:8443` 下载依赖，包括前端 npm 依赖。Docker Engine 的基础镜像拉取也使用该代理。

```text
python scripts/build-production-images.py --tag 1.3.2
```

新主机固定将 [release.env.example](release.env.example) 安装为 `/opt/runtime-os/releases/1.3.2/private/release.env`，保留其中的配置目录路径，填写部署环境的 `HUB_TOKEN`。私有配置沿用生产的文件名。数据库、Silo 凭据及 TLS 文件的清单见[生产容器部署说明](../../docker/production/README.md#私有配置)。Cline 的模型和令牌填入私有 `cline-server.json`。应用间地址使用 Compose 服务名，例如 `silo:9000` 和 `cline:18326`。

| 私有文件或目录 | 新主机固定位置 |
| --- | --- |
| Compose 环境 | `/opt/runtime-os/releases/1.3.2/private/release.env` |
| 应用环境 | 同目录的 `api.env`、`mcp.env`、`news.env` |
| 应用配置 | 同目录的 `api/`、`mcp/`、`news/` 和 `cline-server.json` |
| 凭据与证书 | 同目录的 `secrets/`、`opensearch-certs/` 和 `search-truststore.p12` |

保留源码或 Compose 配置包的目录结构。Compose 会从中加载初始化脚本和监控资源。默认项目名固定为 `runtime-os-release`，启动和停止都使用该项目名。各数据卷在 Docker 的数据根目录下持久保存，可用 `docker volume inspect <卷名>` 查实际挂载目录。

| 数据 | 固定卷名 | 容器内目录 |
| --- | --- | --- |
| 业务 PostgreSQL / Temporal PostgreSQL | `runtime-os-release_postgresql-data` / `runtime-os-release_temporal-postgresql-data` | `/var/lib/postgresql` |
| Redis / Silo | `runtime-os-release_redis-data` / `runtime-os-release_silo-data` | `/data` |
| OpenSearch | `runtime-os-release_opensearch-data` | `/usr/share/opensearch/data` |
| API / MCP / News | `runtime-os-release_api-data` / `runtime-os-release_mcp-data` / `runtime-os-release_news-data` | `/srv/app/data` |
| Cline | `runtime-os-release_cline-state` / `runtime-os-release_cline-workspace` | `/srv/app/state` / `/workspace` |

将私有配置目录和上述卷的备份纳入部署交接。升级时沿用同一项目名和现有命名卷。

```text
docker compose -p runtime-os-release --env-file /opt/runtime-os/releases/1.3.2/private/release.env -f deploy/releases/1.3.2/docker-compose.yml config --quiet
docker compose -p runtime-os-release --env-file /opt/runtime-os/releases/1.3.2/private/release.env -f deploy/releases/1.3.2/docker-compose.yml up -d --wait --wait-timeout 360
docker compose -p runtime-os-release --env-file /opt/runtime-os/releases/1.3.2/private/release.env -f deploy/releases/1.3.2/docker-compose.yml ps -a
docker compose -p runtime-os-release --env-file /opt/runtime-os/releases/1.3.2/private/release.env -f deploy/releases/1.3.2/docker-compose.yml stop -t 90
```

默认智策入口为 `http://127.0.0.1:18100/`，Coding 监控为 `/monitor/`，MCP 管理端口为 `18101`。可用 `/version.json` 检查发行版本。对外开放前，从本机登录并修改 MCP 独立后台的初始管理员密码，再接入现有的 TLS 反向代理。三个一次性初始化服务正常退出时应显示 `Exited (0)`。停止命令保留数据卷。新建数据库使用仓库中的初始化结构，已有数据库升级按现有迁移流程执行。生产业务模型、数据源和对象存储权限从私有配置提供。

## 复用已部署的 Linux 生产环境

现网入口保持为 `/opt/runtime-os/compose.yaml` 和 `/opt/runtime-os/start-production.sh`。Compose 读取 `/opt/runtime-os/production-current/private/release.env`，应用沿用 `/opt/runtime-os-docker-stage/config/` 下的 `api.env`、`mcp.env`、`news.env` 及现有配置文件。现有数据挂载以 `/opt/runtime-os/production-current/compose.production.json` 为准，继续复用已部署的 PostgreSQL、OpenSearch、Silo 和 Native 执行目录。启停沿用现网入口：

```sh
/opt/runtime-os/start-production.sh
docker compose -f /opt/runtime-os/compose.yaml stop -t 90
```

在准备新的生产版本时，继续用 `scripts/prepare-production-stage.py` 生成主机专属配置，并按[服务器部署说明](../../docker/production/SERVER-DEPLOYMENT.md)验收。该模式需要部署阶段提供已授权、已匹配宿主机身份的 Native Executor 配置。新主机 Compose 自带 Cline 服务，其模型适配和现网 Native 作业恢复分别验收。
