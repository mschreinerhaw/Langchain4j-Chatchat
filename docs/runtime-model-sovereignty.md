# Model Sovereignty, Runtime Governance

## Runtime OS Semantic Non-Interference Principle

模型拥有分析主权，用户拥有最终采纳及对外确认权，Runtime 只拥有执行治理权。内容为 Intermediate、Draft 或 Final，由模型声明；执行完成、证据缺口、预算耗尽或缺少独立发布动作，都不能替代模型的内容声明。

v2 增补 `output_type` 和 `content`：模型声明 Final 后直接向当前用户交付，无需第二次模型评审或独立 PUBLISH。旧 PUBLISH 是兼容的模型显式 Final 意图；显式内容声明优先。未声明类型的内容记录 UNDECLARED，以中性 `modelAnalysisOutput` 保存。`modelNativeReportDraft` 为旧协议及旧数据的兼容字段，不作为 v2 内容语义判据。

`modelOutput` 记录模型声明、正文哈希、证据快照和 CURRENT_SESSION 目标；`executionState`、`executionStopReason` 只描述执行事实；`publicationState` 是兼容的会话交付事实，不表示报告被平台认证。权限、安全、协议版本绑定、取消及显式 Contract 仍执行；证据诊断不得改写模型内容类型。对外发送、入库或其他操作必须走独立授权工具，模型 Final 不授予外部操作权限。

v2 已交付正文不再使用旧的 UserFacingAnswerSanitizer 删除模型引用的工具名、分块标识或证据索引；答案收尾与统一交互边界均保留模型正文，会话记忆保存同一文本。HTML 安全渲染、工具权限、版本绑定及显式数据约束不受影响。旧 v1 展示清洗继续兼容。

WAIT 自动恢复、动态 Skill 发现和长时探索调度尚未由此次内容语义调整实现。

模型拥有分析与业务决策自主权，包括理解问题、规划、工具选择、推理、证据充分性判断、补证选择、结论生成及发布意图。Runtime OS 提供可信的执行环境、能力与数据、状态管理和资源治理。除明确的权限、安全及业务 Contract 外，Runtime 不因分析质量评价阻断模型输出，不评分、不改写结论。

## 职责边界

| 决策或能力 | 模型 | Runtime OS |
| --- | --- | --- |
| 理解问题、规划、推理 | 主导 | 提供上下文、Skill 和领域知识 |
| 工具与计算选择 | 自主选择 | 发现能力，执行授权、隔离和调用 |
| 证据充分性、补证选择 | 自主判断 | 呈现来源、缺口及读取事实，执行请求 |
| 结论与发布意图 | 自主决定 | 执行明确的发布 Contract |
| 报告质量 | 模型负责 | 不评价、不修正、不替代 |
| 超时、并发、重试、预算 | 使用执行能力 | 提供运行保障并记录执行事实 |

Runtime 可以记录第二个分块未返回、字段引用不存在或源读取失败；这些观察不能自动推出结论不可靠，也不能触发强制补证或扣留正文。模型可以根据其他证据选择直接发布。显式工具请求仍必须满足权限和执行合同；来源内容与模型声明不能扩展授权。

跨时间推断、累计计数解释、精度、相关性和系统保证属于分析问题。改进分析可通过模型选择的 Skill、专业知识检索、计算验证、反思及可选审查实现。Runtime 不内置这些判断规则，也不自动新增审查 Agent。

## 当前实现

`ModelNativeAnalysisHarness` 接受模型直接完成或请求继续读取。`evidenceAssessment` 为可选的模型声明；`completed` 表达本轮发布意图。`requiresReanalysis` 等声明用于溯源，不替代模型发出的 `evidenceRequests`，不触发新的模型调用。

`ModelEvidenceAssessmentAudit` 有界记录原始声明、引用来源、记录号、字段路径、读取诊断与语义来源。所有诊断保持 `publicationEffect=NONE`，不存在“结论已被 Runtime 证实”的状态。超出审计资源限额也只记录审计限制。

