# Runtime OS 1.3.2 可移植安装与授权

手工安装使用 `20261121-r3` 基础介质和 `20261121-skill-r3` 镜像更新。以下以验收主机 `192.168.195.93` 为例。

单服务启停、升级和回滚见交付目录内的 `install-config/OPERATIONS.md`。源码版见 [生产运维手册](portable-package/OPERATIONS.md)。

| 路径 | 安装入口 | 应用数据位置 |
| --- | --- | --- |
| 手工 Compose | `/opt/ros21/compose.json` | `/opt/ros21/data` |
| Deployment Ops Web | 上传 `runtime-os-1.3.2-portable-20261121-r3.ops.zip` | 预检显示的 `/mnt/disk01/ops/r/<安装目录>/data` |

## 1. 手工 Compose

把以下两个目录放在同一个交付目录下。首次安装只需准备这两部分。

| 交付目录 | 内容 |
| --- | --- |
| `offline-images/` | 三份基础镜像归档、`runtime-os-skill-fix-20261121-r3.tar.gz` 更新包、SHA-256 校验文件和镜像摘要清单。 |
| `install-config/` | `manual-install.env`、Compose 插件和本指南。只需在 `manual-install.env` 中确认安装目录、项目名和端口。 |

在 `.93` 上，这两个目录位于 `/opt/runtime-os-portable-20261121/`。安装器根据配置在目标主机生成 `compose.json`、证书和初始密码。

### 1.1 核对主机和介质

使用 Linux amd64，Docker 至少可用 12 GiB 内存，并为镜像解包和业务数据预留磁盘空间。
新环境需要空闲的入口端口。续跑时端口可由本项目占用。以下命令在同一个 Bash 会话中按顺序执行。

```bash
BASE=/opt/runtime-os-portable-20261121
OFFLINE_IMAGES="$BASE/offline-images"
INSTALL_CONFIG="$BASE/install-config"
source "$INSTALL_CONFIG/manual-install.env"
export DOCKER_CONFIG="$INSTALL_CONFIG/docker-config"
docker info --format 'Docker={{.ServerVersion}} Architecture={{.Architecture}} MemTotal={{.MemTotal}}'
docker compose version
df -h "$(dirname "$ROOT")" "$(docker info --format '{{.DockerRootDir}}')"
ss -ltn "sport = :$HTTP_PORT or sport = :$MCP_PORT"
sysctl -w vm.max_map_count=262144
```

首次安装、清理后重装，或任一镜像缺失时，先校验并按顺序加载基础归档和更新包。更新包包含修复后的 API 和安装器。新装环境没有自建 Agent 时，可以提交普通对话。

```bash
(
  set -euo pipefail
  cd "$OFFLINE_IMAGES"
  sha256sum -c SHA256SUMS
  sha256sum -c runtime-os-portable-support.tar.gz.sha256
  sha256sum -c runtime-os-skill-fix-20261121-r3.tar.gz.sha256
  for archive in runtime-os-current-images.tar.gz runtime-os-current-task-image.tar.gz runtime-os-portable-support.tar.gz; do
    docker image load -i "$archive"
  done
  docker image load -i runtime-os-skill-fix-20261121-r3.tar.gz
)
```

### 1.2 检查配置

**从头安装**时，如果 `$ROOT` 不存在或为空，直接执行下方的「生成配置」，再做「核对配置」。
**续跑**时，如果已有 `compose.json` 和 `installation.json`，直接做「核对配置」。
手工流程使用更新包中的 `runtime-os-portable-installer:20261121-skill-r3`。

#### 生成配置

只有在**安装全新环境**时才生成配置。选择空目录，并确认项目名和端口可用。
以下是验收机在 Docker 29 containerd 镜像存储下的生成方法。先核对 OCI digest，再生成文件及证书。
如果同一台主机已有验收项目，请在 `manual-install.env` 中为新环境设置另一个 `ROOT`、`PROJECT`、`HTTP_PORT` 和 `MCP_PORT`，然后重新执行 1.1 的配置加载命令。

