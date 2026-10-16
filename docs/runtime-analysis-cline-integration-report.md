# Runtime Analysis / 共享 Cline 复核与修复

日期：2026-10-16。本轮以源码验证阶段状态，未从 A1 的完成记录推断后续阶段完成，也未重新登录生产。

## 原有链路和边界

原 MCP 资产发现、授权模板选择、参数绑定、模板执行、DatasetHandle、DataWorkspace 流程未改。`AnalysisCoverageCoordinator.analyzeWithHarness` 将可选 AnalysisCodingPort 接给 `ModelNativeAnalysisHarness`；模型自主决定直接分析已有数据或提交 RUN_CODING。`CodingAnalysisService.execute` 只接受当前已交付的 datasetReference；`CodingInputSnapshotService.capture/requireAuthorized` 固定内容、身份和合同；`ClineCodingAdapter` 经同一个 Node Worker 生成/修复 Python，`CodingPythonExecutionBridge` 调用 MCP managed Sandbox，`CodingEvidenceCollector` 核验并注册结果，主 Harness 继续消费已准入 Dataset。

`CALL_TOOL` 仍拒绝在分析阶段重新发现资产或派发外部工具。SQL 新入口使用另一授权/输出 profile，不能把 Metadata 权限升级为 Python 数据权限，也不能把 SQL 候选索引当业务分析数据。Java 主分析控制权、累计预算、取消/恢复、证据和发布决定不交给 Node。

## 本轮代码修复

| 修复 | 源码/验证 |
| --- | --- |
| SDK 隐式 provider 重试可能绕过 beforeModel 的预留，并使回调 ordinal 与账本脱节 | `coding-agent-node/src/runner.js` 包装 AgentModel 的错误流为不可在 SDK 内自动重试的 finish；错误不伪造用量结算，原 Runtime 错误保留。真实 SDK + 可重试 finish/503 异常测试确认一次预留、一次调用。 |
| SDK 默认 30 秒工具超时可能抢先结束长 Python 执行 | runner 的工具等待和整轮执行绑定合同 deadline；截止时间中止模型/工具信号，已有 Python 操作发取消请求。取消请求不冒充进程停止证明。 |
| HTTP 失败后可能遗留活动 Sandbox 操作 | Python 工具异常路径在清除 activeOperation 前请求 Runtime 取消，未知结果保留待协调；不重放。 |
| 后一次执行失败或未写结果仍可能选择前一次结果引用 | 新 Python 尝试开始即清除 lastCollectedOperation；FAILED、缺少输出和传输错误均不能用旧代码证据宣称最后一次成功。 |
| Node 报 COMPLETED 但完成回调未收敛时，原恢复分支不核验归档 | `ClineCodingAdapter.resume/completeVerifiedOperation` 把已停止的 COMPLETED 会话纳入证据恢复；仍要求最新 Python 的 A2 停止证明、真实 MCP 归档与 Collector 核验。未知模型和失败 Python 都不提交完成，也不再派发。 |
| 新 SQL profile 的取消误入 Python 通道风险 | Adapter 仅对 `cline-<hash>` 操作传播 Python 取消；workspace/model 操作保留自己的控制与核对路径。领取后重新读取 stop/deadline，停止请求禁止新 Node 派发。 |

`CodingAnalysisService` 保留有界等待，等待期间不反复调用主模型；仍占用当前 Java 工作线程，不宣称已有异步挂起/唤醒调度。COMPLETED、结果准入和业务验证分开；FAILED / 待协调 / 业务验证失败不登记派生 Dataset。未知费用保留预留。

## 状态

| 状态 | 当前事实 |
| --- | --- |
| 已实现并经测试 | 固定单输入 v1 的主分析可选委托、作用域/合同绑定、预算/持久化、证据准入；本轮 Node 执行控制和 Java 证据恢复回归。 |
| 已实现但尚未真实联调 | 本轮修正版的真实 provider + Node Docker + MCP Python + Evidence + Analysis Loop 完整回归。历史验收文档不是本轮重新执行的证据。 |
| 部分实现 | CPU/费用等计量仍不完整；结果读取按合同上限装入内存；等待有界但非调度器异步挂起；Python Studio 的代码助手与 Runtime 委托不是同一入口。 |
| 未实现 | 活跃 SDK 会话持久 checkpoint / 连续 ordinal 恢复；动态输入供给、结构化 DataRequirement 协调账本和同任务 continue；完整五类金融故障及组合/未注入对照的 D3/G3；旧 Harness 全量历史测试迁移。 |
| 被环境阻塞 | Docker Linux Engine 当前不可连接，不能重跑真实 Sandbox、容器取消/崩溃重启和 B/C/D 全链路验收。 |

历史 provider 超时缺少可信回执时，不改写成终态、不释放未知费用，不以“测试通过”关闭该事实缺口。v2 WORKSPACE_FILES 只是兼容增加的文件任务 profile，不实现已有数据补给设计的输入版本集。下一轮应按已有设计在同一 Runtime/Worker 上补 checkpoint 与受控数据需求交接，再跑完整 D3/G3。

完整路径依据：

- `chatchat-agents/src/main/java/com/chatchat/agents/orchestration/analysis/governance/AnalysisCoverageCoordinator.java`。
- `chatchat-agents/src/main/java/com/chatchat/agents/orchestration/analysis/graph/ModelNativeAnalysisHarness.java`、`DataWorkspaceOperations.java`、`HarnessToolAccess.java`。
- `chatchat-agents/src/main/java/com/chatchat/agents/runtime/coding/DurableCodingTaskService.java`。
- `chatchat-api/src/main/java/com/chatchat/api/runtime/CodingAnalysisService.java`、`CodingInputSnapshotService.java`、`CodingPythonExecutionBridge.java`、`CodingEvidenceCollector.java`、`ClineCodingAdapter.java`。
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/python/PythonManagedExecutionService.java`、`PythonOperationWorkspace.java`、`PythonDockerRuntime.java`。

测试范围、环境和日志见 [验收记录](coding-agent-end-to-end-validation.md)。
