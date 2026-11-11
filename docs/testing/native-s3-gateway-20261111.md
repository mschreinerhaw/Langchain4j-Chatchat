# 通用 S3 Native Coding Node 实施与验收记录

日期：2026-11-11。**仍在实施；本记录不是最终业务验收通过声明。生产默认 Coding Agent 未切换。**

## 本轮新增的可运行链路

业务系统签发任务合同 → 现有 Governance HTTP/SSE → 受限 Executor → 单任务统一镜像
→ 官方 Standalone Core → 原生 `run_commands` → Python → 候选交付／Runtime 验算反馈
→ 封存并确认容器停止 → S3 分块对象／清单／原生事件 → Runtime 验收回执。

这条 HTTP 链已实际启动原生模型和 Python；不是另写一个 Agent Loop。
Java 客户端 `NativeS3NodeClient` 提供对应协议，但它与正式 Runtime HTTP／Coding SPI
之间的产品级供数、计量、任务终态登记仍未全部接通。既有 Native Silo profile 的
能力协商保护保留，未伪造 `/v1/input-capabilities` 使旧链误认为新节点已经可用。

## 源码差异与组件边界

| 范围 | 原路径 | 新通用路径／当前状态 |
|---|---|---|
| Core／Agent Loop | 固定 v4.1.26 Standalone，包含已有 Host 适配 | 不修改；使用同一固定 runtime manifest 做文件校验 |
| Python 执行 | Native/MCP → Java → Python 控制面 → Sandbox → 回执适配 | Native Shell 直接执行同容器 Python；通用路径不创建远端 Python operation |
| SDK Worker | 旧 `coding-agent-node` 路径可独立存在 | 不复制进统一镜像，不启动第二套循环；Linux 全量回归临时复制的旧 helper 仅用于旧协议测试 |
| 输入授权 | Java 授权快照／资产所有权检查 | 保留；通用服务只消费签名合同绑定的 S3 路径、版本、长度和 SHA |
| 动态工程 | 部分旧业务依赖固定脚本／JSONL | 新协议按 Agent 声明的文件路径和产物类型交付，无 `analysis.py` 约束 |
| 数据传输 | 跨环境逻辑路径和远端回读 | GET Range、8 MiB 分块 PUT、完整 SHA 校验、有限网络重试；不是 Agent 修复次数限制 |
| 验算反馈 | 容易把工具成功／退出码零当作任务成功 | 候选内容读取并校验 SHA；实际验算错误可送回同一 Session；终态只能由 Runtime 明确登记 |
| 取消 | 单独服务／进程状态可能重复 | 撤销合同、取消传输、真实 Executor stop；已写 staging 版本不能成为正式交付 |
| 监控 | 原始工具事件与费用／修复数缺失 | 保留原始 SSE；无真实修复计数时改为 `null`，不因存在 outcome 就显示零 |

新路径绕过而非全局删除：`CodingPythonExecutionBridge`、旧 Python Sandbox API、旧
SDK Worker、固定 Python 源文件适配、远端 Python 结果回读／重复修复门禁。
这些组件仍可供原有业务使用；不能将新路径的成功外推为所有旧入口均已迁移。

固定官方源码提交：`a459f1d04dea030833b558be645011ab739c463b`。
本轮使用与既有证据相同的 Standalone 文件清单，构建检查全部通过。源码矩阵与已有
Host 差异详见 [原单镜像记录](native-coding-node-single-image-20261110.md)。
本轮从本机重新下载官方原文件做额外 SHA 核对时网络连接被关闭；该失败保留在
`target/native-s3-upstream-audit.json`，不能把下载失败说成源码 SHA 不匹配或重新核对成功。

## 构建与部署

- Dockerfile：`cline-agent-server/docker/Dockerfile.python`；仍复用完整 Python 锁文件。
- 修复复用统一镜像做基础层时 PATH 不含 `/usr/sbin` 导致 `usermod` 找不到的问题。
- 构建验证 Python 3.11、全部固定包版本、`pip check`、pandas／pyarrow／duckdb／
  matplotlib／openpyxl／pytest／数据库驱动、Node Native 模块、SQLite 和中文字体。