```bash
(
  set -euo pipefail
  install -d -m 0700 "$ROOT"
  if [ -n "$(find "$ROOT" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo "安装目录已有内容。续跑请使用现有配置，或为新环境选择另一个空目录。" >&2
    exit 1
  fi
  while IFS="$(printf '\t')" read -r ref accepted; do
    actual=$(docker image inspect --format '{{.Id}}' "$ref")
    case " $accepted " in
      *" $actual "*) ;;
      *) echo "镜像 digest 不匹配：$ref" >&2; exit 1;;
    esac
  done < "$OFFLINE_IMAGES/skill-fix-digests.tsv"
  docker run --rm --pull=never --network host --name "$PROJECT-installer" \
    -e HOSTNAME="$PROJECT-installer" -e PROJECT="$PROJECT" \
    -e HTTP_PORT="$HTTP_PORT" -e MCP_PORT="$MCP_PORT" \
    -v /var/run/docker.sock:/var/run/docker.sock -v "$ROOT:/install" \
    --entrypoint python3 runtime-os-portable-installer:20261121-skill-r3 \
    -c 'import runpy; m=runpy.run_path("/opt/product/installer.py"); app=m["Installation"](); app.prepare(); app.render()'
  cd "$ROOT"
  docker compose -f compose.json config --quiet
)
```

#### 核对配置

```bash
(
  set -euo pipefail
  test -s "$ROOT/compose.json"
  test -s "$ROOT/installation.json"
  cd "$ROOT"
  docker compose -f compose.json --profile '*' config --quiet
  docker compose -f compose.json --profile '*' config --images | sort -u |
    while IFS= read -r image; do docker image inspect --format '{{.Id}}' "$image"; done
)
```

### 1.3 部署并验证

整段执行会在首个失败点停下。依次通过六个基础服务的健康检查、数据库及 Native 配置初始化、
整套 24 个服务的 Compose 健康检查，最后由安装器生成 `verification.json`，其中 `status` 应为 `PASSED`。

```bash
(
  set -euo pipefail
  cd "$ROOT"
  test "$(docker compose -f compose.json config | sed -n 's/^name: //p')" = "$PROJECT"
  installer() {
    docker run --rm --pull=never --network host --name "$PROJECT-installer" \
      -e HOSTNAME="$PROJECT-installer" \
      -v /var/run/docker.sock:/var/run/docker.sock -v "$ROOT:/install" \
      runtime-os-portable-installer:20261121-skill-r3 "$@"
  }
  docker compose -f compose.json config --quiet
  docker compose -f compose.json up -d --wait --wait-timeout 240 --pull never \
    postgresql temporal-db redis opensearch silo executor-daemon
  installer bootstrap-compose
  docker compose -f compose.json up -d --wait --wait-timeout 360 --pull never
  bash "$INSTALL_CONFIG/install.sh" monitor-repair
  installer finalize-compose
  docker compose -f compose.json ps
  cat verification.json
)
```

若失败，按 1.1 加载配置后先执行 `cd "$ROOT"`，再用 `docker compose -f compose.json logs --tail 120 <服务名>` 查看安装器点名的服务。
修复后重新执行 1.3。数据库已提交的初始化和已生效的 OpenSearch 安全配置会保留。
续跑前保留 `installation.json`、`config/`、`certs/` 和 `data/`。暂停使用 `docker compose -f compose.json stop`。

| 安装时的现象 | 处理方式 |
| --- | --- |
| OpenSearch 重启或 `unhealthy` | 检查 `opensearch` 日志和 `sysctl vm.max_map_count`。主机重启后重新检查该值，低于 `262144` 时重新设置。基础服务健康后再初始化。 |
| `bootstrap-compose` 停在 News 健康检查 | 确认安装器镜像版本是 `20261121-skill-r3`，再查看 `news` 日志。保留数据库和 OpenSearch 数据，用相同目录续跑。 |
| `recovery2 has no healthcheck configured` | 在 `docker compose -f compose.json --profile '*' config` 输出中核对 `recovery1` 和 `recovery2` 的健康检查。全新环境按 1.2 生成 R3 配置。 |

