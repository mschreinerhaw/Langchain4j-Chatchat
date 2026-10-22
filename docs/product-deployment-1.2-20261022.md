# 灵动智析 1.2 产品部署手册

版本：`1.2.0-20261022`  
发布日期：2026-10-22（北京时间）  
发布分支：`release/1.2-20261022`  
适用范围：Linux x86_64、Docker Engine、Docker Compose v2；既有 PostgreSQL、OpenSearch、Redis 和产品授权服务。

## 1. 本版变更

- 任务初次委派及恢复后重新委派前，实时检查 MCP 产品授权。
- 授权过期时停止新委派，提示：**您的产品授权已过期，任务委派已暂停。请联系管理员更新授权后重试。**
- 授权无效、授权服务不可用时暂停新委派；更新授权后重试会重新检查。查询、取消和已有操作的结果核对仍可执行。
- API、MCP、News 从干净 Java 基础层构建；API 包含 SQL Studio 所需的 Python 3 与 SQLGlot 30.23.0。
- 应用镜像实行 1200 MiB 大小门槛和单一业务 JAR 层检查；修复历史部署脚本基于上一版业务镜像继续叠加 JAR 的问题。
- Coding Agent 在本版没有代码变更，离线镜像包复用服务器既有的已验证镜像，并在构建记录中注明来源。

## 2. 部署架构与端口

| 服务 | 职责 | 当前服务器入口 |
| --- | --- | --- |
| gateway | Runtime 和 MCP 的统一反向代理 | `18100` / `18101` |
| api | 产品页面、会话、工作区及任务编排 | 仅回环 `18180` |
| mcp | 授权、工具控制面及 Python 调度 | 仅回环 `18190`；内部 gRPC `9091` |
| news | News Runtime | 仅容器网络 `8091` |
| coding-agent | Cline 执行器 | 隔离网络 `3100` |
| node-proxy | API 回环到执行器的代理 | API 网络命名空间 `8791` |
| Python Sandbox | 按任务创建的短期执行容器 | 不公开端口 |

本次部署服务器：`192.168.195.221`，SSH 端口 `5221`。产品入口：`http://192.168.195.221:18100/`；MCP 管理入口：`http://192.168.195.221:18101/`。SSH 账户与产品账户分别管理；口令不写入发布包、Git 或文档。外部访问按现场网关配置接入 HTTPS。

## 3. 发布包及目录

本版发布目录为 `/opt/chatchat-releases/1.2.0-20261022`。

| 路径 | 内容 | 管理要求 |
| --- | --- | --- |
| `release-manifest.json` | 版本、构建源码提交、JAR 校验值 | 与发布包一起保留 |
| `jars/` | API、MCP、News 可执行 JAR | 不包含业务数据和部署密钥 |
| `deploy/docker/application/` | 干净基础镜像构建模板及 Compose 示例 | 新装时按现场参数配置 |
| `scripts/release-product.py` | 现有服务器的构建、升级、验证、回滚和镜像清理工具 | 从解压目录执行 |
| `images.tar.gz` | 完整应用镜像、网关和 Python Sandbox 的离线镜像归档 | 通过 SHA-256 后加载 |
| `SHA256SUMS` | 离线发布文件校验清单 | 安装前校验 |
| `evidence/` | 镜像大小、部署、验收、清理报告 | 公开报告须脱敏 |
| `private-backup/` | 升级前配置、原始 JAR、数据库备份 | 仅服务器 root 可读；不进入便携发布包 |

便携包不携带授权文件、真实环境变量、数据库口令、私钥、内部凭据密钥或用户数据。新主机必须另行配置这些内容。升级包与完整离线镜像包的具体文件名、大小和校验值，以本次发布验收记录为准。

## 4. 安装前准备

