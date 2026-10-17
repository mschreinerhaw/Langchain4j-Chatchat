# Coding Agent 实际代码差距矩阵

> 后续进展：本表保留前轮状态。动态补数、SDK 检查点恢复、AI Coding 分析和 D3/G3 的最新实现及实际验收见 [2026-10-16 升级实施报告](runtime-ai-coding-upgrade-20261016.md)。

日期：2026-10-16。进场差距与本轮实施后的状态合并记录；完整源码路径和方法见下方索引。状态只代表列明范围。

| 功能 | 文档要求 | 源码现状/依据 | 缺失内容 | 优先级 | 实施位置 | 验收方式 |
| --- | --- | --- | --- | --- | --- | --- |
| Coding SPI/A1 | 四契约与跨语言 hash | 四契约已有独立实现；本轮新增 v2 WORKSPACE_FILES，v1 金样本/Schema 不变，版本保持与跨语言 hash 已测试 | 新协议的目标部署兼容验收 | P0 | 公共契约与同一个 Adapter | Java/Node 金样本、v1/v2 Schema 及未知版本拒绝回归 |
| A2 | 持久状态、所有权、共享预算 | `DurableCodingTaskService`/`JdbcRuntimeEvidenceStore` 真实实现 | 不能替代 SDK 活跃上下文恢复或 provider 回执 | P0 | 复用现有服务 | 真实 H2 并发、进程恢复、取消与预算测试 |
| Cline Worker | SDK 编程与自动修复 | `coding-agent-node/src/runner.js#createCodingRun` 已在同一 Agent 中支持 Python v1 / SQL 文件 v2；修复隐式 provider 重试、任务截止时间、工具异常取消与旧结果引用；21 项 Node 测试通过 | 活跃 SDK checkpoint；真实 provider/容器重验 | P0 | 同一个 runner/server | SDK+确定性模型及 Java 跨进程测试，真实模型验收另列 |
| SQL Studio | SQL/ETL→校验→Diff→确认→保存 | 原 prepare/result/apply 与版本 CAS 保留；本轮 submitCoding → SPI/Adapter/同一 Worker → SQLGlot → 候选/证据 → 用户确认 → apply 已接线；授权表选择经原 MCP 重新取快照 | 真实 provider/浏览器/容器联调；逐行 Patch；EXPLAIN/独立数据库执行；完整动态计划展示 | P0 | 原 Studio service/controller/UI + CodingWorkspaceService + 共享 Adapter/Worker | 真实 SDK/SQLGlot 两文件报错修复、30 项 SQL HTTP 测试、61 项前端测试；真实模型端到端仍待验 |
| Python Studio | 编辑与执行反馈 | `PythonCodeAssistantService`、`PythonDataScienceService` 已有规划/模型代码建议及 MCP preview/publish | Studio 助手没有直接使用 Cline；不是 Runtime RUN_CODING 的同义入口 | P1 | 后续复用共享任务/产物适配 | Studio 输入权限、代码/文件版本、执行与取消 |
| Runtime 数据分析 | Dataset→Python→证据→Analysis | `CodingAnalysisService`、`CodingInputSnapshotService`、`CodingEvidenceCollector` 已接线 | v1 固定单输入；活跃会话恢复、结构化补数、完整金融对照未完成 | P0/P1 | 同一 AnalysisCodingPort/Adapter | 可选委托、预算、结果准入、真实模型+沙箱 |
| 授权工具网关 | 各入口独立授权 | 原签名/合同/lease/元数据策略保留；新 workspace 回调复用相同绑定，只能读已捕获上下文/暂存 SQL；无 CODE_EXECUTION、业务数据或网络权限 | 目标网关新路径部署及网络复验 | P0 | 既有回调与授权设施 | 跨 tenant/user/run、旧 fence、停止后调用、元数据撤权/变版拒绝 |
| Evidence 回流 | 六项真实引用和业务验证分离 | Collector 与 Evidence 事务已实现；新增 workspace 工具回执与候选索引，明确 sqlExecuted=false；中断后证据发布可恢复 | 真实容器部署验证；大结果流式读取 | P0 | 同一个 Evidence Store | 引用解析、哈希、原子发布、重试恢复；禁止旧成功提案替代新失败提案 |
| 活跃会话恢复 | checkpoint/ordinal 连续且不重放 | server.js 的 Map；runner.snapshot 未持久使用 | 活跃 Coding SDK 上下文跨进程恢复未实现 | P1 | 同一个 Worker，A2 保持权威 | 在模型/工具各边界 kill 并独立进程恢复 |
| 增量供给 | 缺口交 Java 决策并原路补数 | 仅设计文档；v1 immutable inputSnapshotRef | 版本化输入集、补给账本、continue/证据血缘 | P1 | 已授权兼容扩展，不能用 resume 冒充追加数据 | 多轮同类/不同时间输入、撤权、预算连续性 |
| D3/G3 | 五类金融故障与对照 | 历史局部成功不能覆盖 | 真实 provider/沙箱/业务独立验算的完整集合 | P1 | 测试与验收夹具 | 类型、交易日、重复、精度、收益及组合/正常对照 |

