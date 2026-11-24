# Runtime OS 单容器启停与镜像升级

适用于已经安装的 Runtime OS Compose 实例。首次安装和授权见 [安装说明](standalone/portable-package/README.md)，冷备份及整机恢复见 [生产运维手册](standalone/portable-package/OPERATIONS.md)。

## Coding 监控无法打开时怎么恢复

监控地址为 `http://your-runtime-host:18100/monitor/`。`gateway` 接收 18100 端口上的请求，`api` 提供监控页面和任务数据。以下命令使用 Compose 服务名。按现场配置替换示例主机、SSH 端口、发布端口、安装路径和项目名。

### 1. 登录服务器并确认容器状态

在本地终端连接服务器。

```bash
ssh -p 22 your-user@your-runtime-host
```

在服务器的 Bash 中执行。新开 SSH 会话时重新定义 `compose`。

```bash
compose() { sudo docker --host unix:///var/run/docker.sock compose -p runtime-os-install --project-directory /opt/runtime-os-install -f /opt/runtime-os-install/compose.json "$@"; }
compose config --quiet
compose ps -a gateway api mcp
```

| 状态 | 操作 |
| --- | --- |
| `gateway` 显示 `Exited` 或 `Created`，API 和 MCP 已就绪 | 启动 `gateway`。 |
| `api` 显示 `Exited` 或 `Created` | 恢复 API，等它变为 `healthy`，再检查网关。 |
| 服务在 Compose 中存在，状态列表却没有对应容器 | 用当前清单和本机镜像重建该服务。 |
| 多项应用和基础服务同时停止 | 用整套恢复入口按依赖顺序启动。 |
| 容器反复重启，或服务正在运行但监控返回 `502` 或 `503` | 先看网关和 API 日志，检查 API 是否就绪。 |

### 2. 启动已有容器

网关容器停止，且 API 和 MCP 就绪时执行：

```bash
compose start gateway
compose ps -a gateway
```

API 容器停止时，先确认 Redis 和既有 PostgreSQL、Silo、OpenSearch 已恢复，再执行：

```bash
compose start api
compose ps -a api
compose logs --tail 80 api
```

如果 API 显示 `health: starting`，等它变为 `healthy`。若网关也停止了，此时再执行 `compose start gateway`。网关可能只显示 `Up`，随后用 HTTP 请求验证监控入口。

### 3. 容器缺失或整套服务停止

网关容器缺失时，等 API 与 MCP 就绪，再按当前清单重建网关。API 容器缺失时，先确认其基础依赖就绪，将下面的 `gateway` 换成 `api`。

```bash
compose up -d --no-deps --no-build --pull never gateway
compose ps -a gateway api
```

多项依赖同时停止时，使用实例的现有安装入口。它按依赖顺序启动已登记的服务，并沿用原数据和镜像。

```bash
sudo /opt/runtime-os-install/install-config/install.sh deploy
sudo /opt/runtime-os-install/install-config/install.sh status
```

### 4. 验证监控恢复

在服务器上检查 `/monitor` 是否返回 `302` 并指向 `/monitor/`，再检查 `/monitor/` 是否返回 `200`。

```bash
curl --noproxy '*' --max-time 10 -sS -D - -o /dev/null http://127.0.0.1:18100/monitor
curl --noproxy '*' --max-time 10 -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18100/monitor/
```

从客户端浏览器打开 `http://your-runtime-host:18100/monitor/`，登录后确认任务列表可加载。若返回 `502` 或 `503`，查看网关和 API 日志。若本机返回 `200` 而客户端打不开，检查客户端到运行主机发布端口的连通性。

```bash
compose ps -a gateway api mcp
compose logs --since 10m --tail 120 gateway api
sudo /opt/runtime-os-install/install-config/install.sh verify
```

## 确认操作对象

