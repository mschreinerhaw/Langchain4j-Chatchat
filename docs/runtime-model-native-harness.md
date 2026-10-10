# Model Native Analysis Harness

原则：Model decides. Runtime executes. User judges.

职责公约与证据审计边界见 [Model Sovereignty, Runtime Governance](runtime-model-sovereignty.md)。

## 本轮联机问题定位

2026-10-09 捕获了两次实际执行：

- `c22c105b-be5a-4c1c-a155-b658a8b4be03`：三只股票的投资机会比较，3 个数据集进入 Worker 路径，134 条事件，最终为 PARTIAL_SUCCESS。
- `665df6cf-2875-4556-a09e-531561958ad5`：同三只股票的行情分析，1 个大数据集也因数据体积进入 Worker 路径，94 条事件，最终为 PARTIAL_SUCCESS。

这两个案例的数据源均有成功返回。旧路径将数据规模与分析职责绑定，经过 Worker 结构化 Claim、语义分类及二次汇总。代码还存在强制逐数据集 findings 覆盖和方法检查后的模型修复。最终正文可被保留，并不代表它生成前的上下文与决策已摆脱这些机制。

修改目标是消除这层架构干预、恢复完整证据访问及模型自主表达。报告结论的正确性仍需用户结合实际来源判断；不以 Runtime 评分宣称报告质量已经得到证明。

## 本轮实现范围

`AnalysisCoverageCoordinator` 在数据集绑定后直接执行 `ModelNativeAnalysisHarness`。Driver–Worker 分析架构及配置回退已删除，包括分派器、独立数据集 Agent、强制归并、专属提示词和 Temporal 分析 Workflow。通用工具并发、DAG 调度、任务状态与重试仍保留。大数据执行协议见 [Single Brain 数据工作区](runtime-single-brain-data-workspace.md)。

模型上下文分为当前问题及模型工作状态、授权 Skills/Agent 角色/领域知识及可用能力、证据导航视图与执行回执。较大的证据仍由既有 DatasetHandle/SpillStore 承载；投影省略不等于源证据不存在。

模型可直接输出最终 Markdown，或使用 `model_native_analysis.v1`：

```json
{
  "schemaVersion": "model_native_analysis.v1",
  "completed": false,
  "reportMarkdown": "已形成的分析正文",
  "workspace": { "notes": "模型自己的工作笔记", "artifacts": [] },
  "evidenceRequests": [
    { "operation": "READ_TEXT", "datasetReference": "已绑定的数据集 ID", "record": 1,
      "field": "已有文本字段", "fromChar": 0, "maxChars": 3000 }
  ]
}
```

可执行能力复用已有 READ_RECORDS、READ_NESTED_RECORDS、READ_CONTEXT、CALCULATE、EXECUTE_OPERATION，并增加 READ_TEXT 原文游标。READ_TEXT 返回精确片段、totalChars、hasMore、nextChar，模型可以继续读取同一原始字段，无需另一个模型提取器。计算只接受来源声明的合同或操作；Runtime 不自行选择业务公式。

workspace 由模型维护；Runtime 保存版本、上下文指纹、输出检查点及读取结果，限制资源和隔离范围。相同租户/运行及完全相同输入指纹的输出检查点可复用；不同输入不能命中。记录数准备完成只表示执行事实，不表示每条记录都被模型语义审阅。

最终发布复用本次模型正文，不再调用第二次总结模型、不追加分析质量门禁、不在正文追加 Runtime 覆盖统计。来源失败和 sourceComplete=false 仍保留在执行元数据中。

第一次部署后的联机重跑还定位到 AnswerEvidenceAuditService 的遗留 Claim 评分：新路径已生成正文，最终装配层仍追加质量提示、影响公共执行状态。新路径现在在该边界明确跳过 Claim 评分，审计信封保留工具执行与数据集引用，不把人工价值判断作为运行失败或强制复核状态。旧路径保持原有兼容行为。

图形能力继续从注册表动态注入。只有模型实际提供 ReportBlock 建议时，才运行独立的确定性绑定节点；字段、数据引用和权限校验失败只移除相应图形。无建议时不强制图表，也不新增图形规划模型调用。

## 配置与观察

```yaml
chatchat:
  agent-runtime:
    harness-max-model-turns: 8
```

轮次限额为资源边界，可配置 1–64；不再受旧路径最多三次决策限制。每轮最多四次有界证据请求，READ_TEXT 每次最多 3000 字符。超出预算时保留模型已有报告；无正文则报告执行未产生结果。协议或读取失败反馈给模型，不把读取失败解释为业务证据不存在。

关注元数据：modelNativeHarnessActive、harnessWorkspace、harnessTrace、harnessModelCalls、harnessStopReason、harnessSemanticCoverage、analysisReportGenerationMode。前端通过 HARNESS_TURN 逐轮呈现模型分析与证据访问。

这是第一阶段的证据导航和上下文承载改造。外部工具发现、搜索与 MCP 授权仍沿用现有 Agent Loop；尚未将整个执行系统迁移成统一的自由工具循环，也没有宣称已完成全部 Artifact SDK、用户反馈持久化或跨运行记忆。