每轮声明保存在 `modelEvidenceAssessmentHistory`，与输入/输出检查点和读取回执一起形成轨迹。模型在最终报告中改变证据处理决定或不再提供声明时，历史观察仍保留；Runtime 不把旧声明覆盖到新报告上，也不要求模型沿用旧判断。

当前声明及历史进入分析 summary 的证据元数据，并通过既有 Evidence Bundle 归档接口保存，沿用租户、用户和运行隔离。原始证据与 limitations 保留。

`AnalysisExecutionOutcome` 描述执行事实。数据返回、处理或轨迹不完整可以形成部分完成状态；模型对证据充分性的声明独立保存。`COMPLETED` 不代表分析正确，`PARTIALLY_COMPLETED` 不代表报告质量不合格。最终装配保留已有执行状态，不以正文可展示为由改成完整完成。

必要发布约束包括授权、隔离、工具/数据合同和可展示的报告载荷；不把内部协议或执行指令当作业务正文。新增强制业务 Contract 必须来自明确配置或授权，不能由 Runtime 的分析偏好推导。

## 2026-10-10 验证

本次曾联机试验强制 Evidence Claim Validator：首轮真实任务在修正预算耗尽后被拦截，另一轮发布部分报告。这是已撤销的实验，不作为最终架构。最终实现移除结论规则、强制修正、声明校验门禁及其测试工具，保留事实审计和状态归档。

回归验证模型即使声明缺口、范围矛盾或 `requiresReanalysis=true`，仍可选择完成并保持原正文；不增加 Runtime 修正调用。另验证无效引用仅记录观察、历史不丢失、跨运行读取仍受隔离、预算治理继续有效、执行状态与 Evidence Bundle 归档保持一致。

本地 clean 构建后的 52 项定向回归通过，后端包构建通过。较早的扩展回归有一项既有 `reviewerTimeoutUsesConfiguredModelTimeout` 超时失败；用早上的原始 finalizer 复现约 5.1 秒，超过测试的 3 秒上限。本次没有修改该超时逻辑。

已部署到 `192.168.195.221:8080`，首页返回 200，重新登录后的 `/api/v1/health` 返回 `UP`。最终发布包 SHA-256：`657d338075acdba9db8379b405610ac48e936a55bfa286d541f3e9f6b8df3cf9`；包内没有 `EvidenceClaimValidator`。回退包目录：`/opt/chatchat-deploy-backup/evidence-claim-validation-20261010-vPdo1b`。

在已部署应用 classpath 上运行 `ModelSovereigntyReleaseProbe`：模型声明缺口、范围矛盾、无效引用及重分析偏好，仍按其 `completed=true` 发布原正文；模型调用一次，引用诊断保留，`publicationEffect=NONE`，没有结论门禁。该验收使用受控模型夹具，与真实模型调用分开记录。

最终真实只读分析任务 `240f863c-b71d-4f90-b777-380141f5b21b` 返回 `SUCCESS`，运行与执行 outcome 均为 `COMPLETED`。模型自主执行一次 READ_RECORDS 和四次 READ_TEXT，共三轮分析模型调用，随后自主结束；没有强制修正或结论校验事件。三轮声明历史保留，最终声明仍列出未读区间、时区及其他指标缺口，`publicationEffect=NONE`。这表示执行完成，不代表全部证据已经被语义审阅或业务结论得到平台认证。

草稿与发布 Contract 正文 SHA-256 一致：`58d37ad239c0b37e2002d6a17d91a7f9686703fe520c5eb906a88ae29f88b243`。真实任务运行于包 `06ba5f9383bf1479dc6ebca12089f0d3d04cf5b11bd325d8bd55eaff93470fff`。联机发现最终装配会重建 summary；最终包仅补齐审计及历史向新 summary 的传递。该补充不改变分析或发布决策，通过本地回归及最终远端夹具验证。

历史实验和最终验收原始记录位于本地 `target/codex-live/claim-validation-20261010/`，不包含登录令牌。