在服务器的 root Bash 会话中执行。以下两种入口按现场选择一种。命令里的 `gateway`、`api`、`mcp` 都是 **Compose 服务名**，可以用 `compose config --services` 查看。

**按离线包安装的实例**，先进入服务器上包含 `install-config/` 和 `offline-images/` 的交付目录。

```bash
CONFIG="$(pwd -P)/install-config"
source "$CONFIG/manual-install.env"
export DOCKER_CONFIG="$CONFIG/docker-config" DOCKER_HOST=unix:///var/run/docker.sock
unset DOCKER_CONTEXT COMPOSE_PROJECT_NAME
DOCKER_BIN=${DOCKER_BIN:-$(command -v docker)}
compose() { "$DOCKER_BIN" compose -p "$PROJECT" --project-directory "$ROOT" -f "$ROOT/compose.json" "$@"; }
```

**已按安装规范迁移的实例**，使用服务器上的实例配置。从实例配置读取实际安装目录和项目名。

```bash
source /opt/runtime-os-install/install-config/manual-install.env
export DOCKER_HOST=unix:///var/run/docker.sock
unset DOCKER_CONTEXT COMPOSE_PROJECT_NAME
DOCKER_BIN=${DOCKER_BIN:-$(command -v docker)}
compose() { "$DOCKER_BIN" compose -p "$PROJECT" --project-directory "$ROOT" -f "$ROOT/compose.json" "$@"; }
```

操作前确认安装目录、项目及服务。

```bash
printf 'ROOT=%s PROJECT=%s\n' "$ROOT" "$PROJECT"
CONFIGURED_PROJECT=$("$DOCKER_BIN" compose --project-directory "$ROOT" -f "$ROOT/compose.json" config | sed -n 's/^name: //p')
test "$CONFIGURED_PROJECT" = "$PROJECT" || exit 1
compose config --quiet
compose config --services
compose ps -a
```

## 启停单个服务

以下以 `gateway` 为例。操作网关前确认 API、MCP 已健康。维护 Redis、PostgreSQL、Temporal、OpenSearch 或 Native 执行器时，先暂停新流量和调度，确认相关任务完成，再按依赖顺序恢复。替换 API、执行器或其网络命名空间时，按发行说明一起恢复相关 Worker 和 Recovery。

先执行 `SERVICE=gateway`，再选一项操作。

| 操作 | 命令 |
| --- | --- |
| 停止已有容器 | `compose stop -t 30 "$SERVICE"` |
| 启动已停止的容器 | `compose start "$SERVICE"` |
| 重启已有容器 | `compose restart -t 30 "$SERVICE"` |
| 按当前清单创建缺失容器 | `compose up -d --no-deps --no-build --pull never "$SERVICE"` |
| 检查状态 | `compose ps -a "$SERVICE"` |
| 查看最近日志 | `compose logs --since 15m --tail 100 "$SERVICE"` |

`start` 和 `restart` 使用容器创建时的镜像。加载了新镜像或改了 Compose 配置后，使用 `up` 重新创建容器。日常启停使用 `stop` 和 `start`，避免 `down -v`、删除安装目录或清理现有生产卷。

## 升级单个服务的镜像

仅在发行说明明确支持该服务独立替换、且与当前配置和数据兼容时使用。以下命令以网关为例，API、MCP、Broker 和 Worker 需要按发行说明核对组合版本。

1. 在维护窗口前取得该镜像的归档、SHA-256 清单、新镜像 ID，以及与它配套的 Compose 和安装镜像清单。为新版使用独立标签。
2. 记录当前容器的镜像 ID，并为旧镜像增加回滚标签。将现有 `compose.json`、`installation.json` 和相关配置备份到安装目录之外的管理员专用目录。

