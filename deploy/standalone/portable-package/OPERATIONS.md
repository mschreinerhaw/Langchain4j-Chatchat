# Runtime OS 离线部署生产运维手册

适用于使用 `install-config/install.sh` 安装的 Linux amd64 单机 Compose 环境。安装、初始密码及产品授权见 [安装指南](README.md)。以下操作以 `/opt/ros21` 为安装目录。实际目录、Compose 项目和发布端口以 `install-config/manual-install.env` 为准。

## 1. 确认操作对象

切换到目标机的 root Bash 会话，进入包含 `install-config/` 和 `offline-images/` 的交付目录。新开 SSH 会话时重新执行本段。下文的 `compose` 是在当前 Bash 会话定义的函数。

```bash
CONFIG="$(pwd -P)/install-config"
source "$CONFIG/manual-install.env"
export DOCKER_CONFIG="$CONFIG/docker-config" DOCKER_HOST=unix:///var/run/docker.sock
unset DOCKER_CONTEXT
DOCKER_BIN=${DOCKER_BIN:-$(command -v docker)}
compose() { "$DOCKER_BIN" compose --project-directory "$ROOT" -f "$ROOT/compose.json" "$@"; }
compose config --quiet
compose config | sed -n 's/^name: //p'
compose --profile '*' config --services
compose ps -a
```

确认输出的项目名与 `$PROJECT` 一致，再执行变更。`DOCKER_BIN` 为空时，先按安装指南配置 Docker CLI 的绝对路径。`compose` 的参数使用**服务名**，例如 `gateway`、`mcp` 或 `worker1`。完整容器名可从 `compose ps -a` 查看。

## 2. 单个服务和整套系统启停

| 操作 | 命令 |
| --- | --- |
| 停止一个容器对应的服务 | `compose stop -t 30 gateway` |
| 启动已有的容器 | `compose start gateway` |
| 重启已有的容器 | `compose restart -t 30 gateway` |
| 创建缺失的网关容器，并等待健康 | `compose up -d --no-deps --wait --wait-timeout 90 --pull never gateway` |
| 显示服务状态与健康状态 | `compose ps -a gateway` |
| 查看最近 15 分钟日志 | `compose logs --since 15m --tail 200 gateway` |
| 持续跟踪日志 | `compose logs -f --tail 100 gateway`，按 Ctrl+C 退出日志查看 |

把 `gateway` 换成实际服务名。`start` 用于已经创建的容器。`up` 会按当前配置和本机镜像新建或替换容器。执行带 `--no-deps` 的单服务操作前，先确认它依赖的服务已健康。示例中网关依赖 `api` 和 `mcp`。`api` 被替换后，使用相同发行版的 `deploy` 恢复共享其网络命名空间的 Worker、Recovery 和执行器。停止数据库、OpenSearch、Temporal 或 `executor-daemon` 前，先停止新流量，并确认关联任务完成或已按业务流程取消。

整套停机和恢复：

```bash
compose stop -t 90 gateway
# 确认运行中的任务已经处理完毕后再继续。
compose stop -t 90
compose ps -a

# 使用同一发行版离线镜像和安装配置恢复。
bash "$CONFIG/install.sh" deploy
bash "$CONFIG/install.sh" status
```

保留 `$ROOT` 和该版本的镜像。`deploy` 会按顺序启动基础服务、执行可重复的初始化、恢复应用并刷新验收结果。日常停机使用 `stop`。`down -v`、删除 `$ROOT` 或清理 Docker 卷会影响恢复。

## 3. 离线升级

### 升级前

1. 取得新版介质、同一发行版的安装配置、发行说明和数据库迁移说明。现场确认当前发行版到目标发行版有明确的升级路径。计算升级窗口、可用空间和快照保留周期。
2. 在新版的 `offline-images/` 执行 `sha256sum -c SHA256SUMS`。对照新旧 `images.tsv`。相同标签对应不同摘要时，在旧环境运行期间避免向该 Docker Engine 加载新版介质，因为镜像标签会被替换。保留**上一版完整介质和说明**供回滚使用。
3. 记录当前 `$ROOT`、项目名、端口、`verification.json` 和容器状态。暂停入口流量和调度，处理运行中任务，按第 2 节停止整套服务。确认 `compose ps --status running -q` 无输出后备份。

