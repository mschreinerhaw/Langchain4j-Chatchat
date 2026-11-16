# Runtime OS Deployment Manager · P0

Deployment Manager 在现有独立 `deployment-ops/` 模块中实现，复用其认证、H2、Docker 代理、审计和离线安装基础，不另建依赖 Runtime API 的控制服务。

## 边界与架构

浏览器 → 独立 Java 17 Web / H2 → 私有网络或 HTTPS → Python Node Agent → 本机 Unix Docker Socket。

- Web 没有 Docker Socket、宿主目录或命令执行接口；代理不提供通用 Shell。
- Java 使用已有 JDK HTTP 服务和离线 Jackson/H2 依赖；本期不迁移 Spring Boot。
- 前端使用本地 HTML、CSS、JavaScript、SVG 和系统字体；不需要 npm、CDN 或公网认证。
- Web 与代理的持久化目录分别保存 H2 和代理 SQLite 操作账本/采样。代理没有外部数据库依赖。
- 业务容器不依赖管理平台、不加入管理平台 Compose 项目；管理平台关闭不触发业务容器 stop/down。
- 主机指标来自只读 `/proc` 和主机根文件系统挂载。代理已有 Docker 管理权限，应按宿主管理员权限保护其令牌、网络、备份和只读宿主挂载。
- 当前 H2 为单实例文件数据库，不支持多 Web 实例同时共享数据库文件。

## 默认只读与审批

初次启动保持 `OPS_READ_ONLY=1`。此时只能发现资源、读取指标和日志、预览操作，不执行 Docker 写操作，也不重放旧安装恢复动作。

部署负责人在完成资源基线审查后可明确设置 `OPS_READ_ONLY=0`，再仅重建代理。操作人员仍须完成：选择目标 → 查看真实配置、挂载及外部 Compose 来源 → 输入完整目标名称 → 提交一次性审批计划。计划绑定操作者、资源快照，5 分钟过期；提交和执行前重新检查资源状态，发生变化必须重新预览。重复请求使用幂等键返回同一任务。

控制面容器禁止从业务操作入口修改。共享网络命名空间的被依赖容器不能被单独停止、重启或删除。容器删除要求先停止，始终 `force=false&v=false`；不删除数据卷或宿主目录。镜像删除检查全部运行中及已停止容器引用；禁止强删、标签覆盖和全局 prune。外部数据卷只能只读挂载到新容器，不允许宿主 Bind Mount 或特权选项。

P0 不提供批量写入、终端或通用 exec。审批只覆盖本次指定资源，不代表对发现的其他现有资源授权。

## 操作入口

默认首页 `/` 或 `/manager.html` 为新的中文管理工作台；原签名包安装和维护界面保留在 `/index.html`。

1. 接入节点：先部署 `compose.node.yaml`，使用独立令牌及 TLS 服务端证书；在主机管理登记 HTTPS 地址。私有 CA 通过 Java truststore 配置。远程 HTTP 注册被拒绝。
2. 监控总览：查看在线节点、已发现容器、镜像、应用标签分组和真实指标。节点失联时不显示旧状态为正常；统计注明仅包含可连接节点。
3. 容器管理：创建、启动、停止、重启、删除；查看脱敏参数、挂载、真实 CPU/内存与日志。创建时指定本地镜像、运行 UID:GID、命令参数、环境变量、端口、命名卷、网络、重启策略、资源限制和健康检查。运行用户须与镜像及数据目录权限一致，不自动放宽文件系统权限。
4. 镜像管理：查看实际 Image ID/标签/容器引用，导入带 SHA-256 的 Docker save TAR，导出保留标签的 TAR、添加新标签、删除无引用镜像。拉取仅允许代理配置的 `OPS_ALLOWED_REGISTRIES`，已有标签不允许被覆盖。
5. 操作任务：显示 `PENDING / RUNNING / SUCCESS / FAILED / CANCELLED / UNKNOWN`。关闭页面不取消后台任务。代理重启后未确认完成的任务变为 UNKNOWN，需核对真实 Docker 状态后重新审批，不自动重放。仅未开始的 PENDING 任务可经 API 取消。
6. 日志与指标：日志使用有认证的 SSE，每 2 秒最多 200 行，单次连接 30 秒、自动重连，Web 最多同时 4 个日志流；关闭详情即断开。主机每 10 秒采样；最多 128 个运行容器每轮 30 秒采样。采样保留 7 天，界面显示最近 120 个点。超过 90 秒的容器样本视为过期；没有有效 CPU 差分时显示未知，不填模拟 0。
7. 系统设置：独立 admin / viewer，viewer 不能预览、确认或执行写入。用户更新会撤销该用户已有会话。

Web 镜像导入上限为 256 MiB。更大文件使用已有签名离线包的分块传输工作流。主机磁盘指标明确为根文件系统 `/`，不代表所有挂载点或 Docker 存储盘；容器 CPU 可超过 100%，不能当作整机使用率。

## 开发与源码构建

从 `deployment-ops/` 执行：

```sh
python3 scripts/build-frontend.py
python3 -m unittest discover -s tests -p 'test_*.py'
node --check frontend/manager-ui.js
docker compose build --pull=false
```