```bash
SERVICE=gateway
CID=$(compose ps -a -q "$SERVICE")
OLD_ID=$("$DOCKER_BIN" inspect --format '{{.Image}}' "$CID")
ROLLBACK_TAG="runtime-os-rollback/$SERVICE:$(date +%Y%m%d-%H%M%S)"
"$DOCKER_BIN" image tag "$OLD_ID" "$ROLLBACK_TAG"
printf '旧镜像 %s，回滚标签 %s\n' "$OLD_ID" "$ROLLBACK_TAG"
```

3. 在发布介质目录校验归档后加载。将下面的归档名、镜像引用和镜像 ID 换成发行清单中的值。

```bash
sha256sum -c SHA256SUMS
"$DOCKER_BIN" image load -i gateway-patch.tar
NEW_REF='runtime-os-gateway:发布标签'
EXPECTED_ID='sha256:发行清单中的镜像ID'
test "$("$DOCKER_BIN" image inspect --format '{{.Id}}' "$NEW_REF")" = "$EXPECTED_ID"
```

4. 按发布流程应用配套 Compose 和安装镜像清单。保留原项目名、端口、数据挂载和凭据。迁移实例的运维入口会核对 `installation.json` 中的 `imageIdentities`，因此 Compose 和镜像清单须由同一发布流程同步更新。已有离线安装遇到镜像集合校验失败时，使用发行方提供的迁移流程。
5. 确认 Compose 中目标服务引用了新镜像，再重建该服务。容器起来后核对实际镜像 ID、健康状态和业务入口。

```bash
compose config --quiet
compose config --images "$SERVICE"
compose up -d --no-deps --no-build --pull never --force-recreate "$SERVICE"
compose ps -a "$SERVICE"
CID=$(compose ps -q "$SERVICE")
test "$("$DOCKER_BIN" inspect --format '{{.Image}}' "$CID")" = "$EXPECTED_ID"
compose logs --since 10m --tail 100 "$SERVICE"
```

在浏览器验证受影响的入口。网关升级时，应用登录应正常，`/monitor` 应重定向至 `/monitor/` 并保留发布端口。使用上述安装布局时，可执行 `sudo /opt/runtime-os-install/install-config/install.sh verify` 复验现有服务和数据挂载。

## 升级整套离线镜像

在从当前版本到目标版本已有支持的迁移流程时，先处理运行任务、暂停流量与调度，并备份安装目录及外部存储。确认新版交付目录里的 `manual-install.env` 指向实际已有的 `ROOT`、`PROJECT` 和端口。加载前保留旧版介质，避免被同名标签覆盖后失去回滚来源。

按该版本说明完成配置和数据库迁移后，从**新版交付目录**执行。

```bash
(cd offline-images && sha256sum -c SHA256SUMS)
sudo bash install-config/install.sh load
sudo bash install-config/install.sh deploy
sudo bash install-config/install.sh status
```

`load` 会核验归档和 `images.tsv` 中的镜像 ID。`deploy` 会校验安装记录并按依赖启动。缺少受支持的原地迁移方案时，使用独立目录、项目和端口并行验收，切换前核对数据与授权。对于采用上述安装布局的实例， `/opt/runtime-os-install/install-config/install.sh` 是既有部署的运维入口，整套换版使用该实例的发布与回滚流程。

## 回滚

单服务替换失败时，恢复备份的 Compose、安装镜像清单和相关配置，并确认旧镜像引用解析到记录的 `OLD_ID`。随后用同一项目重新创建受影响的服务，再核对健康、镜像 ID 和业务入口。

```bash
compose config --quiet
compose up -d --no-deps --no-build --pull never --force-recreate "$SERVICE"
compose ps -a "$SERVICE"
CID=$(compose ps -q "$SERVICE")
test "$("$DOCKER_BIN" inspect --format '{{.Image}}' "$CID")" = "$OLD_ID"
```

整套版本回滚使用上一版介质及对应的配置。若升级已执行数据库迁移或接收新写入，先按审核过的恢复方案处理数据，再切回应用镜像。保留旧镜像、备份和失败日志至回滚期结束。