- `.dockerignore` 排除 Python 缓存，构建源文件清单也排除 `.pyc`，避免本地测试缓存
  被混入运行镜像与源码证据。
- 安装脚本复用已注册且固定 ID 的独立 daemon；没有创建新 Docker daemon、重新配置
  默认 daemon、改变模型服务或切换生产默认节点。
- 最新隔离部署目录：`/srv/cs3-v5-20261111/`；HTTP 地址：服务器本机
  `http://127.0.0.1:18340`。外部访问需由现有平台受控转发或 SSH 隧道接入。
- 镜像标签：`runtime-os-coding-node:cline-4.1.26-python3.11`。
- 最新镜像配置 ID：`sha256:cb6e8b7fbfbc26088dfdfcbdfd2b2f3f53b7099480dd60228f58dff81b946fb3`。
  `RepoDigests` 为空；OCI 导出清单 Digest 为
  `sha256:0e89df2b625b24fca5b0effa911cb2ea2c25cdd5f8c7c78b1fc6c3ec90148111`。
  这是已导出 OCI artifact 的 Manifest Digest，没有发布为 registry RepoDigest。

安装、私有权限配置、构建日志、来源哈希和每个进程 PID／启动标识均保存在部署目录。
私钥、签名能力 URL、原始模型回执和客户数据不进入 Git。

## 已完成的验证与保留的失败

- Java 全模块编译成功；相关 Java 测试 **30 项通过**（原相关 25 项和最终 SDK 5 项）。
  SDK 包含 Java/Node ASCII、中文对象路径哈希对照、候选内容校验、真实协议错误反馈与 URL 遮蔽。
- 本机 Node 测试 90 通过、3 跳过；远端 Linux 在补齐原有历史证据和旧协议测试 helper 后，
  **93 项全部通过**。最初因测试上下文缺少这两类文件而失败的日志原样保留，没有跳过用例。
- 新 HTTP 集成测试使用协议夹具，验证实际 HTTP／Unix Socket／SSE、产物封存、
  外任务拒绝、上传幂等、Runtime 终态登记以及上传中的取消 fencing。它不是模型／Silo 验收。
- 第一个真实模型先导样本完成多文件、TypeError 修复、报告和 PNG，但 JSON 均值只保留
  四位小数，独立精度检查失败。模型请求 12 次，耗时约 129 秒。失败样本未替换。
- 第二个先导样本的数值和产物通过。最初观测器只检查 `tool.failed`，漏掉了
  `tool.completed` 中真实的 Python traceback 和 `EXIT_CODE=1`。原判定保留，并新增更正证据：
  事件 254 实际 TypeError → 294 原生编辑 → 320 Python 重跑。未修改原生事件或重写历史结果。
- 固定新镜像／配置预登记 5 个综合分析 Session 与 5 个真实 Python 子进程取消 Session。
  第一队列分析 **4/5**、真实取消 **5/5**。分析第 4 次实际错误是工作区根目录缺少
  `delivery_manifest.json`，Host 返回 422；当时测试客户端直接终止，未反馈给仍可继续的会话。
- 修复客户端对实际协议拒绝的反馈后，另行预登记同镜像、同模型、同数据的新队列，
  **5/5 通过**。旧队列不删除、不替换、不混算。新队列第 4 次先收到两次真实清单错误，
  Cline 在同一 Session 修正，最终由 Host 重新计算哈希和原生回执。

| 新队列样本 | 耗时（秒） | 实际 provider 请求 | provider Token | 本地独立验算 |
|---|---:|---:|---:|---|
| 1 | 110.349 | 12 | 91,203 | 通过 |
| 2 | 107.914 | 11 | 80,236 | 通过 |
| 3 | 127.875 | 18 | 135,602 | 通过 |
| 4 | 229.938 | 21 | 233,808 | 通过，含两次真实协议反馈 |
| 5 | 126.537 | 16 | 126,161 | 通过 |