## 验证

回归覆盖：原文窗口连续读取、工作状态传递、越界数据引用拒绝、模型直接结束、超过旧三轮限制、预算耗尽保留正文、多数据集不触发旧 Worker/业务推断、数据源失败保持可见、总结发布不再调用模型、无图形建议不强制规划、前端每轮事件不合并。

本轮相关后端回归 131 项通过，前端回归 104 项通过，构建通过。

2026-10-09 最终联机任务 `30cc170e-f05a-4145-a14d-37794a4e9f29` 使用原三只股票比较问题及 qwen3.7-plus，状态 SUCCESS，42 条事件。Skills 为 APPLIED，数据工作区绑定 6 条实际返回记录；报告分析模型调用 1 次、Worker 0 个、最终总结模型调用 0 次。模型自主选择直接结束，本次没有额外原文读取请求，也没有图形建议，因此未运行图形规划节点。

模型草稿与发布契约正文完全相同（2175 字符）；最终装配只进行了现有 Markdown 行尾空白规范化，规范化后的正文 SHA-256 一致。最终结果没有 Claim 覆盖分值、逐条 Claim 审核或 Runtime 质量提示。SUCCESS 表示执行完成，不表示平台认证了模型的业务结论。

生产前端使用该实际任务结果进行浏览器内回放，未写入或替换服务器会话：查询明细默认折叠，摘要文字 12px 灰色；未生成启发式图表；页面无 JavaScript 异常。截图、任务、事件、运行元数据、完整报告及验证指标保存在 `target/codex-live/harness-20261009/`。其中 `final-*.json` 是最终实测，`after-*.json` 是第一次部署后暴露发布层遗留评分的实测，`comparison-*`/`market-*` 是修复前样本。

上述为第一阶段部署记录。Driver–Worker 删除后的部署记录见 [Single Brain 数据工作区](runtime-single-brain-data-workspace.md)。当前版本没有旧分析路径开关；部署备份用于整包恢复，不是保留旧架构的执行入口。

扩展检查发现两个旧路径问题：InterpretationPlanRuntimeArchitectureTest 的主文件行数上限为 8730，而当前 HEAD 已超过该值；AgentAnswerFinalizerEvidenceAnswerTest 的 reviewerTimeoutUsesConfiguredModelTimeout 实际等待约 5 秒，未满足小于 3 秒的断言。本轮未修改对应执行器主文件或旧 reviewer 超时逻辑，也未放宽门槛。新路径不调用该 reviewer。扩展失败日志保留在 target/codex-live 与 target/harness-tests-extended-20261009.log；最终部署记录见联机记录。

## 2026-10-10：v2 意图、工具恢复与链路探索

`model_native_analysis.v2` 每轮必须声明 `decision` 对象；`CONTINUE` 可以没有取证请求。内容语义独立于执行意图：模型通过 `output_type: intermediate|draft|final` 声明，正文可使用 `content` 或兼容字段 `reportMarkdown`。`final` 自动发起向当前用户的会话交付，无需额外 PUBLISH；`COMPLETE`、`PARTIAL_COMPLETE` 本身不定义内容类型。缺少声明时记录 `UNDECLARED`，不推断为草稿。旧 `PUBLISH` 保留为模型显式最终回答的兼容入口；显式 `output_type` 优先。最终回答绑定正文版本与证据快照用于恢复一致性，不作语义审批。外部提交必须独立通过用户授权工具。`WAIT` 当前返回 `WAIT_UNSUPPORTED`，尚无自动暂停、唤醒和进程重启后持续探索调度。v1 和纯 Markdown 保持旧交付语义；选择 v2 后禁止静默降级。

```json
{
  "schemaVersion": "model_native_analysis.v2",
  "decision": { "action": "CONTINUE" },
  "output_type": "draft",
  "reportMarkdown": "模型草稿",
  "workspace": { "findings": ["模型明确声明的发现"] },
  "evidenceRequests": []
}
```

外层 Task 继续使用既有公共状态码，`NO_PRESENTABLE_RESULT` 可表示没有会话最终回答，不应据此认定模型分析失败或内容是草稿。v2 执行结果及安全元数据另行保留 `modelDecision`、`modelOutput`、`executionState`、`executionStopReason`、`publicationState` 和版本绑定。尚无 Final 声明时返回中性的执行事实提示，保留正文及其声明，不将主动完成解释成“结果整理失败”。预算停止不生成模型完成决定或内容类型。

`HarnessToolAccess.call(request, sources, runtimeIdentity)` 通过原有 `RuntimeExecutionCheckpointPort` 保存调用身份、参数与工具 Contract 指纹、原子领取记录和完整回执。相同请求恢复前复核现行授权；不同参数须使用不同 requestId。已提交结果可以跨 Worker 恢复；结果投影失败可重建证据。当前进程持有成功但未落盘结果时可以重试保存；重启后只剩领取记录时返回结果未知，不自动重做远程调用。批量子项分别领取和保存结果，取消继续传播；不承诺外部工具的 exactly-once 副作用。

