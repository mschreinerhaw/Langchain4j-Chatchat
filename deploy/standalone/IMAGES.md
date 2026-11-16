# 镜像与单服务维护

首次部署执行 [README 的两条命令](README.md#安装)。安装镜像自动创建下面所有容器及其网络、密码文件、证书和外置目录。部署用户不需要逐个拼接依赖服务的启动参数。

默认容器名前缀为 `runtime-os`；首次安装指定 `PROJECT` 时，改用实际前缀。镜像标签统一为 `20261115-docker-only`，每个镜像的准确 ID 和上游来源记录在交付的 `release-images.json` 中。

| 镜像 | 创建的容器 | 主要外置数据（安装目录下） |
| --- | --- | --- |
| `runtime-os-postgres` | `runtime-os-postgresql` | `data/postgresql`，包含 API/MCP/News 的独立数据库及角色 |
| `runtime-os-postgres` | `runtime-os-temporal-db` | `data/temporal`，工作流数据库 |
| `runtime-os-search` | `runtime-os-opensearch` | `data/opensearch`；配置 `bootstrap/opensearch.yml`、证书 `certs` |
| `runtime-os-silo` | `runtime-os-silo` | `data/silo`；账号文件 `config/silo-user`、`config/silo-password` |
| `runtime-os-redis` | `runtime-os-redis` | `data/redis`；配置 `config/redis.conf` |
| `runtime-os-temporal` | `runtime-os-temporal` | 工作流状态保存到 `runtime-os-temporal-db` |
| `runtime-os-api` | `runtime-os-api` | `data/api` |
| `runtime-os-mcp` | `runtime-os-mcp` | `data/mcp` |
| `runtime-os-news` | `runtime-os-news` | `data/news`，使用独立 PostgreSQL 和外部 Temporal |
| `runtime-os-node` | `runtime-os-node1`、`runtime-os-node2`、`runtime-os-studio` | `n`，会话、工作区和审计 |
| `runtime-os-node` | `runtime-os-executor1`、`runtime-os-executor2`、`runtime-os-studio-executor` | `n`，执行器套接字和策略 |
| `runtime-os-node` | `runtime-os-recovery1`、`runtime-os-recovery2` | `n`，恢复状态 |
| `runtime-os-api` | `runtime-os-worker1`、`runtime-os-worker2` | `data/worker1`、`data/worker2`，Native 工作流执行进程 |
| `runtime-os-frontend` | `runtime-os-frontend-resource-hub`、`runtime-os-frontend-worker` | `data/frontend` |
| `runtime-os-daemon` | `runtime-os-executor-daemon` | `d/strict`，专用执行引擎及离线执行镜像 |
| `runtime-os-gateway` | `runtime-os-gateway` | 无业务数据；默认对外端口 18100、18101 |
| `runtime-os-installer` | `runtime-os-manager` | 安装目录；自动维护执行器的进程身份绑定 |

## 单个容器的启动、停止和日志

完成首次安装后，所有服务均可通过 Docker 管理。例如：

```bash
# PostgreSQL
docker stop runtime-os-postgresql
docker start runtime-os-postgresql
docker logs --tail 100 runtime-os-postgresql

# OpenSearch：沿用原来的索引目录、配置和证书
docker stop runtime-os-opensearch
docker start runtime-os-opensearch
docker logs --tail 100 runtime-os-opensearch

# Silo：沿用原来的文件目录和账号文件
docker stop runtime-os-silo
docker start runtime-os-silo
docker logs --tail 100 runtime-os-silo

# Redis：沿用原来的持久化目录和配置
docker stop runtime-os-redis
docker start runtime-os-redis
docker logs --tail 100 runtime-os-redis
```

其他服务使用表中对应容器名，命令相同。整套系统的停止和恢复使用 [README 中的 `stop`、`start`](README.md#查看停止和恢复)，安装镜像会按依赖顺序等待服务就绪。

## 查看每个镜像的实际启动参数

每次安装在外置目录生成 `compose.json`，包含全部服务的准确镜像、启动命令、环境变量、数据挂载和健康检查。可直接使用镜像内置工具读取，宿主机无需安装 Compose：

```bash
docker run --rm --pull=never \
  --entrypoint docker \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /data/runtime-os:/install \
  runtime-os-installer:20261115-docker-only \
  compose -p runtime-os -f /install/compose.json config
```

输出含本次安装的数据库密码等私有配置，按私密运维资料保管。

## 镜像构建与发布

开发人员使用 [BUILD.md](BUILD.md) 的统一构建命令。业务镜像先由源码在构建服务器编译；`prepare-build.py` 将配套工件封装并导出所有镜像。

| 镜像 | 构建或来源 |
| --- | --- |
| API、工作流 worker、MCP | 源码编译镜像，构建时生成 `Dockerfile.api`、`Dockerfile.mcp`，加入 JDBC 工件 |
| News | 源码编译的 News 镜像 |
| Node、执行器、恢复器、Studio | 源码编译的 Native 镜像，构建时生成 `Dockerfile.node` |
| PostgreSQL | Docker Hub `postgres:18.6` 原始镜像，发布为固定版本的产品标签 |
| Redis | Docker Hub `redis:7.4-alpine` 原始镜像，发布为固定版本的产品标签 |
| Silo | Docker Hub `pgsty/silo:latest` 的本次已审核镜像 ID，发布为固定版本的产品标签 |
| OpenSearch | `opensearchproject/opensearch:2.19.1`，通过 `schema-tools/scripts/prepare-search-image.py` 加入匹配的 ICU、IK、pinyin 工件 |
| Temporal | Docker Hub `temporalio/auto-setup:1.29.7` 原始镜像，发布为固定版本的产品标签 |
| 浏览器预览服务 | 已构建的 `runtime-frontend-resource-hub:1.0.0`，固定为本次产品镜像 ID |
| 专用执行引擎 | [Dockerfile.daemon](Dockerfile.daemon)，基于 `docker:27.5.1-dind` |
| 网关 | [Dockerfile.gateway](Dockerfile.gateway)，基于 `nginx:1.28-alpine` |
| 安装器、管理器 | [Dockerfile.installer](Dockerfile.installer)，封装 Docker CLI、Compose、初始化工具和完整镜像清单 |

发布交付 `runtime-os-20261115-docker-only.tar.gz`、`SHA256SUMS`、`release-images.json` 及本目录文档。相同版本标签对应固定的镜像 ID；正式升级另发版本及对应迁移流程。
