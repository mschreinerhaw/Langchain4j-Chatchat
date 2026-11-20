# Runtime OS 1.3.2

发行版复用生产 Compose 的 PostgreSQL 18、Redis、Silo、OpenSearch 2.19.1 和 Temporal 1.29.7。API、MCP 和 News 等待数据库、OpenSearch 安全初始化和 Temporal namespace。API 容器内存上限 6 GiB，Java 最大堆 4 GiB。网关提供智策、MCP 管理和 Coding 任务监控入口。

## 在新主机启动

Linux amd64 可以加载发行镜像包：

```text
docker image load -i runtime-os-1.3.2-linux-amd64-apps.tar
docker image load -i runtime-os-1.3.2-linux-amd64-frontend.tar
```

源码构建在仓库根目录执行以下命令。构建脚本通过 `https://192.168.36.128:8443` 下载依赖，包括前端 npm 依赖。Docker Engine 的基础镜像拉取也使用该代理。

```text
python scripts/build-production-images.py --tag 1.3.2
```

将 [release.env.example](release.env.example) 复制到仓库外，填写 `RUNTIME_CONFIG_DIR` 和 `HUB_TOKEN`。所需应用 env、私有配置、数据库和 Silo 凭据、OpenSearch 证书及 Java 信任库见[生产容器部署说明](../../docker/production/README.md#私有配置)。Cline 的模型和令牌填入私有 `cline-server.json`。应用间地址使用 Compose 服务名，例如 `silo:9000` 和 `cline:18326`。保留整个源码发行包目录结构，Compose 会从中加载各服务的初始化脚本和监控页面资源。

```text
docker compose --env-file /path/to/release.env -f deploy/releases/1.3.2/docker-compose.yml config --quiet
docker compose --env-file /path/to/release.env -f deploy/releases/1.3.2/docker-compose.yml up -d --wait --wait-timeout 360
docker compose --env-file /path/to/release.env -f deploy/releases/1.3.2/docker-compose.yml ps -a
docker compose --env-file /path/to/release.env -f deploy/releases/1.3.2/docker-compose.yml stop -t 90
```

默认智策入口为 `http://127.0.0.1:18100/`，Coding 监控为 `/monitor/`，MCP 管理端口为 `18101`。可用 `/version.json` 检查发行版本。对外开放前，从本机登录并修改 MCP 独立后台的初始管理员密码，再接入现有的 TLS 反向代理。三个一次性初始化服务正常退出时应显示 `Exited (0)`。停止命令保留数据卷。新建数据库使用仓库中的初始化结构，已有数据库升级按现有迁移流程执行。生产业务模型、数据源和对象存储权限从私有配置提供。

## 复用已部署的 Linux 生产环境

已运行的生产拓扑使用 `/opt/runtime-os/production-current`，包含 Native Broker、专用 Docker daemon 与 Executor，以及既有的数据库、索引和 Silo。使用 [Compose 入口](../../docker/production/compose.release.yaml)、[依赖覆盖文件](../../docker/production/compose.host-dependencies.yaml)和[启动脚本](../../docker/production/start-production.sh)复用这些已配置资源。在准备新的生产版本时，继续用 `scripts/prepare-production-stage.py` 生成主机专属配置，并按[服务器部署说明](../../docker/production/SERVER-DEPLOYMENT.md)验收。该模式需要部署阶段提供已授权、已匹配宿主机身份的 Native Executor 配置。新主机 Compose 自带 Cline 服务，其模型适配和现网 Native 作业恢复分别验收。