### 冷备份和恢复点

以下示例在目标机执行。把 `BACKUP_PARENT` 改成实际已挂载且空间充足的备份位置。备份包含数据库、私有执行器状态、授权、配置和凭据，访问权限限制为管理员。备份文件放在 `$ROOT` 之外。

```bash
(
  set -euo pipefail
  umask 077
  BACKUP_PARENT=/mnt/disk01/runtime-os-backups
  install -d -m 0700 "$BACKUP_PARENT"
  case "$(realpath -e -- "$BACKUP_PARENT")/" in
    "$(realpath -e -- "$ROOT")/"*) echo '备份目录必须位于安装目录之外。' >&2; exit 1 ;;
  esac
  RUNNING=$(compose ps --status running -q)
  test -z "$RUNNING" || { echo '仍有运行中的容器，停止备份。' >&2; exit 1; }
  BACKUP=$(mktemp -d "$BACKUP_PARENT/$PROJECT-XXXXXX")
  tar --numeric-owner --acls --xattrs -C "$ROOT" -cpf "$BACKUP/installation.tar" .
  install -m 0600 "$CONFIG/manual-install.env" "$BACKUP/manual-install.env"
  cd "$BACKUP"
  sha256sum installation.tar manual-install.env > SHA256SUMS
  sha256sum -c SHA256SUMS
  printf '%s\n' "$BACKUP"
)
```

如果业务使用外部 PostgreSQL、Silo 或其他位于 `$ROOT` 之外的存储，按对应服务的备份流程同步取得恢复点。恢复前应在隔离环境验证备份可读和版本匹配。

### 选择升级方式

- **配置补丁**，例如这次监控入口修复。保留现有 `manual-install.env`，换用修订版安装脚本，运行 `monitor-repair`。命令只重建网关，完成后验证 `/monitor` 的跳转和页面。
- **原地升级**，在发行方提供从当前版本到目标版本的迁移流程时使用。在维护窗口内校验并加载新版、按发行说明执行迁移和容器切换，再执行该版本的验收。新建安装脚本用 `imageIdentities` 校验发行版。遇到 `This installation belongs to another product image set` 时，记录目标版本及摘要，按版本迁移流程处理。直接修改 `installation.json` 摘要或在旧目录重新运行 `prepare` 会破坏版本校验。
- **并行换版**，适用于缺少原地迁移流程时。使用独立 Docker 主机，或使用唯一、不覆盖旧版本的镜像标签，并为新环境设置新的 `ROOT`、`PROJECT` 和未占用的 `HTTP_PORT`、`MCP_PORT`。执行新版 `install`，再按发行方支持的方式迁移业务数据、重配外部服务并核对产品授权。全新安装会生成自己的证书和凭据。验收通过后再切换入口流量。旧环境保留到回滚期结束。

上线验收至少检查 `verification.json` 为 `PASSED`、各容器健康、应用与 MCP 登录、授权有效、`/monitor` 保留发布端口，以及一次使用已授权数据的 Coding 任务。只在用户流量切换前的冻结点完成数据核对和验收。

### 回滚

并行换版失败时，先停止新版接收流量，再切回旧环境。新版已经接收写入时，先核对新增数据并按业务恢复方案处理。原地升级失败时，停止入口和新版容器，保留失败现场。从上一版介质恢复镜像和配置并按上一版迁移说明回退。若已执行数据库迁移，使用审核过的逆向迁移或恢复升级前的一致备份；应用镜像和数据库恢复点必须配套。恢复整个安装目录时，使用同一 `$ROOT`，保留权限和属主。备份内的密钥、证书及授权跟随相应环境恢复。恢复完成后，用**上一版**安装脚本的 `deploy` 和 `status` 复验，再开放流量。

整目录恢复示例仅适用于同一主机上、已停机且 `$ROOT` 中的数据与升级前备份配套的部署。先按第 1 节核对目标，并设置 `BACKUP` 为已确认的升级前备份目录。若安装目录本身是挂载点，使用该文件系统的快照恢复流程。