共 78 次 provider 请求、667,010 Token；费用无可信回执，保持未知。
每个样本均有真实 Python TypeError、后续源码编辑、成功 Python 重跑、多文件源码、JSON、
Markdown 和 Matplotlib PNG。报告统计值、舍入和五张图已独立复核。
5 次取消都先观察到持续存在的真实 Python 进程，再确认 Executor 停止、无迟到本地写入、
旧合同不能重建 Worker，也不能提交迟到结果。

新队列的源码与辅助 CSV 又在五个全新、断网的同镜像容器复跑，不复制原有输出或 Core 状态，
使用对应的实际 Native 命令；五份 JSON、Markdown、PNG **全部逐字节一致**。
第一版复跑器误选了一个未声明交付的临时 `validate.py`，得到 4/5；该失败保留在 `replay/`。
修正为只选交付源码对应的原生命令后，在独立 `replay-declared-source-v2/` 目录重新验证，
不修改模型产物、不消耗新模型请求、不覆盖第一版结果。

15 个 v5 Session 的受控摘要、181 个 Python 已装包版本、完整 Node 测试日志、镜像与 OCI 证据，
以及新队列的 37 个原始合成数据产物保存在
[本地证据目录](evidence/native-s3-gateway-20261111/verification.json)。下载逐一核对了大小及 SHA-256。
原始模型请求／响应和完整 Native 事件留在授权测试主机，未将它们作为公共仓库文件。

队列结果保存在 `cohort/`、`cohort-protocol-feedback-v2/` 与各 `acceptance/` 目录。
这些综合合成数据测试不能冒充 A–H 各自五次独立业务验收，也不能冒充真实 Silo、
客户数据、正式应用 HTTP／SPI 或原生 CLI 对照。所有任务仍未登记为正式业务成功。

## 本轮共享环境中断

开始时默认 Docker 中可见 Silo、应用、PostgreSQL 等共享容器。约 09:40 后这些容器和
基础镜像不再出现在默认 daemon 中，Silo 9000 和应用 18100 均不可达。
本轮没有执行 Docker 删除／停止共享容器／prune 操作；第一次构建因原基础镜像缺失失败，
失败日志保留在 `/srv/cs3-20261111/`。第二次复用现存独立镜像构建时暴露 PATH 问题，
失败保留在 `/srv/cs3-v2-20261111/`；修复后 v3／v4／v5 构建成功。

用户已确认继续使用同一服务器并正在恢复共享服务。因此没有自行重建另一套 Silo，
没有重配置共享 Docker 网络，没有把模拟存储测试计为真实 S3 验收。

## 尚未完成的最终验收项

1. 在共享服务恢复后，用原应用鉴权、授权快照和正式 Coding SPI 完成 Java → Node →
   Silo → Java 的完整产品级任务状态、计量与证据闭环。
2. 固定新服务版本完成真实 Parquet／客户账户、空数据、Silo 故障与分块传输、原生 CLI
   对照及 A–H 各五次验收；此前客户队列和组件测试单独保留，不能混算。
3. 新通用服务的实际 Skills／MCP、Native Session 跨进程受控恢复和原监控页完整集成。
   当前恢复是 fail-closed，不重放未知副作用，不能称为已恢复原 Session。
4. 持续化部署监督、应用回滚演练、完整 Silo 归档校验和最终源码提交 SHA。

这些条件未满足前，不宣布最终交付、不切换生产默认节点、不删除失败证据或旧镜像。
接口、Java SDK、构建部署及回滚约束见 [S3 服务协议](../../cline-agent-server/README.s3.md)。

## 2026-11-11：Java 入口与轻量监控对接验证

