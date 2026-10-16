# Coding Agent 续建源码审查

日期：2026-10-16。基线 commit：`4b641ca6`。启动时已有未提交的 `harness-cline-integration-acceptance-20261016.md` 修改，本轮保留，不将它计作本轮实施或重新验收的事实。

## 审查方法与历史差异

先检查 2026-10-14 至 2026-10-16 的 Git docs 历史及文件修改时间，再沿公共合同、A2、MCP 执行、证据、Adapter、Node、Harness、Studio 调用链读取源码。没有发现适用的 AGENTS.md。A1 不是其他阶段的完成证明。

| 阶段 | 源码依据 | 复核结论 |
| --- | --- | --- |
| A1 | `chatchat-common/src/main/java/com/chatchat/common/runtime/coding/CodingAgentSPI.java` 及同目录 TaskContract/Outcome/ExecutionEvent/CanonicalHash | 已有四契约、状态/身份校验、Schema、跨语言哈希测试。 |
| A2 | `chatchat-agents/src/main/java/com/chatchat/agents/runtime/coding/DurableCodingTaskService.java` 的 submit/claim/reserve/startIntent/reconcileStopped/commitOutcome；`chatchat-api/src/main/java/com/chatchat/api/runtime/JdbcRuntimeEvidenceStore.java` 的 mutateExecutionCheckpoint | 独立真实实现。复用 Evidence owner 事务、CAS、lease/fencing、共享预算、持久事件。未知 provider 结果保留预留，不自动重放。 |
| B1–B4 | `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/python/PythonManagedExecutionService.java` 及同目录 PythonOperationWorkspace/PythonDockerRuntime | 已有操作去重、fencing、任务工作区、容器取消/查询、不可变归档；本轮 Docker 不可用，不能重签真实容器门槛。 |
| C1–C3 | `CodingEvidenceCollector.collect/registeredResult`、`JdbcRuntimeEvidenceStore.commitCodingEvidence` | 已有六项证据、哈希/行数/Schema 核验及原子发布；当前结果仍按合同大小上限整批装载，不是大数据流式方案。 |
| D1 | `ClineCodingAdapter`、`Cline*CallbackController`、`coding-agent-node/src/{server,runner,runtime-model}.js` | SDK `@cline/agents@0.0.93` 已真实安装并调用 Agent；Java 签名回调、模型预留/回执、Python 和证据已接线。单测模型替身不能当真实 provider 验收。 |
| D2 | `AnalysisCoverageCoordinator` → `ModelNativeAnalysisHarness.withCoding` → `CodingAnalysisService.execute` | RUN_CODING 已接入；主模型可不调用。只接受当前已交付 Dataset，结果经六引用和独立校验范围准入。等待有界，不会在等待期间重复消耗主模型轮次，但仍占用线程。 |
| D3/G3 | 故障夹具与既有验收文档 | 尚无本轮完整五类金融故障、组合注入、未注入对照及真实模型容器重启验收。保持未通过。 |

早期 D 阶段文档中的“回调未实现、D2 未启动、生产关闭”是历史阶段结论，不能覆盖后续源码。反过来，后续 v1 成功及已完成任务重启幂等也不能证明活跃 SDK 上下文恢复、动态补数或完整 G3 已实现。本轮未登录生产或核实文档所述现网状态。

## 架构和续建顺序

SQL Studio 与 Analysis Loop 复用同一个 CodingAgentSPI、ClineCodingAdapter、Node SDK Worker。入口、授权、任务上下文、产物及准入规则分别绑定；不增设平台任务状态机或证据库。Metadata MCP 只返回授权结构，SQL 执行保持单独授权；Runtime 取数仍由原资产发现、模板选择、参数绑定、模板执行完成。

用户已在本轮明确允许保留 v1 兼容的契约扩展；这取代旧前置报告中“全部 Python 待办验收后才开始任何 SQL 集成”的实施顺序，不意味着历史验收缺口自动关闭。先修复公共执行问题，再扩展同一 Worker 的受控工程产物入口；真实模型/Docker 验收和未关闭事项分别列明。

详见 [差距矩阵](coding-agent-unfinished-work-matrix.md)、[SQL 集成](sqlstudio-cline-integration-report.md)、[Runtime 集成](runtime-analysis-cline-integration-report.md)、[验收记录](coding-agent-end-to-end-validation.md)。

## 本轮实施后的分类

| 状态 | 结论 |
| --- | --- |
| 已实现并经测试 | 共享 Worker 的 SQL 文件 profile、受控元数据快照与 SQLGlot、既有工程审阅/版本应用；公共执行控制与证据恢复修复。最终 Java 253、Node 21、前端 61 项通过，前端构建成功。 |
| 已实现但尚未真实联调 | 新修正版的真实 provider、普通浏览器入口、Node Docker 与服务器部署组合。 |
| 部分实现 | SQL 完整内容提案而非逐行 Patch；计量、大数据流式读取、动态计划展示和 Python Studio 共享入口仍有边界。 |
| 未实现 | 活跃 SDK checkpoint、增量补数/输入版本集、完整 D3/G3、旧 Harness 全量历史测试迁移。 |
| 被环境阻塞 | Docker named pipe 不存在，未进行本轮真实 Sandbox、容器取消/重启和完整链路 B 重验。 |

完整路径/关键方法索引位于差距矩阵。本轮没有用 Mock、SQL 解析或历史部署文档替代真实 provider / Docker / 数据库执行证据；生产配置和用户原有未提交文档保持原状。
