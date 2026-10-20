# Runtime OS 自主分析职责重构审查

基线：Git `1da22672a`，标签 `v1.1`。

## 当前真实调用链

DATA_ANALYSIS 当前与其他任务共用以下链路：

1. `AgentOrchestrationEngine.executeInterpretationPlanPipeline(...)` 创建 `InterpretationAnalysisSession`，由 `InterpretationAnalysisGraph` 驱动阶段。
2. `InterpretationAnalysisSession.prepare()` 创建 `InterpretationPlanRuntime`。Java 模型参与计划、工具结果复核和检索参数补充；这些动作属于受控任务规划、模板选择与参数绑定，继续保留。
3. `InterpretationAnalysisSession.initialData()` 调用 `InterpretationPlanRuntime.execute(...)` 执行授权模板工作流，产生不可变工具结果。该阶段继续由 Java 控制。
4. `InterpretationAnalysisSession.initialAnalysis()` 调用 `AgentOrchestrationEngine.analyzeInterpretationPlanEvidence(...)`，进入 `InterpretationPlanEvidenceAnalyzer.analyze(...)`。这里由 Java 模型阅读数据内容、生成假设、缺口和证据图，属于与 Coding 重叠的数据探索。
5. 同一方法随后调用 `AgentOrchestrationEngine.analyzeClaimAdmissionCoverage(...)` → `buildRecordCoverageBundle(...)` → `AnalysisCoverageCoordinator.analyze(...)`。
6. `AnalysisCoverageCoordinator.analyzeWithHarness(...)` 启动 `ModelNativeAnalysisHarness.execute(...)`。Harness 在 Java 内维持工作区、选择读取/计算操作、循环调用模型，再通过 `AnalysisCodingPort.executeObserved(...)` 请求 Coding 计算。这是第二层数据探索与分析循环。
7. 如果 Harness 没有形成终态，`InterpretationAnalysisSession.refinementAnalysis()`、`AnalysisLoopCoordinator`、`AnalysisRefinementCoordinator` 和 `InterpretationPlanRewriter` 可能继续补证、重写计划并再次分析。受控模板执行失败恢复仍有价值，但在已取得 DATA_ANALYSIS 数据后继续由 Java 判断内容缺口属于职责重叠。
8. `AgentOrchestrationEngine.synthesizeInterpretationPlanAnswer(...)` → `FinalSynthesisNode.synthesizeFinal(...)` 发布报告。已有 Coding/模型成品时这里可以机械发布；旧路径仍可能构造 Java 总结提示或进入 `AgentAnswerFinalizer.finishReviewedAnswer(...)`。

Node/Python 的现有受控链路为：

`CodingAnalysisService.executeObserved(...)` → `CodingInputSnapshotService.captureConfigured(...)` → `CodingTaskContract` → `CodingAgentSPI.submit/resume(...)` → Node `runner.js` → `python_execute` → Python Sandbox → `CodingEvidenceCollector.registeredResult(...)`。补数由 Node 的 `data_request` 调用进入 `CodingDataSupplyService.request(...)`，Java 在这里重新核对 Skill、模板、参数、字段、轮次及数据权限，并生成新的不可变输入版本；Node 没有 MCP 或数据库执行权。

## 最小改造边界

- `InterpretationAnalysisSession`：仅在 `workflowFamily/taskType=DATA_ANALYSIS` 且初始模板执行完成后，跳过 Java 内容证据分析、语义声明计算和证据扩充循环，直接进入主 Coding 分析。QA、补充发现和非数据任务保持原路径。
- `AnalysisCoverageCoordinator`：保留数据投影、外置存储、快照指纹、失败源记账和取消检查；新增 DATA_ANALYSIS 的 Coding-owned 路径。原 `ModelNativeAnalysisHarness` 继续服务 QA/兼容任务。
- `AnalysisCodingPort` / `CodingAnalysisService`：新增一个通用主分析入口。它一次交付所有授权数据集、用户目标、已有 schema/来源声明、Skills 上下文和固定的通用报告契约；分析方法、关联发现、计算程序和修复仍由 Coding 决定。
- `CodingAnalysisService`：继续限制在已交付数据和预授权 `data_request`；结果必须是已停止、已归档且结构校验通过的单一报告产物。Java 只校验报告字段、证据引用是否属于授权数据集及不可变回执是否存在。
- `FinalSynthesisNode` / `AnalysisFinalizationPolicy`：Coding 成品通过结构和来源校验后，由现有直接发布分支机械交付，禁止 Java 再调用模型探索、重写或评审内容。

## 不修改的公共能力

- `InterpretationPlanRuntime` 的授权、模板工作流、参数绑定、取消、恢复、审计和失败处理。
- `CodingDataSupplyService` 的受控补数协议。
- QA、文档理解、补充发现任务的原分析与回答能力。
- Snapshot Manifest、共享工作目录、Python Sandbox 网络隔离、结果登记和完整 Coding 交互日志。

## Trace 验收字段

沿用统一 Trace 的 Java/Coding 模型回执、模板批次、Snapshot 和 Python 执行记录，并新增执行模式 `CODING_OWNED_DATA_ANALYSIS`、跳过的 Java 分析阶段以及主 Coding 结果引用。真实验收比较 Java 模型调用次数、Coding 模型调用次数、模板执行次数、快照读取次数、Python 执行/修复次数、Token、端到端耗时、终态分布和逐项原始数据核对结果。

## v1.1 改造前实测证据

真实模型、真实模板和同一分析请求的第一个成功样本：端到端 639,088 ms；Java 模型 12 次、469,039 ms；Coding 模型 2 次、92,567 ms；模板批次 1 次、7,462 ms；Python 执行 1 次、4,432 ms。Java 模型链占端到端耗时约 73.4%，其中数据到达后的 `ModelNativeAnalysisHarness.executeInternal(...)` 又调用 3 次 Java 模型，累计约 202 秒。该结果证明主要瓶颈是 Java 的重复分析循环，模板查询和 Python 执行不是主要瓶颈。

本次改造的 DATA_ANALYSIS 路径在数据到达后记录 `javaContentAnalysisModelCallCount=0`、`primaryCodingDispatchCount=1`、`primaryCodingDurationMs` 和完整 Coding usage；原 QA 路径仍走 `ModelNativeAnalysisHarness`。
