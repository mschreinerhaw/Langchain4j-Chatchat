# Runtime OS 1.3.2 客户离线部署

此安装包使用本地构建的 `1.3.2` Linux amd64 镜像，并沿用现网端口布局和固定数据目录。目标为新 Linux 主机，需已安装 Docker Engine、Docker Compose 2.27 或以上、Python 3.9 或以上、OpenSSL 和 curl。启用 Native Coding 还需要 systemd，以及宿主机的 `dockerd`、`containerd`、`ctr`。保留解包后的目录结构。

## 1. 导入镜像并启动

在发行包根目录以 root 执行：

```sh
(cd images && sha256sum -c SHA256SUMS)
docker image load -i images/apps-linux-amd64.tar
docker image load -i images/infrastructure-linux-amd64.tar
python3 deploy/releases/1.3.2/customer/prepare.py
bash deploy/releases/1.3.2/customer/start.sh
```

`prepare.py` 在目标主机生成独立的凭据和搜索 CA，并由 [config](config/) 模板创建 `release.env`、`api.env`、`mcp.env`、`news.env`。文件固定在 `/opt/runtime-os-docker-stage/config/`。平台管理员初始密码位于 `config/secrets/api_bootstrap_admin_password`。MCP 独立后台安装后先在本机修改初始管理员密码，再接 TLS 反向代理。默认入口为 `127.0.0.1:18100` 和 `127.0.0.1:18101`。

| 配置入口 | 生效范围 |
| --- | --- |
| `api.env` | 数据库任务队列、心跳与租约、PARQUET 共享输入、工作区、分析参数、搜索索引 |
| `mcp.env` | API 鉴权地址、News 地址、gRPC 端口、Python 共享数据和工作区 |
| `news.env` | PostgreSQL 配置、独立 Temporal 和 MCP 地址 |
| `release.env` 和 Compose | 容器网络地址、JVM 与 TLS、数据库和搜索凭据文件、Native 启用开关 |

从旧客户包升级时，在客户主机重新运行新版 `prepare.py`。它补齐已有 `.env` 中缺失的键，保留已经设置的值和已有凭据。客户版 News 使用独立 PostgreSQL 新库，接管现网 H2 历史数据时需要单独迁移。

| 数据 | 固定位置 |
| --- | --- |
| API / MCP / News | `/opt/chatchat-1.0.0-SNAPSHOT/data`、`/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/data`、`/opt/chatchat-runtime-news-1.0.0-SNAPSHOT/data` |
| 日志及共享 Python 目录 | `/opt/runtime-os-docker-stage/{api,mcp,news}/logs`、`/opt/runtime-os-docker-stage/mcp/data/` |
| PostgreSQL / Silo / OpenSearch | `/data/postgresql/18/docker`、`/opt/runtime-os-docker-stage/silo/data`、`/data/opensearch/data` |
| Temporal / Redis | Docker 卷 `runtime-os-source-production_temporal-postgresql-data` 和 `runtime-os-source-production_redis-data` |
| Native | `/opt/runtime-os/p18-d736e9f` 和 `/srv/runtime-os-coding-node-20261110/strict-daemon-3` |

## 2. 启用生产 Native Coding 链路

先在客户环境创建并发布模型，取得现有租户 ID、用户 ID 和模型逻辑名。为 Silo 创建版本化 `runtime-assets` 桶，为客户应用签发该桶的受限凭据，并写入 `/opt/runtime-os-docker-stage/config/secrets/asset-access-key` 和 `asset-secret-key`。运行：

发布方用 `deploy/docker/production/native-daemon/build.sh` 构建 Native 运行时镜像，再用 `docker image save -o images/native-daemon-linux-amd64.tar runtime-os-native-daemon:26.1.3-el8` 打包。

```sh
docker image load -i images/native-daemon-linux-amd64.tar
bash deploy/releases/1.3.2/customer/install-native-daemon.sh
docker image load -i images/native-execution-linux-amd64.tar
docker --host unix:///srv/runtime-os-coding-node-20261110/strict-daemon-3/run/docker.sock \
  image load -i images/native-execution-linux-amd64.tar
python3 deploy/releases/1.3.2/customer/native-bind.py --tenant CUSTOMER_TENANT_ID \
  --user CUSTOMER_USER_ID --model PUBLISHED_MODEL_NAME
```

将私有 `release.env` 的 `CUSTOMER_NATIVE_ENABLED` 改为 `true`，再次运行 `start.sh`。启动会校验专用 daemon 的身份、隔离和资源限制，并检查 Executor 与 Broker。客户数据源、租户权限、已发布模型和客户授权工具通过现有平台配置。投产前提交合成任务，核对终态和 Silo 产物。普通启停使用 `start.sh` 和 `stop.sh`，保留既有数据目录和卷。

客户需按业务范围接入自有 JDBC 驱动、Python Studio 环境及 OpenSearch 扩展分词器。使用客户的模型和工具授权验收这些集成。

需要指定 Python 环境时，在 `api.env` 中设置客户发布的 `CHATCHAT_CODING_ANALYSIS_ENVIRONMENT_ID`，并在平台配置模型与推理档位。生产服务器的模型凭据、数据库密码、资产令牌、租户和环境 ID 由各环境自行管理。
