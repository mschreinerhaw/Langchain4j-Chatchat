# Python 英文命名及 coding-agent 自动恢复

新分配脚本只使用 ASCII 字母、数字、连字符、下划线和扩展名。例如中文业务需求使用 `analysis_20261022T120000000Z_7c12a06e93bd_v1.py`；可提取英文业务词时使用英文短名。UTC 时间、Task 短码、版本递增及原任务目录隔离规则保持不变。

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

## 本次部署及真实验收

实现提交：`abc64338ba720d8feffe261502b7380dd1e40fed`；发布分支 `release/1.2-20261022`。部署服务器 `192.168.195.221:5221`。替换 API 镜像为 `runtime-os-api:1.2.0-20261022-ascii-recovery`，大小 743,679,166 字节，仍只有一个应用 JAR 层；Node、MCP 镜像不需要重建。新 JAR SHA-256 为 `889be8ef5f7e59bd75f86f243d21f25538d6cf2a052f43e71bd285180ce8424c`。

部署前检查到现有任务仍在执行，因此等待该任务自然结束后再替换 API，未中止原任务。部署前备份配置、数据库及应用持久目录；配置、挂载和无关容器保持。备份位置记录在服务器该版本的 `evidence/deployment.json`，私有备份不进入交付包。

| 验证 | 实际结果 |
|---|---|
| Java 契约/命名回归 | 4 项通过，包括中文、混合英文、日文、重音字符输入及历史 Unicode 兼容 |
| Java 任务持久化回归 | 36 项通过，无失败或跳过 |
| Python 巡检单元测试 | 6 项通过，覆盖范围、健康状态、人工停机/暂停、冷却和原子状态写入 |
| 真实进程异常退出 | 对核对身份后的目标容器宿主 PID 发送 SIGKILL；Docker 自动拉起，同容器新 PID，16.30 秒恢复健康 |
| 真实进程无响应 | 对同一服务目标 PID 发送 SIGSTOP；健康检查变为 unhealthy，定时巡检记录重启请求并完成重启，91.68 秒恢复健康；未手动执行恢复命令 |
| 开机及守护配置 | docker.service 与巡检 timer 均 enabled，timer active；Docker 服务已有 Restart=always |
| 其他服务隔离 | 故障演练前后其他容器 ID 和启动时间保持一致 |
| 真实模型及 Python 分析 | 父任务 SUCCEEDED、Cline 任务 COMPLETED；3 次实际代码执行，三个版本名称全为 ASCII，原输入快照复用 |
| 源码与服务端记录 | 三个版本的 API 下载、Silo 下载、PG 索引、Java 提交/反馈日志均核对 scriptPath/version/hash |
| 历史兼容 | 4 份此前中文版本源码仍可下载，内容哈希不变 |

本次真实生成示例：

```text
python-csv-decimal-curre_20261022T091017512Z_3bd99ecea97f_v1.py
python-csv-decimal-curre_20261022T091050957Z_3bd99ecea97f_v2.py
python-csv-decimal-curre_20261022T091122359Z_3bd99ecea97f_v3.py
```

任务 `fb6263e8-dec4-3f67-8de6-da710813ade9`，Trace ID `att-1-606aa44c-eae4-49f8-b0a3-9714d7ce388b`。六次真实模型调用、134,605 Tokens；首版 Python 虽退出成功，但存在 OUTPUT_CONTRACT 错误，后续版本在同一任务修复后取得可接纳结果，合成附件最终回答为 CNY 33.68。最终既有业务验证状态仍为 INCONCLUSIVE，表示没有额外业务验证器给出 PASSED，不将它冒充业务契约已被独立证明；产物接纳、命名、哈希及历史兼容验证分别记录。

完整结果见[机器可读验收记录](evidence/runtime-ascii-recovery-20261022.json)。故障演练均在没有活动任务的窗口进行；本次没有重启宿主机，也不宣称已验证任意执行中业务的无损恢复。命名归一化为本地字符串处理，不增加模型调用或重新加载分析文件。

## 部署补丁包

服务器文件：`/opt/chatchat-releases/chatchat-1.2.0-20261022-ascii-recovery-hotfix-r2.tar.gz`，6,316,259 字节；SHA-256：`dae59c2e2b09a376c13060d4d394b31b327e92fa886433631c28a116199b49c1`。

这是基于此前已验收 script-naming-r2 安装的增量包，包含约 7 MB 的 JAR 字节差分、部署工具、巡检安装文件、文档和验收证据；没有凭据、私有备份、业务数据或基础镜像，不用于空机器首次安装。原基线 JAR 必须匹配 `bfdf26c827c0e10c9d4cbb1c51abe7ac1e2136c3afd99d8d3738b5bcd494e12b`，重建后匹配本文新 JAR 哈希。

解包到 `/opt/chatchat-releases/` 下新的发布目录，按包内 INSTALL.md 校验清单、重建 JAR、构建镜像并部署。已实际解包此包，核对全部清单哈希，并由包内差分重建出与线上一致的完整 JAR。[包校验记录](evidence/runtime-ascii-recovery-package-20261022.json)独立保存；回滚 API 和暂停巡检的命令在 INSTALL.md 中。
