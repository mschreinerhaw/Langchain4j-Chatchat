# Runtime OS 客户现场维护手册

适用于通过 `runtime-os-1.3.2` 离线介质安装的 Linux 单机 Docker Compose 环境，包括已经应用离线分析补丁的实例。运行环境需要 Python 3.7 及以上、Docker 和 Compose v2。

脚本读取现场现有的 `compose.json`，使用清单指定的项目和镜像。恢复时保留当前的 API、MCP 和 Native Worker 配置，沿用已有挂载和数据卷。

## 1. 上传与确认实例

把维护包复制到客户 Linux 服务器，校验后解压。用现场实际安装路径替换 `/opt/ros21`。

```bash
sha256sum -c runtime-os-1.3.2-maintenance-20261125.tar.gz.sha256
tar -xzf runtime-os-1.3.2-maintenance-20261125.tar.gz
cd runtime-os-1.3.2-maintenance-20261125
sudo python3 runtime-maint.py --root /opt/ros21 status
```

核对输出里的项目名、安装目录和服务列表。若系统没有 Compose v2，可以通过 `--config-dir /实际路径/runtime-os-1.3.2/install-config` 使用原离线介质附带的 Compose 插件。如果现场 Docker CLI 使用自定义路径，把 `--docker-bin /实际路径/docker` 放在子命令前。

## 2. 宿主机宕机或整套服务停止

宿主机恢复后，先检查 Docker。只有 Docker 由 systemd 管理且服务未运行时才启动它。

```bash
sudo systemctl status docker --no-pager
sudo systemctl start docker
```

Docker 可用后，用当前安装目录恢复。脚本会先等待基础服务，再启动 Temporal、MCP 和 API，最后恢复其余服务。

```bash
sudo python3 runtime-maint.py --root /opt/ros21 recover
sudo python3 runtime-maint.py --root /opt/ros21 verify
```

恢复已有容器时复用其镜像和配置。容器已缺失时用当前 Compose 清单从本地镜像创建。如果 API 容器已缺失，脚本还会重新接入共享其网络命名空间的现有服务。大型环境启动较慢时，可在子命令前提供 `--timeout 600`。

## 3. 单个服务故障

下面以 `mcp` 为例。先看日志，再根据容器是否仍存在选择操作。

```bash
sudo python3 runtime-maint.py --root /opt/ros21 services
sudo python3 runtime-maint.py --root /opt/ros21 logs mcp --since 15m --tail 200
sudo python3 runtime-maint.py --root /opt/ros21 restart mcp
sudo python3 runtime-maint.py --root /opt/ros21 status mcp
```

| 命令 | 适用情况 |
|---|---|
| `start mcp` | 服务已停止或容器缺失，且依赖服务已健康。 |
| `restart mcp` | 重启现有容器。若指定 `api`，脚本先停止当时在运行的网络关联服务，再按顺序恢复。 |
| `stop mcp` | 维护窗口内暂停指定服务。`stop api` 会同时停止共享其网络命名空间的在运行服务。 |
| `logs mcp --follow` | 持续看日志。按 Ctrl+C 退出日志查看。 |

重启 API、数据库、Temporal 或 `executor-daemon` 前，暂停入口流量和调度，确认在途任务已结束。单个分析任务异常时，先查任务 ID 对应的监控和执行器日志。在整套运行环境中，实际任务 Worker 由专用 Docker 调度。

## 4. 恢复验收

运行 `verify` 检查关键容器的健康状态，以及应用首页、Coding 监控和 MCP 管理入口。脚本从网关的实际端口映射读取地址。使用外部代理时，可在 `verify` 后指定 `--app-url https://应用地址` 和 `--mcp-url https://MCP地址`。

随后用客户自己的账号登录，提交一次小分析任务并打开报告。若验证此前的 XLSX 故障，请用脱敏样例检查报告是否读出了实际列名和行数，并生成了基于单元格的指标。

若恢复失败，保留日志和原配置。先按报错核对磁盘、Docker、本地镜像及依赖服务，再恢复对应组件。使用离线发布包的升级流程处理镜像换版；现场宕机恢复沿用已安装清单，避免执行 `down -v`、清理生产卷或重新运行旧介质的 `install.sh deploy`。