Java/H2/Jackson 已在源码目录提供，不运行 Maven/npm/pip 安装。离线构建机预先加载 Java、Python 基础镜像。代理 Dockerfile 的 `PYTHON_IMAGE` 参数支持已有受信任 Python 基础镜像，源码验收记录必须注明实际基础镜像。使用独立镜像标签构建和验收，禁止覆盖生产标签。

## 离线交付

开发构建机准备好两个最终镜像后：

```sh
python3 scripts/build-manager-offline.py \
  --web-image deployment-manager-web:VERSION \
  --agent-image deployment-manager-executor:VERSION \
  --output /media/runtime-manager-VERSION
```

输出包含 `images/manager-images.tar`、SHA256SUMS、images.lock.json、compose.yaml、.env.example、trust/、安装/升级/备份/恢复脚本。最终镜像包含全部运行依赖，无需联网拉取。

新环境安装前先只读检查目标 Docker 环境。创建 `.env`，设置独立随机凭据，保留只读模式及 loopback 地址。明确确认新安装后执行：

```sh
MANAGER_INSTALL_NEW=yes sh install.sh
```

安装器拒绝已有 deployment-ops 容器、已有同名数据卷、已有不同镜像的相同标签，校验归档及实际 Image ID。不能用新安装脚本接管已有平台。

升级须先备份、审查 Compose 差异和版本兼容性，保留已有 `.env`；版本镜像必须使用新标签：

```sh
sh backup.sh /secure/manager-backup-VERSION
MANAGER_UPGRADE_REVIEWED=yes sh upgrade.sh
```

备份只停止管理端和代理，关闭 H2/SQLite 后复制管理元数据，不停止业务容器。签名业务镜像包存放在 ops-packages 中，需另行保存；管理元数据备份不复制业务数据和业务镜像。

恢复时先停止管理端和代理，再运行 `sh restore.sh /secure/manager-backup-VERSION RESTORE-MANAGEMENT`。仅允许匹配管理镜像版本的备份；恢复写入新卷，同时恢复对应的凭据和 trust 配置，保留原有卷、Compose、凭据及 trust 目录。恢复后审查并显式启动。恢复只针对管理面，禁止据此恢复或回滚业务数据库。

## API

全部资源 API 需本地会话 Cookie；写请求还需 admin 和 `X-Ops-Request: 1`。远程节点令牌仅保存在 H2，不返回浏览器。

| API | 用途 |
| --- | --- |
| GET /api/inventory | 实际 Docker 镜像与全部容器，包含最新容器采样 |
| GET /api/nodes/{id}/manager/policy | 节点只读状态与仓库白名单 |
| GET /api/nodes/{id}/manager/host、history | 主机实时指标及真实历史样本 |
| GET /api/nodes/{id}/manager/networks、volumes、events | 网络、数据卷、Docker 事件 |
| GET /api/nodes/{id}/manager/containers/{cid}/metrics、history | 容器指标和历史 |
| GET /api/nodes/{id}/manager/logs/{cid} | 有认证的 SSE 日志 |
| POST /api/nodes/{id}/manager/plans | 只读校验并生成脱敏预览 |
| POST /api/nodes/{id}/manager/execute | 提交 planId、confirmation、idempotencyKey |
| GET /api/nodes/{id}/manager/jobs[/{jobId}] | 持久化操作结果 |
| POST /api/nodes/{id}/manager/jobs/{jobId}/cancel | 仅取消尚未执行的任务 |
| GET /api/nodes/{id}/manager/export/{imageHexId} | admin 导出镜像 TAR |
| POST /api/nodes/{id}/images/import | 验证 SHA-256，拒绝覆盖标签后导入 |

Node Agent 对接官方 [Docker Engine API](https://docs.docker.com/reference/api/engine/version/v1.45/)，当前协议路径固定为 v1.45，运行环境要求 Engine 26+。未调用 docker system prune，也不接受任意 Docker API 路径。

## 验收与阶段范围

自动测试包括角色校验、只读模式、预览不写入、资源状态变化拒绝、镜像引用保护、幂等、未知结果不重放、管理账本重启恢复及历史保留策略。`tests/manager-live.py` 只针对独立 QA 项目、新建测试镜像、测试容器和测试卷执行写操作；`tests/manager-browser.cjs` 通过真实页面验证创建、启动、日志、停止和删除。

本期实现 P0。应用页按真实 Compose/标签分组；应用定义、任意 Compose 编辑/发布、跨版本配置回滚、数据库兼容性检查和批量操作属于后续 P1/P2，不能用单容器操作任务替代这些功能的验收。旧签名包安装器继续保留，不能据此宣称通用 Compose 管理已经完成。

`tests/manager-cold.py` 可在明确指定的新隔离 daemon 中验收空缓存离线安装、冷备份、精确恢复和 daemon 重启。它需要预先存在的受信任 DinD 镜像、至少 8 GiB 空间及测试授权；不挂载生产 Docker Socket，不使用宿主网络或 PID namespace。不得把同物理主机上的隔离 daemon 测试表述为第二台物理机验收。

单物理主机验收不等同于多物理主机验收。不会为了测试而重启生产 Docker daemon、断开生产网络、制造生产磁盘不足或重建 Runtime 业务容器。未执行的场景在验收报告中明确记录，不用模拟结果替代。