此前“尚未完成”描述保留为历史记录。本次已在用户授权的测试服务器上，将原有
18100 入口接入 Native S3 适配层；18351 监控入口同时查询原 Java API，不再查询候选数据库。
任务链为 Java 授权及快照 → 官方 Cline 4.1.26 → 本地 Python → 原 Silo → Java 登记。
原文件分析接口 `/api/v1/data-science/user-assets/analyses` 与通用
`/api/v1/coding/tasks` 均已通过真实模型和真实 Silo 验证。

当前部署使用以下本地不可变镜像 ID，没有将它们冒充仓库 RepoDigest：

- API：`runtime-os-api:integration-native-s3-20261111-r5`，
  `sha256:6914ece103e4f2c4fa7d4658ac823a7f7420970ec371f882e686d9da7c3242eb`。
- Node：`runtime-os-coding-node:integration-20261111-r4`，
  `sha256:03d65518c97d14310829639a7bcddd31636d5a74d04e2196b0eae6fc0d1be758`。

18100 上 CSV 与 Parquet 两个独立任务并发完成，分别耗时 111.57 秒、108.09 秒。
两者均自主创建多个 Python 模块，真实触发 TypeError、修改源码并重跑，生成 Markdown、
JSON、PNG。通过 Java 下载的 14 个交付文件均校验大小和 SHA-256；测试程序独立验算
3 行、总和 47.5、均值 47.5/3。数据为明确标注的合成样本，没有使用客户数据。
这些业务数值只存在于验收客户端，不进入通用 Java 业务校验逻辑。

真实运行中的 Python 经 Java 取消后，确认执行容器停止，任务登记为 CANCELLED，
没有正式交付文件。重复提交已完成的相同 requestId 复用同一任务且没有新增模型请求。
CSV 的 9 次请求与原始供应商回执逐项一致：输入 62,805、输出 4,323 Token。
SSE 历史回放和 Last-Event-ID 续读通过。当前 Node 镜像的 94 项运行时测试全部通过；
Java 的 141 项既有相关回归，以及后续取消修正的 8 项专项测试均通过，二者有重叠，不相加。

浏览器验证通过登录、401、越权 runId 的 403、真实时间线、异常修复证据、动态源码和图表预览。
详情任务文字使用 16px 常规字重，执行时间线和交付产物每次打开任务默认折叠。
错误展示保留真实外层退出码；即使 Shell 用 echo 掩盖 Python 非零退出，实际 traceback、
源码快照变化和同命令重跑证据仍可查询。Runtime 对业务语义保持 INCONCLUSIVE，
不因退出码为零、模型声称修复或文件上传成功而判定业务正确。

源码上传后在服务器容器构建；Git 基线为 `e06eabaf4c8554469ab48fc8841ef1ccc200986e`，
包含工作树修改，尚未生成本专项最终提交 SHA。公开验证摘要见
[对接证据目录](evidence/native-java-connection-20261111/verification.json)，
原始模型回执、Silo 交付和所有失败记录留在 `/opt/runtime-os/cn2/`。
首轮 Worker 目录权限失败、首轮异步取消状态未收敛，以及首次切换环境变量拼写错误
均保留记录；首次入口切换触发了自动回滚，修正后再次切换成功。

部署脚本为 `scripts/connect-native-stage.py`。回滚脚本保存在
`/opt/runtime-os-docker-stage/rollback/native-s3-20261111T094811Z/rollback.sh`，
旧 API 镜像、配置和数据卷均保留。MCP、News、Silo、PostgreSQL 的容器 ID 和镜像未改变。
原网关使用镜像中不存在的 wget，健康检查已改用现有 curl，返回 healthy。

本次完成测试入口对接，不等同于 `1.0.0` 正式发布。A–H 五次固定环境验收、原生 CLI
完整对照、全部 Skills/MCP 场景、故障传输场景、最终提交推送、SBOM 与正式镜像交付
仍须按原发布门槛完成，不能将本次合成样本结果合并为这些未执行的验收。