本轮具体修改和测试结果以 [验收记录](coding-agent-end-to-end-validation.md) 为准，未完成项不因文档建立而关闭。

| 分类 | 本轮状态 |
| --- | --- |
| 已实现并经测试 | 上述本地 Java 253、Node 21、前端 61 项对应的公共修复与 SQL 集成范围 |
| 已实现但尚未真实联调 | 新版本真实 provider/浏览器/目标容器拓扑 |
| 部分实现 | Python Studio 共享入口、全量内容提案、动态计划展示、计量/大结果策略 |
| 未实现 | 活跃 SDK 继续恢复、增量供给、完整 D3/G3 等矩阵中未关闭项 |
| 被环境阻塞 | 本机 Docker 引擎不可用所影响的 Sandbox/容器重启与真实全链路验收 |

## 真实源码路径与关键入口

| 完整路径 | 关键方法/依据 |
| --- | --- |
| `chatchat-common/src/main/java/com/chatchat/common/runtime/coding/CodingAgentSPI.java` | submit/get/cancel/resume/subscribe；同目录 TaskContract/Outcome/ExecutionEvent/CanonicalHash/Protocol |
| `chatchat-agents/src/main/java/com/chatchat/agents/runtime/coding/DurableCodingTaskService.java` | submit/claim/reserve/startIntent/reconcileStopped/commitOutcome/events |
| `chatchat-api/src/main/java/com/chatchat/api/runtime/evidence/JdbcRuntimeEvidenceStore.java` | mutateExecutionCheckpoint/commitCodingEvidence |
| `chatchat-api/src/main/java/com/chatchat/api/runtime/coding/cline/ClineCodingAdapter.java` | submit/cancel/resume/completeVerifiedOperation |
| `chatchat-api/src/main/java/com/chatchat/api/runtime/coding/cline/ClineModelCallbackController.java` | reserve/model/settle/readWorkspace/proposeWorkspace/finish；同目录 ClineCallbackAuthFilter |
| `chatchat-api/src/main/java/com/chatchat/api/runtime/coding/workspace/CodingWorkspaceService.java` | submit/read/propose/complete/recover/output |
| `chatchat-api/src/main/java/com/chatchat/api/runtime/coding/analysis/CodingAnalysisService.java` | execute/run；同目录 CodingInputSnapshotService.capture/requireAuthorized、CodingEvidenceCollector.collect/registeredResult |
| `chatchat-agents/src/main/java/com/chatchat/agents/orchestration/analysis/graph/ModelNativeAnalysisHarness.java` | withCoding/execute；同目录 DataWorkspaceOperations/HarnessToolAccess |
| `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/python/PythonManagedExecutionService.java` | start/get/cancel；同目录 PythonOperationWorkspace.prepare/archive/artifactPath、PythonDockerRuntime |
| `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioService.java` | prepare/submitCoding/result/apply/write；同目录 SqlStudioMetadataService.captureCoding/operate、SqlGlotToolchain.validate |
| `chatchat-api/src/main/java/com/chatchat/api/datascience/python/PythonCodeAssistantService.java` | 现有代码助手；同目录 PythonDataScienceService 的 MCP preview/publish 路径 |
| `coding-agent-node/src/runner.js` | createCodingRun：工具 profile、模型 hooks、受控编码循环；server.js：httpGateway/createCodingServer |
| `chatchat-api/web-app/src/js/views/SqlStudioView.js` | generate/refreshGeneration/apply/cancel；CodeChangeReview 与原文件树/编辑器 |
