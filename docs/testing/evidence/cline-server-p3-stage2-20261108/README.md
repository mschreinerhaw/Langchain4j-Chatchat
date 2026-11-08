# 第二阶段关键证据

采集时间 `2026-11-08T05:32:55Z`，基线 `db60dec41`。模型 `qwen3.7-plus`；全部外部进程、Python Docker Sandbox、PostgreSQL 和 HTTP 调用均真实执行。直接协议测试使用明确标记的测试关联标识；原生调用证据另列，来自官方 v4.1.26 Agent 和真实模型，不将两者混称。

| 证据 | 内容 |
|---|---|
| [原生任务结果](runs/native/summary.json) | 18 项检查通过；整个 Worker 被杀、主动取消、仅官方 Core 被杀三条真实模型链路 |
| [真实 MCP 回执](runs/native/long_mcp-final-operations.json) | 原生 Tool Call、operationId、PID/启动时间、取消与停止证明 |
| [真实 Sandbox 回执](runs/native/python_execute-final-operations.json) | 实际 Container ID、镜像/代码/输入 Hash、exit 137、stopConfirmed |
| [Core 子进程被杀](runs/native/python_execute-core-kill-final-operations.json) | 外层 Worker 仍运行，独立触发远端停止与对账 |
| [协议测试](runs/protocol-tests.json) | 21 项检查通过；持久绑定、原生回执缓存、重复派发/取消、参数变造及 JSON-RPC ID 冒用拒绝 |
| [故障注入](runs/fault-tests.json) | 6 项检查通过；请求丢失、回执丢失、迟到回执、authority 被杀/重启、JDBC 恢复与旧 Epoch/Fence |
| [Journal 对账](journal/reconciliation-matrix.json) | 历次 45 个持久 Operation，未对账 start intent 为 0；直接协议测试的任务状态与操作状态分列 |
| [原始 checkpoint](journal/checkpoints.json.gz) | 现有 Java Journal 原始持久记录，经凭据脱敏，保留真实 MCP 响应 |
| [Python 数据库](journal/python-rows.json) | 实际 Managed Execution 状态和容器身份 |
| [Journal 扩展测试](runs/journal-guards.log) | 真实 JDBC 的 7 个边界检查，无外部执行模拟 |
| [旧 Python Fence](runs/managed-stale-fence.log) | 调用既有 Managed 实现，旧 Fence 拒绝且归档回执字节不变 |
| [延后检查](runs/late-observation.json) | 实际执行节点中的原进程全部不再存在；保留完成/停止时间和回执 Hash |
| [Docker 事件](runs/docker-execution-events.json) | 实际 daemon 事件，只有缓冲仍保留的操作有事件；空列表不能作为停止证明 |
| [健康检查](runs/health.json) | authority/remote Docker healthy，三个服务 HTTP 200 |
| [凭据边界](runs/remote-credential-boundaries.json) | MCP 凭据不能创建 Session，不能读取 Gateway/JDBC 配置或 Docker Socket |
| [官方宿主差异](source/upstream-host-diff-matrix.json) | 相对第一阶段仅两个官方宿主文件变化，附逐文件 Diff |
| [Core/Agents Hash](source/core-source-hashes.json) | 611 个文件逐一相同（包含测试及非 TS 文件），无 Agent Core 修改 |
| [构建日志](runs/native-build.log) | 官方类型检查、ProtoBus 生成和 Standalone 构建 |
| [Docker 构建](runs/docker-service-rebuild.log) | 最终受测 Worker 镜像摘要 |
| [资源清单](runtime-manifest.json) | Bundle、descriptor、原生依赖及运行时资源 Hash |
| [首次 CAS 失败](runs/initial-protocol-failure.json) | Python 实际停止，但并发对账 HTTP 返回 revision conflict，保留真实失败 |
| [首次原生启动失败](runs/initial-native-failure/summary.json.gz) | 提前 READY 导致 Python 审批找不到已登记任务；完整审批/事件另存同目录 |
| [首次租约时序失败](runs/initial-fault-test-timing.json) | 查询已取得新租约，测试误判过期；未绕过 Fence，保留原失败 |

`workers/` 保留每个 Worker 的脱敏 registry、Docker 状态、原生事件、项目文件前后 Hash 和 Diff；`python/` 保留实际代码、stdout/stderr、manifest 与 receipt。[path-map.json](python/path-map.json) 将原执行目录映射为 Windows 可检出的短路径，文件内容不变。`remote/operations/` 是执行节点真实去重记录，不是新增 Java Journal。

[SHA256SUMS.json](SHA256SUMS.json) 覆盖全部采集文件。报告和源码对照见 [验收报告](../../cline-server-p3-stage2-20261108.md)、[宿主矩阵](../../../audits/cline-server-p3-stage2-host-diff.md)、[协议与风险](../../../design/cline-server-p3-stage2-operations.md)。