使用默认端口时，Runtime OS 入口为 `http://192.168.195.93:18100`，MCP 管理端为 `http://192.168.195.93:18101`。改过端口时，以 `manual-install.env` 为准。
两处初始用户名均为 `admin`。目标机管理员可以在任意 Bash 会话中执行以下命令，读取本次安装生成的初始密码。

```bash
(
  set -euo pipefail
  source /opt/runtime-os-portable-20261121/install-config/manual-install.env
  if [ ! -s "$ROOT/installation.json" ]; then
    printf '找不到安装记录：%s/installation.json\n' "$ROOT" >&2
    exit 1
  fi
  docker run --rm --pull=never --network none --entrypoint python3 \
    --mount "type=bind,source=$ROOT,target=/install,readonly" \
    runtime-os-portable-installer:20261121-skill-r3 \
    -c 'import json; print(json.load(open("/install/installation.json"))["adminPassword"])'
)
```

使用默认应用端口时，Coding 监控入口为 `http://192.168.195.93:18100/monitor/`。出现 HTTP 500、`/monitor` 跳转到内部端口 `8080`，或运行旧安装器的 `bootstrap-compose` 后，可独立执行 `bash /opt/runtime-os-portable-20261121/install-config/install.sh monitor-repair`，重建网关并验证相对重定向、首页和静态资源。

在浏览器所在机器复测不带末尾斜杠的地址。第一条应返回 `302` 和 `Location: /monitor/`，第二条应输出 `200 http://192.168.195.93:18100/monitor/`。使用自定义端口时按 `manual-install.env` 的 `HTTP_PORT` 替换。

```bash
curl -fsS -D - -o /dev/null http://192.168.195.93:18100/monitor
curl -fsSL --max-redirs 1 -o /dev/null -w '%{http_code} %{url_effective}\n' \
  http://192.168.195.93:18100/monitor
```

若 `monitor-repair` 在 `sudo` 下找不到 Docker，请在 `install-config/manual-install.env` 配置 `DOCKER_BIN` 为 Docker CLI 的绝对路径。可先用平时运行 Docker 的会话执行 `command -v docker` 获取路径。

## 2. 安装产品授权

1. 登录 **MCP 管理端 → License 授权**，取得当前部署显示的服务器 MAC，并用它申请授权包。
2. 将同一授权包的 `license.dat` 和 `license-public.pem` 放入 **MCP 持久化数据目录的 `license/`**。手工环境的宿主机目录是 `/opt/ros21/data/mcp/license/`，容器内是 `/srv/app/data/license/`。
3. 首次放置或更换公钥后重启 MCP。回到授权页点击「刷新授权」，确认授权有效、绑定机器及模块正确。

以下命令可在新的 Bash 会话中直接执行。示例将授权包解压到 `/opt`，如果文件在其他目录，请修改 `AUTH`。更新已有授权前先备份两份旧文件。

```bash
(
  set -euo pipefail
  INSTALL_CONFIG=/opt/runtime-os-portable-20261121/install-config
  source "$INSTALL_CONFIG/manual-install.env"
  export DOCKER_CONFIG="$INSTALL_CONFIG/docker-config"
  AUTH=/opt
  test -s "$ROOT/compose.json"
  test -s "$AUTH/license.dat"
  test -s "$AUTH/license-public.pem"
  install -d -m 0750 -o 10001 -g 10001 "$ROOT/data/mcp/license"
  install -m 0600 -o 10001 -g 10001 "$AUTH/license.dat" "$ROOT/data/mcp/license/license.dat"
  install -m 0644 -o 10001 -g 10001 "$AUTH/license-public.pem" "$ROOT/data/mcp/license/license-public.pem"
  cd "$ROOT"
  docker compose -f compose.json restart mcp
)
```

Web 环境的对应目录是 `/mnt/disk01/ops/r/<实际安装目录>/data/mcp/license/`。
从部署预检获得安装目录，或以 MCP 容器的挂载为准：

