# Runtime OS 1.3.2 离线手工安装

本包使用 `192.168.195.93` 验证过的 API、Native Coding 和安装器镜像。新环境按目标主机生成配置、证书和密码。

| 目录 | 内容 |
| --- | --- |
| `offline-images/` | Linux amd64 镜像归档、SHA-256 校验文件和逐镜像摘要 |
| `install-config/` | 安装命令、Compose 插件、可编辑的 `manual-install.env` 和本指南 |

将两个目录解压到同一目录。应用和私有执行器由 Compose 管理。部署机使用 Linux amd64、本机 Docker、至少 12 GiB Docker 可用内存。为 Docker 镜像、私有执行器和应用数据分别预留空间。安装过程不需要访问镜像仓库。

## 安装

1. 将交付包复制到目标机。若收到独立 `.sha256` 文件，先在同一目录用 `sha256sum -c` 校验。
2. 解压交付包，以 `root` 进入 `runtime-os-install`。确认 `install-config/manual-install.env` 中的 `ROOT`、`PROJECT`、`HTTP_PORT` 和 `MCP_PORT`。默认安装目录是 `/opt/ros21`，端口为 `18100` 和 `18101`。同一主机安装多个项目时，为每套环境选择不同的目录、项目名和端口。
3. 检查主机并执行安装。

```bash
bash install-config/install.sh check
bash install-config/install.sh install
```

`check` 会显示宿主机现有的 Python 版本。宿主机只有 Python 2.7、较旧 Python 3 或没有 Python 时，照常使用上述 Bash 命令。安装器明确调用镜像里的 `python3`，加载阶段也会检查镜像内安装器和验收脚本的语法。OpenSearch 需要 `vm.max_map_count` 至少为 `262144`，安装入口会在低于此值时提高当前运行值。主机重启后，按系统的持久化 sysctl 配置复核该值。

## 分步安装与验收

```bash
bash install-config/install.sh load
bash install-config/install.sh prepare
bash install-config/install.sh deploy
bash install-config/install.sh status
bash install-config/install.sh password
```

`load` 验证归档、加载镜像并检查镜像摘要。`prepare` 在空目录生成部署配置。`deploy` 先检查基础服务，再迁移数据库、初始化 OpenSearch 并启动应用。最终应有 24 个服务健康，且 `$ROOT/verification.json` 的 `status` 为 `PASSED`。如果健康检查失败，根据 Compose 输出查看相应容器日志，再用 `deploy` 续跑。已有配置、证书和数据留在 `ROOT`。

`password` 可以在新 SSH 会话中独立执行。使用初始用户名 `admin` 和输出的密码登录 `http://目标机IP:HTTP_PORT`，MCP 管理入口是 `http://目标机IP:MCP_PORT`。生产模型和数据源按实际业务环境配置。切换外部访问地址时使用目标机的实际 IP 或域名。

## Coding 监控

在应用端口打开 `http://目标机IP:HTTP_PORT/monitor/`。例如 `.93` 使用 `http://192.168.195.93:18100/monitor/`。页面使用 Runtime OS 登录获取任务数据。`deploy` 会验证首页、JavaScript 和 CSS，然后生成安装验收报告。

已有环境遇到监控首页 HTTP 500 时，确认 `manual-install.env` 指向当前部署，再使用新版配置包执行下列命令，修复路由并重建网关。

```bash
sudo bash install-config/install.sh monitor-repair
```

如果 `sudo` 环境找不到 `docker`，脚本会检查常见安装路径。自定义安装位置可先在平时运行 Docker 的会话执行 `command -v docker`，再把返回的绝对路径写入 `install-config/manual-install.env`，例如 `DOCKER_BIN=/opt/docker/bin/docker`。如果命令无输出，请先在目标主机安装 Docker Engine 和 CLI。随后重试 `monitor-repair`。

## 产品授权

先在 **MCP 管理端 → License 授权** 获取当前环境显示的 MAC，再用它申请产品授权。将同一授权包中的 `license.dat` 和 `license-public.pem` 放到 `$ROOT/data/mcp/license/`。以下命令在解压后的 `runtime-os-install` 目录内执行。

```bash
(
  set -euo pipefail
  CONFIG="$(pwd -P)/install-config"
  source "$CONFIG/manual-install.env"
  export DOCKER_CONFIG="$CONFIG/docker-config" DOCKER_HOST=unix:///var/run/docker.sock
  unset DOCKER_CONTEXT
  AUTH=/opt
  test -s "$ROOT/compose.json"
  test -s "$AUTH/license.dat"
  test -s "$AUTH/license-public.pem"
  install -d -m 0750 -o 10001 -g 10001 "$ROOT/data/mcp/license"
  install -m 0600 -o 10001 -g 10001 "$AUTH/license.dat" "$ROOT/data/mcp/license/license.dat"
  install -m 0644 -o 10001 -g 10001 "$AUTH/license-public.pem" "$ROOT/data/mcp/license/license-public.pem"
  docker compose -f "$ROOT/compose.json" restart mcp
)
```

如果授权文件解压在其他目录，请修改 `AUTH`。更新已有授权前先备份旧文件。重启后回到授权页刷新，确认授权有效和机器绑定正确。业务数据、模型密钥和产品授权文件由目标环境准备。
