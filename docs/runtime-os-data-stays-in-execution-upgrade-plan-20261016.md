# Runtime OS 数据分析主链路审查与分步升级

日期：2026-10-16。原则：**Data stays in the execution environment. Models reason over schemas, plans, code, diagnostics, and verified results.** 数据保留在受控执行环境中；模型围绕结构、计划、代码、诊断和已核验结果推理。

## 源码审查

| 环节 | 当前事实 | 结论 |
| --- | --- | --- |
| 主 Analysis Loop | `AnalysisCoverageCoordinator` 把可选 `AnalysisCodingPort` 注入 `ModelNativeAnalysisHarness`；原 `BoundedAnalysisEvidence` 对小数据可直接把所有行放入初始提示词，对大数据提供全量扫描 Profile 和有界读取。 | Coding 已接线，但启用后的小数据默认提示仍可装入全部原始行，需切到摘要优先。 |
| 初始 Coding 输入 | `CodingAnalysisService` 只选当前已交付的 Dataset；`CodingInputSnapshotService` 将完整内容固定为带哈希的 MCP 文件，目标提示仅带描述符。 | 数据不会直接拼进 Cline 目标提示；增加缺失值/空值/类型统计，让模型在读取前了解质量。 |
| 多数据集 | Coding 服务支持 `datasetReferences` 1–8 个，包装时保留来源和内容哈希；Harness 原先仅检查单 `datasetReference`。 | 这是实际接线缺口，已修复。仍须遵守合计行数/字节上限。 |
| 计算与补数 | `CodingTaskContract` → 同一 `CodingAgentSPI/ClineCodingAdapter/Node Worker`；Java 授权的 `data_request` 和 `sql_validate`；完整数据在受控 Python 容器内处理。 | 沿用同一 A2、预算、权限和回执，不增加平行任务体系。 |
| 结果综合 | 完成后由 `CodingEvidenceCollector` 登记结果 Dataset、验证记录及六类证据，主 Loop 再消费；验证失败不准入。 | 结构校验成功仍是 `businessValidation=NOT_EVALUATED`。监管/财务口径需独立验证器或确定性指标服务。 |

## 实施顺序与状态

1. **主提示摘要优先，已实施。** Coding 能力存在时，Harness 的初始 L3 使用全量扫描的结构 Profile 和至多一条样本；保留有界按需读取。未启用 Coding 时沿用原分析行为。模型提示明确优先委托多行计算给 `RUN_CODING`，Python 读取完整快照。审查测试断言第二条原始记录不进入首次模型提示。
2. **补齐输入描述，已实施。** `CodingInputSnapshotService` 的持久快照附带 `qualitySummary`：每个字段的观察类型、缺失行数、显式 null 数及全量结构扫描范围。描述符不含原始行、凭证或 SQL；旧快照缺少该字段时返回空摘要，保持读取兼容。
3. **打通多数据集任务，已实施。** Harness 校验当前已交付且不重复的 1–8 个引用后转交原 `CodingAnalysisService`；后者继续负责捕获、合计限制、合同、预算和证据。不存在第二个 Coding Worker。
4. **结果核验与综合，沿用已完成链路。** `CodingEvidenceCollector` 归档实际程序、输入版本、Sandbox 执行及独立验证；只有可用结果进入主 Loop。业务规则必须由受信的 Skill/验证器提供，不能由模型自己宣布通过。
5. **后续容量阶段，尚未实施。** 初始快照目前在 Java 使用内存中有界 JSONL 缓冲，再上传到 MCP；默认上限 16 MiB/10 万行，多输入仍先构造内存包装。超过限额应设计可追踪的分片/流式快照传输和结果分页，不能仅调大模型上下文或无限增大堆内存。旧 `READ_RECORDS` 和批量模型逐行处理能力仍可由主模型明确选择；若产品要求全面禁止模型遍历原始数据，需另设可审计的策略开关与迁移测试。
6. **真实业务验收，待业务环境。** 用真实授权模板、真实模型和业务口径验算收益/交易等 Skill；金融核心指标继续由确定性服务独立对账。当前 G3 是真实 SDK/SQLGlot/Python Docker 加模型及外部数据源测试替身，不能证明真实模型分析质量。

三个逻辑契约映射到现有结构：`CodingAnalysisRequest` 对应主 Loop 的 `RUN_CODING` 请求加 `CodingTaskContract`；`DataRequirementRequest` 对应受控 `data_request` 回调；`CodingAnalysisResult` 对应 `CodingTaskOutcome`、已登记 Dataset 和 Evidence Bundle。公共 v1 协议不新增必填字段。

## 本轮验证

- 定向 Reactor 测试：`HarnessCodingIntegrationTest` 11 项、`CodingInputSnapshotServiceTest` 1 项、`CodingAnalysisServiceTest` 11 项，均通过。前者包含首次提示不含第二条原始行和多数据集引用准入断言。
- 非 Coding 回归：`BoundedAnalysisEvidenceTest` 6 项、`DataWorkspaceOperationsTest` 9 项通过。额外运行的旧 `ModelNativeAnalysisHarnessTest` 30 项中有 25 失败、2 错误；该类使用旧 `ChatModel.chat(String)` 测试桩，与当前 `ChatRequest` 协议不匹配，问题已在先前审查文档记录。本轮没有把该组计为通过。
- 门禁：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-coding-gates.ps1 -Offline -SkipFrontend`。新报告 [`gates.json`](../target/coding-gates/20261016-124023/gates.json) 显示 G1/G2/G3 通过，Java 316 项、Node 22 项通过；本轮明确跳过前端。真实 SDK、SQLGlot、JDBC、Python Docker，模型与外部模板源为确定性测试替身。上一轮的前端 71 项通过记录不等于本轮重跑。
- `git -c core.safecrlf=false diff --check` 通过。没有生产部署或真实模型质量验收。