```bash
MCP_CONTAINER=$(docker ps --filter label=com.docker.compose.project=ops-env-web-r21 \
  --filter label=com.docker.compose.service=mcp --format '{{.Names}}')
test -n "$MCP_CONTAINER"
docker inspect "$MCP_CONTAINER" \
  --format '{{range .Mounts}}{{if eq .Destination "/srv/app/data"}}{{.Source}}{{end}}{{end}}'
```

文件就位后执行 `docker restart "$MCP_CONTAINER"`。若使用自定义 `CHATCHAT_LICENSE_FILE` 或
`CHATCHAT_LICENSE_PUBLIC_KEY_PATH`，以该 MCP 实例的实际配置为准。

产品签发私钥留在 License Center。部署包的验签公钥 `runtime-portable-20261121.json`
安装到 Deployment Ops 的信任目录。产品验签公钥 `license-public.pem` 和 `license.dat` 一起安装到 MCP 数据目录。

| 授权状态 | 检查项 |
| --- | --- |
| `NOT_INSTALLED` | MCP 数据卷路径、文件名、容器 UID 10001 的读取权限 |
| `INVALID_SIGNATURE` | 两个文件是否来自同一授权包，更换公钥后 MCP 是否已重启 |
| `SERVER_MISMATCH` | 签发时的 MAC 是否与当前 MCP 授权页显示一致 |
| `EXPIRED` | 授权有效期及服务器日期 |

管理员登录可在授权安装前验证。受授权保护的业务模块需在授权状态有效后验证。

## 3. Web 全新重装

**先检查 Web 前置条件，再停止手工环境或删除镜像。**
目标机需挂载真实数据盘 `/mnt/disk01`，使用能够按镜像配置 SHA-256 核验本包的经典镜像存储，
并预留签名包、解包、镜像和业务数据的空间。Docker 29 的 containerd 镜像存储报告 OCI manifest digest，
当前 Web 路径需要兼容的镜像存储。存储模式迁移应安排在单独维护窗口。验收机的这两项前置条件尚未满足。

如果需要释放 18100 和 18101，在 `/opt/ros21` 对手工项目执行 `docker compose -f compose.json down`。
如需模拟镜像缺失，只删除该项目使用且无其他容器引用的镜像。新建 Web 环境使用独立数据目录。

```bash
(
  set -euo pipefail
  BASE=/opt/runtime-os-portable-20261121
  findmnt --mountpoint /mnt/disk01
  driver_status=$(docker info --format '{{json .DriverStatus}}')
  case "$driver_status" in
    *io.containerd.snapshotter.v1*) echo '当前发行包的 Web 路径要求经典镜像存储。' >&2; exit 1;;
  esac
  cd "$BASE"
  sha256sum -c deployment-ops.jar.sha256
  (cd media; sha256sum -c runtime-os-1.3.2-portable-20261121-r3.ops.zip.sha256)
  sysctl -w vm.max_map_count=262144
  export OPS_DATA_DIR=/mnt/disk01/deployment-ops-portable/data
  export OPS_PORT=19250 OPS_EXECUTOR_PORT=19252
  install -d -m 0700 /mnt/disk01/deployment-ops-portable/standalone/trust
  install -m 0600 "$BASE/media/runtime-portable-20261121.json" \
    /mnt/disk01/deployment-ops-portable/standalone/trust/
  /opt/jdk-17.0.5/bin/java -jar "$BASE/deployment-ops.jar"
)
```

从工作站建立 SSH 转发，打开 `http://127.0.0.1:19250`。Windows OpenSSH 遇到用户配置权限错误时可加 `-F NUL`。

```bash
ssh -N -o ExitOnForwardFailure=yes -L 19250:127.0.0.1:19250 root@192.168.195.93
```

首次用 JAR 终端中的安装令牌创建管理员。点击「部署应用」，选择本机和新环境，例如 `web-r21`，
上传 R3 签名 ZIP 及同名 `.sha256`，填写空闲的入口端口及业务管理员密码。
记录预检显示的安装路径，提交部署并等待任务成功。验证登录及 Coding 运行时后，按第 2 节安装产品授权。