1. 确认 Linux x86_64、Docker Engine 与 Compose v2 可用；服务器端脚本要求 Python 3.9 以上。
2. 按发布清单预留磁盘空间：需要同时容纳新镜像、压缩镜像归档和升级前备份。先执行 `df -h`、`docker system df`，不要用各镜像显示大小简单相加估算实际磁盘占用，因为镜像会共享基础层。
3. 备份 API/MCP PostgreSQL 数据库、会话 RocksDB、对象存储及 OpenSearch。会话恢复同时依赖关系库索引、RocksDB 与 OpenSearch 正文，单独备份 JAR 或关系库不构成完整数据备份。
4. 暂停新任务入口并确认正在执行的任务已结束。发布工具在容器替换前检查任务表；有活动任务时直接退出，不强行取消。
5. 确认有效授权匹配宿主机网卡身份。沿用 `/sys/class/net/ens192/address` 的只读挂载；更换容器不更换宿主机授权身份。
6. API 与 MCP 必须同步升级：本版新增内部签名接口 `/internal/v1/license/coding-dispatch-entitlement`。旧 MCP 缺少接口时，新 API 将暂停委派并提示检查授权服务。

## 5. 现有服务器升级步骤

当前现场继续使用 `/opt/runtime-os-docker-stage/compose.yaml` 和 `config/deployment.env`，保留原数据库、持久化目录、模型配置及密钥挂载。**不要用发布包内的通用 Compose 示例覆盖现场配置。**

```bash
# 在服务器上进入已解压且已核验的本版目录。
cd /opt/chatchat-releases/1.2.0-20261022
sha256sum -c SHA256SUMS

# 离线镜像包存在时先加载；gzip 管道可直接交给 docker load。
gzip -dc images.tar.gz | docker load

# 使用原现场配置切换应用服务。此命令会生成私有备份和精简回滚镜像。
python3 scripts/release-product.py deploy \
  --release-dir "$PWD" \
  --java-base runtime-os-java-base:temurin17-jammy-20261022

python3 scripts/release-product.py verify --release-dir "$PWD"
```

自动升级流程保留现场 Compose 参数，备份配置和原始 API/MCP JAR，创建干净基础层上的回滚镜像，并备份 API/MCP 数据库。随后替换 MCP、News、API 和 API 网络命名空间的 node-proxy；就绪后刷新 gateway 的上游解析并恢复既有执行器网络限制。失败时恢复配置并切回回滚镜像。

该工具默认检查现场 `runtime_os_acceptance_api`、`runtime_os_acceptance_mcp` 数据库及 `18180/18190` 回环端口；其他部署环境应先按实际数据库和端口调整脚本。工具不会自动恢复数据库备份；本版无业务数据库迁移。若后续版本有破坏性迁移，需单独制订数据库回滚方案。

## 6. 新服务器安装要点

1. 从离线包加载镜像，或在可联网构建机上执行 `deploy/docker/application/build-images.ps1`。构建脚本从根 POM 自动读取版本，不能手工选择旧 JAR。
2. 以 `deploy/docker/application/compose.yaml` 为模板建立现场目录，配置 API、MCP 各自的 PostgreSQL 数据源、既有 OpenSearch、News 配置和对象存储。按 `database/init/postgresql/` 的 SQL 初始化新库并复核实体与迁移版本；生产环境使用已审核迁移。
3. 设置 `API_IMAGE`、`MCP_IMAGE`、`NEWS_IMAGE`、`NODE_IMAGE`、`NODE_PROXY_IMAGE`，以及 `API_DATA_DIR`、`MCP_DATA_DIR`、`NEWS_DATA_DIR`、`NEWS_CONFIG_DIR`、`MODEL_CONFIG_FILE` 等必需路径。数据目录必须预先存在。
4. 分别准备 `api.env`、`mcp.env`、`news.env`；设置内部凭据与 Cline 共享密钥文件。Node 的共享密钥须与 API 一致且允许容器 UID/GID 读取。宿主机目录与环境文件按最小权限保存。
5. MCP 挂载 Docker socket 以启动 Sandbox；Python 数据路径须在 MCP 容器与宿主机保持同一绝对路径。对象存储密钥、授权和用户数据通过外部配置或持久化挂载提供。
6. 执行 `docker compose --env-file ... -f ... config -q`，确认展开无误后 `up -d --no-build`。
7. 安装匹配本机身份的有效授权，并在 MCP 中配置、发布 Python 环境。加载镜像不等于发布 Python 环境；环境配置中的镜像名必须与已加载镜像一致。
8. 新部署须补齐本现场已有的共享输入、对象存储和执行器监控配置；具体参考同目录 `README.md`、`shared-inputs.compose.yaml` 及实际已审核的现场 Compose。

