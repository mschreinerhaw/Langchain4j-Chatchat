# 生产容器部署

本目录包含生产启动配置。应用从仓库源码构建，凭据与业务数据不进入镜像。

## 构建

Docker Desktop / Docker Engine 的拉取代理设置为 `https://192.168.36.128:8443`。本机 Docker Desktop 已配置该地址。应用构建统一执行：

```powershell
python scripts/build-production-images.py
```

脚本校验上游 TLS 证书，临时提供 HTTP → HTTPS 转发，让 Maven、apt、npm、Bun 的请求全部经过指定代理。构建完成或失败后关闭转发器。代理使用 Docker 预定义 build args，Maven settings 使用 BuildKit secret，均不保存在运行层。可通过 `--service chatchat-api` 等参数单独构建。日志默认位于工作区外层 `target/production-build/`。

| 模块 | Dockerfile（构建 context 均为仓库根目录） |
| --- | --- |
| API | chatchat-api/Dockerfile |
| MCP | chatchat-mcp-server/Dockerfile |
| News | chatchat-runtime-news/Dockerfile |
| Cline | cline-agent-server/Dockerfile |
| 前端资源库、预览 worker | frontend-resource-hub/Dockerfile.production |

Java 使用 17，多阶段构建并打包实际编译的前端。API 包含哈希锁定的 SQLGlot；MCP 包含 Docker CLI。Cline 从 upstream-pin.json 对应的固定提交构建官方 standalone，使用 Bun 1.4.2，并验证 manifest 与 SQLite 原生模块。前端资源库使用固定 Playwright 1.56.1 浏览器镜像。原资源库 Dockerfile 保留目录级 context，供已有流程使用。

生产应用统一使用 UID/GID 10001，挂载的配置需可读、数据目录需可写。基础镜像可通过相应 build args 覆盖；正式发布应记录并审核镜像 digest。compose.build.yaml 供已有代理转发器的 CI 使用，需设置 BUILD_PROXY（兼容 HTTP CONNECT 的转发地址）和 MAVEN_SETTINGS_FILE；普通本机构建使用上面的脚本。

## 依赖与启动顺序

已对照 `/opt/runtime-os-docker-stage` 的现有及历史配置，纳入：

| 用途 | 镜像 |
| --- | --- |
| 业务库、独立 Temporal 库 | postgres:18 |
| S3 对象存储 | pgsty/silo:latest |
| Temporal schema、namespace 初始化 | temporalio/auto-setup:1.29.7 |
| Temporal 常驻服务 | temporalio/server:1.29.7 |
| 搜索 | opensearchproject/opensearch:2.19.1 |
| 缓存 | redis:7.4-alpine |

PostgreSQL 18 卷挂载到 `/var/lib/postgresql`。业务库空卷初始化复用 `database/init/postgresql` 和 `../application/postgresql-init.sh`。Temporal 按数据库健康 → schema 成功 → server 健康 → 默认 namespace 成功的顺序启动。业务应用等待依赖就绪。OpenSearch 使用现有 TLS 配置和保留已有安全索引的初始化脚本。

## 私有配置

将 RUNTIME_CONFIG_DIR 指向仓库之外的绝对路径，准备：

- `api.env`、`mcp.env`、`news.env`：环境差异配置；不能包含指向宿主服务的 localhost 地址。
- `api/`、`mcp/`、`news/`：只读应用配置，例如 application.yml、internal-credential.key。内部凭据沿用应用要求的 ENC 格式。
- `cline-server.json`：从 cline-server.example.json 复制并填写模型和操作令牌。stateDir 为 `/srv/app/state`，工作区位于 `/workspace`。
- `opensearch-certs/`：证书要求见 `../opensearch/certs/README.md`。
- `search-truststore.p12`：将上述 CA 导入 PKCS12 信任库，密码 changeit，只保存公钥证书。Java 通过只读挂载信任搜索服务。
- `secrets/`：以下每项为独立、不带换行的私有文件。

```text
api_bootstrap_admin_password
postgresql_admin_password
postgresql_api_password
postgresql_mcp_password
postgresql_news_password
temporal_database_password
opensearch_admin_password
redis_password
silo_root_user
silo_root_password
```

Redis/OpenSearch 密码使用 32–128 位 URL-safe 随机字符。应用数据库密码与搜索密码通过 configtree 注入。另从私有环境设置 HUB_TOKEN，资源库与预览 worker 使用同一随机令牌。不要将服务器密码拷入生产配置源码。

Compose 已设置数据库地址、MCP gRPC 地址、News Temporal 地址和 CA 信任库。私有应用配置还需设置内部认证、S3 地址 `http://silo:9000`、桶与凭据、API/MCP/News HTTP 地址、Cline 与业务集成设置。Java 默认 schema validate，数据迁移须先完成；不自动升级已有数据库。

```powershell
$env:RUNTIME_CONFIG_DIR = 'D:/private/chatchat'
# HUB_TOKEN 从私有环境注入。
docker compose -f deploy/docker/production/compose.yaml up -d
docker compose -f deploy/docker/production/compose.yaml ps -a
```

主 compose 文件只启动预构建镜像。包含 init、资源限制、停止宽限期、日志轮转和持久化卷；业务入口只绑定宿主 127.0.0.1，通过 TLS 反向代理提供外部访问。使用 `compose stop` 保留数据。MCP 不自动挂载 Docker socket，需要宿主容器执行能力时，须按实际部署补充 socket/远程 daemon 与对应 GID 权限。

## 验证范围

```powershell
python scripts/smoke-production-containers.py
python scripts/smoke-production-frontend.py
```

启动检查使用临时容器和 H2，关闭外部集成及 MCP gRPC，仅检查非 root 身份、前端 HTML、运行库和 HTTP，不调用真实模型。Java 镜像 HEALTHCHECK 是 TCP 存活检查，Cline 为带认证的 /health/ready，均不能代替生产业务验收。检查结束自动删除测试容器，日志与 JSON 结果默认在工作区外层 target/production-container-smoke/。

Temporal 初始化参考官方 [auto-setup.sh](https://github.com/temporalio/docker-builds/blob/main/docker/auto-setup.sh) 与 [entrypoint.sh](https://github.com/temporalio/docker-builds/blob/main/docker/entrypoint.sh)。Cline 保留现有单操作者服务能力，容器化不改变其租户隔离边界。