大回执分块与索引在同一个数据库事务提交，读取与 CAS、分块清理共用所属分区锁。清理复用 `AgentRunRetentionScheduler`，只删除无检查点引用的分块；保留有效回执、请求领取和预算身份。没有引入自动删除这些执行事实的 TTL，否则相同请求可能被误认为从未执行。完整执行事实的退休/墓碑保留策略仍需明确的治理 Contract。

运行页面的“链路探索”复用 Task 事件与 Runtime timeline，不新增独立 Exploration Graph 存储或 Planner。`buildExplorationGraph` 将真实的 Goal、模型轮次、工具请求、证据返回/读取、模型显式 hypotheses/findings 与 decision 投影为稳定节点及关联。计划步骤不等于实际调用，模型调用记录中的 toolName 不等于工具执行。`receiptOrigin` 区分新执行、已提交回执恢复、已有结果重试保存及批量恢复，恢复不会被描述成新远程调用。默认隐藏可展开的运行事件，保留计划快照对照、节点详情及 JSON/SVG 导出；动态增加节点保留已有位置和视角，终态轮询补齐最后一轮观察。

图的类型与探索关联借鉴 ARTEX 的设计思想，代码独立实现，未复制 ARTEX 实现。公开展示的是模型显式声明的探索摘要与执行事实，不展示 workspace 私有工作笔记，不对模型结论进行质量评分或强制纠正。运行时动态 Skill 发现和长任务唤醒属于后续阶段，当前能力注入和授权工具集仍沿用现有机制。

本轮方法级改动：

| 组件 | 方法 | 调整 |
| --- | --- | --- |
| HarnessToolAccess | `call(request, sources, runtimeIdentity)` | 单独记录回执来源，保留原回执身份和恢复语义 |
| ModelNativeAnalysisHarness | `execute`、`recordIntent`、`explorationReceipt` | 记录模型内容类型、完成决定与版本绑定；Final 自动发起会话交付，未声明不推断草稿 |
| ModelAnalysisIntent / AdaptiveAnalysisController | `finalDeclared`、`publishRequested`、`retainedContent`、`continuing`、`decideModel` | 分离内容类型与执行意图；旧 PUBLISH 兼容，新 Final 无需 PUBLISH，读取旧正文别名 |
| FinalSynthesisNode / ReportPublicationGraph | `synthesizeFinal`、`publishModelReport`、`publishHarnessReport`、`execute` | 直接交付模型 Final，不重新生成正文或评审结论；保留权限和版本一致性校验 |
| AgentAnswerFinalizer / InteractionOrchestrationService | `finishWithDecision`、`chat` | 已交付 Final 不再要求 action=PUBLISH，不调用语义 reviewer；v2 正文及其引用不经过旧文本清洗 |
| AnalysisCoverageCoordinator / AnalysisSummaryGovernanceCoordinator | `analyze`、`finalizeSummary` | 既有摘要与证据元数据贯通 modelOutput，不复制内容语义裁决逻辑 |
| DefaultAnalysisWorkflowRuntime | `continueModelDirected`、`archive` | 通用链按模型 Final 交付并归档其声明，证据缺口不阻止交付 |
| AgentTaskService | `compileExecutionResult`、`ExecutionResultContract.safeMetadata` | 保留模型内容类型与执行事实；不再将未声明 Final 的正文称为草稿 |
| UserFacingContentSanitizer / UiArtifactService | `modelNativeContent`、`sanitizeUiResponse`、`externalizeIfNeeded`、`resource` | v2 正文来源在 UI 与资源 manifest 中贯通，授权资源读取保留模型引用 |
| ConversationMemoryService / ConversationService | `responseMemoryContext`、`toMessage` | 保存模型内容声明来源，会话重读不再清洗 v2 原文 |
| VisualizationPlanningNode / ReportVisualizationAudit | `execute`、`audit` | v2 无法绑定的图形保留为普通文本，不执行图形、不丢弃模型 payload；v1 兼容 |
| JdbcRuntimeEvidenceStore | `readExecutionCheckpoint`、`readExecutionCheckpointLocked`、`cleanupUnreferencedExecutionPayloads` | 分块读取、替换及清理一致性；有界游标扫描和无引用分块回收 |
| RuntimeExecutionCheckpointPort | `cleanupUnreferencedExecutionPayloads` | 可选默认维护方法，既有实现无需新增实现才能兼容 |
| AgentRunRetentionScheduler | `cleanup` | 复用既有维护周期，不新增执行调度器 |
| TasksView / explorationGraph | `loadExplorationTimeline`、`refreshRuntimeSnapshot`、`buildExplorationGraph` | 读取实际 timeline，切换任务隔离，增量续取与终态补齐，投影真实探索关联 |
| PlanDagGraph | `rebuild`、`focusReadableView` | 动态增长保留布局和视角，避免新增节点重叠，控制布局缓存，保留旧计划消费者默认行为 |

前端还调整了 TasksView 模板、样式与默认测试入口；更新两条与当前展示组件不一致的旧断言，没有为通过断言改变金融或工具治理逻辑。新增测试覆盖读取与替换/清理并发、无引用回收、模型显式状态展示、终态最后一轮加载、真实工具调用与模型推理记录的区分、草稿不发布和回执来源。