## 7. 验收标准

- API 页面、MCP 管理页面及 `/monitor/` 可访问；服务日志没有持续启动异常。
- API 到 MCP 的 HTTP、gRPC 通路正常；授权签名接口返回当前真实授权状态。
- `/usr/bin/python3` 可导入 SQLGlot 30.23.0，SQL Studio 能解析简单 SQL。
- Coding Agent 健康检查通过，既有数据挂载、配置值与非应用服务保持一致。
- 通过自动测试验证授权过期、无效、不可用时不接受新委派；更新授权后可再次委派。**不得为验收修改在线真实授权或服务器系统时间。**
- 通过正常登录后的问答、历史会话及一次小规模分析进行业务抽查；HTTP 200 或容器运行状态不能替代业务验收。无法完成的项目应在验收记录中明确标记。

## 8. 回滚

```bash
cd /opt/chatchat-releases/1.2.0-20261022
python3 scripts/release-product.py rollback --release-dir "$PWD"
```

回滚使用本版发布前原始 JAR 与干净运行时重新封装的 `1.2.0-20261022-rollback` API/MCP 镜像，并恢复原 News 镜像及原配置，不依赖 33 GB 的旧叠加镜像。原始配置、JAR 与数据库备份保存在 `private-backup/`，只允许 root 访问。需要恢复业务数据时，应先停写，再按数据库、RocksDB、OpenSearch、对象存储的一致性恢复方案执行。

## 9. 镜像大小与安全清理

本次发现的根因是历史脚本不断执行 `FROM 上一次业务镜像` 加 `COPY app.jar`，使每次升级都把一个新 JAR 留在新层；覆盖文件不会从旧层回收空间。API 在线显示大小已达约 33.3 GB。修复后的镜像从纯运行时基础层开始，每版仅有一个业务 JAR 层。

```bash
docker image inspect --format '{{.Size}}' runtime-os-api:1.2.0-20261022
docker history --no-trunc runtime-os-api:1.2.0-20261022
docker system df

# 完成验收并写入 evidence/acceptance.json 的 PASSED 记录后：
python3 scripts/release-product.py cleanup --release-dir "$PWD"
```

清理工具只选择未被任何容器引用的旧 API/MCP/Coding Agent 发布标签，保留本版和精简回滚版本；不删除容器、数据卷、数据库、对象存储或用户文件。停止状态的容器仍保护其引用镜像。禁止把 `docker system prune -a --volumes` 作为本产品升级步骤。

镜像空间与日志、构建缓存、历史验收目录分别计量。现场历史 evidence 目录约 28 GB，不属于镜像；其中可能包含备份和业务数据，本次不按“无效镜像”删除。日志另设轮转和保留期限；Docker JSON 日志建议每文件 20 MB、保留 5 个，在下次受控容器重建时生效。

## 10. 离线基础层的来源

现场 Docker Hub 连接失败时，本版从宿主机已安装的 Temurin 镜像中提取原始 Java 运行时层：定位 Temurin 的 `__cacert_entrypoint.sh` 构建边界，校验保留层的 SHA-256，剔除所有业务层，然后导入独立 `runtime-os-java-base` 镜像。该操作复用现场既有信任，不宣称重新完成上游签名验证。详细来源和层摘要保存在 `base-provenance.json`。

在线构建环境应优先使用经组织批准、通过 digest 固定的官方运行时基础镜像。离线包应同时保存实际镜像 ID、JAR 哈希、源码提交和镜像归档校验值，确保部署可追溯。
