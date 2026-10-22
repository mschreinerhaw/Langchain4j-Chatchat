# Python 英文命名及 coding-agent 自动恢复

新分配脚本只使用 ASCII 字母、数字、连字符、下划线和扩展名。例如中文业务需求使用 `analysis_20261022T120000000Z_<task短码>_v1.py`；可提取英文业务词时使用英文短名。UTC 时间、Task 短码、版本递增及原任务目录隔离规则保持不变。

历史中文脚本、归档、定位栈及代码哈希仍按原路径读取，不批量改名或改写不可变证据。新 Cline 执行仍必须使用 Java 分配的脚本身份，不能用自行指定的中文名称绕过分配。

## 自动拉起机制

线上检查确认 coding-agent 已配置 Docker `restart: unless-stopped` 以及 15 秒间隔、连续三次失败的健康检查，但 Docker daemon 开机启动原来处于 disabled，且 Docker 不会因 unhealthy 状态自行重启。

本次复用 Docker 和 systemd：

- Docker 原 restart policy 负责异常进程退出后的拉起。
- 启用 docker.service 开机启动，不重启 Docker daemon，不影响其他业务容器。
- 独立 systemd timer 在上次检查结束后约 30 秒巡检，只有目标 Compose project/service 的运行中容器为 unhealthy 时才重启。
- 每个容器两次巡检重启之间至少间隔 300 秒；健康、启动中、暂停中、已停止和 Docker 正在重启的容器不处理。
- 使用 Requisite 而不是 Requires，不因巡检而拉起操作员主动停止的 Docker daemon；手动停机行为保持。
- 状态写入 root 私有目录并原子替换；日志仅记录目标 ID、状态、动作和异常类型，不输出 docker inspect 的环境变量。

安装（适用于现有 runtime-os-app-stage 部署，root 执行）：

```sh
bash deploy/docker/application/install-coding-agent-watchdog.sh
systemctl is-enabled docker.service runtime-os-coding-agent-watchdog.timer
systemctl is-active runtime-os-coding-agent-watchdog.timer
journalctl -u runtime-os-coding-agent-watchdog.service
```

源文件：`coding-agent-watchdog.py`、对应 service/timer 和安装脚本，均在 `deploy/docker/application/`。单元以只读系统目录、私有临时目录和 root 私有 StateDirectory 运行，仅允许检查及重启指定 Compose 服务。巡检服务不创建新 Agent，不改变 Java Runtime OS 的任务恢复、幂等和授权职责。

维护时可执行 `systemctl stop runtime-os-coding-agent-watchdog.timer` 暂停巡检；恢复使用 `systemctl start ...timer`。移除巡检可 disable --now timer；不要因此停止 Docker 或其他服务。手动 `docker stop` 本身不会被巡检立即拉起。

服务恢复不等于任意进行中的分析自动成功或允许盲目重放。未完成任务继续由原 Java/SDK checkpoint、停止证明及预算机制核对。宿主机开机启动以 unit enabled 状态校验，不通过重启整台服务器进行验收。