```bash
(
  set -euo pipefail
  test "$ROOT" != / && test "$(realpath -e -- "$ROOT")" = "$ROOT"
  RUNNING=$(compose ps --status running -q)
  test -z "$RUNNING"
  (cd "$BACKUP" && sha256sum -c SHA256SUMS)
  FAILED_ROOT="$ROOT.failed-$(date +%Y%m%d-%H%M%S)"
  test ! -e "$FAILED_ROOT"
  mv -T -- "$ROOT" "$FAILED_ROOT"
  install -d -m 0700 "$ROOT"
  tar --numeric-owner --acls --xattrs -C "$ROOT" -xpf "$BACKUP/installation.tar"
  printf '失败现场已保留在 %s\n' "$FAILED_ROOT"
)
```

在上一版交付目录中恢复 `manual-install.env`，核对其中的安装路径、项目及发布端口，再依次执行上一版的 `load`、`deploy`、`status`。如果回滚过程中写入过外部存储，还需同步恢复外部存储对应的版本和恢复点。

## 4. 常见问题

| 现象 | 排查与处理 |
| --- | --- |
| `sudo` 下 `docker: command not found` | 在平时运行 Docker 的会话用 `command -v docker` 获取路径，把绝对路径写到 `manual-install.env` 的 `DOCKER_BIN`。没有 Docker CLI 时先安装 Docker Engine 和 CLI。 |
| `ROOT: unbound variable` 或 `:/install:ro` | 新 SSH 会话缺少前一会话的配置。重新执行第 1 节，或直接使用 `bash install-config/install.sh password` 等独立命令。宿主机仅有 Python 2 或没有 Python 时，继续使用 Bash 安装入口，Python 脚本由安装器镜像运行。 |
| 镜像归档校验失败或镜像摘要不符 | 在交付目录的 `offline-images/` 执行 `sha256sum -c SHA256SUMS`，确认归档和 `images.tsv` 同属一版。校验通过后执行 `bash install-config/install.sh load`。若加载后摘要仍有差异，记录 Docker 存储类型和实际镜像摘要，由发行方核对介质与当前引擎。 |
| OpenSearch `unhealthy`、重启或初始化失败 | `compose logs --tail 200 opensearch`，检查 `sysctl vm.max_map_count` 至少为 `262144`，并检查 Docker 数据盘和安装目录所在磁盘。需要时执行 `sysctl -w vm.max_map_count=262144`，复验。OpenSearch 启动后仍要通过安装脚本执行安全配置初始化。 |
| `bootstrap-compose` 卡在 `news`，或 Recovery 没有健康检查 | 查看 `compose logs --tail 200 news temporal opensearch recovery1 recovery2`。核对镜像和安装器为同一代完整介质，通过 `compose --profile '*' config` 检查 Recovery 服务是否有健康检查。修复依赖后执行同版本 `deploy` 续跑，保留现有数据。 |
| `No skill is registered`、Coding 分析未启用或缺表 `runtime_user_workspace` | 核对是否使用包含空 Agent 回退、Native Coding 配置和用户资产迁移的修复版。旧环境按第 3 节走对应版本升级。修复版的新安装通过 `deploy` 执行受控迁移，并在重新提交任务后检查结果。 |
| `/monitor` 跳到 `8080`，或 `/monitor/` 返回 500 | 浏览器使用 `HTTP_PORT`，默认 `18100`。运行 `bash install-config/install.sh monitor-repair`，从浏览器所在机器确认 `/monitor` 返回 `Location: /monitor/`，跟随跳转仍在原宿主机端口且返回 200。 |
| 授权页显示 `NOT_INSTALLED`、`INVALID_SIGNATURE` 或 `SERVER_MISMATCH` | 核对 `$ROOT/data/mcp/license/` 下的 `license.dat` 和 `license-public.pem` 来自同一授权包，MCP 的 UID 10001 可读，并与授权页的 MAC 匹配。首次安装或换公钥后执行 `compose restart mcp`，回授权页刷新。 |
| 应用能登录，热点分析只得到 `PARTIAL_SUCCESS` | 查看任务结果与数据来源。如果结果报告未获得授权输入，检查当前 Agent、模型与已授权数据源，再提交新任务。容器健康和业务数据就绪分别验收。 |
