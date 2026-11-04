# Cline 报告链路源码与运行审查

审查日期：2026-11-04。基线 `1dda875e0`，实施分支 `feature/cline-report-skills-integration`。先审查，后实施；本文件的运行事实不等于改造后验收通过。

## 已取得的运行证据

经用户指定环境的真实登录，读取既有任务 `d6e12490-d51c-494e-bc3a-dba8ba58162c`（财经资讯分析 Agent，问题为“华是科技亏损主要原因是什么”）。答案 25,248 字符，含 23 处“记录 N”，混入行情快照、逐条摘要与不相关资讯，末尾才出现业务结论。原任务为 PARTIAL，不能称完整成功。任务书举例为“华星科技”，实际历史任务为“华是科技”，两者不能混同。

实际 Cline 任务 `analysis-coding-0a3b711603489af1256f03c83bf1db2a1ca492d9e0ac68ffe025f7ffd0babe62` 经三次真实 Python 执行，第一次退出 0 但结果合同失败，后两次结构校验通过。故此案例并非简单的工具结果直接透传，也并非根本没有 Python：Cline 生成了包含大量原始记录的报告，现有结构和来源复制校验未拦截这种格式化复制。其配置未带 `reportMode`；仅凭该历史记录无法断言用户当时勾选了图文模式。

部署基线：API JAR SHA-256 `bd405a26c022147cb4701e26933a739f1366729ee81935d781173203230d6fb6`；Worker 镜像 `runtime-os-coding-agent:resource-completion-47e7a72b2415`，Node `v24.21.0`。受权读取的原始任务和调用记录暂存 `target/cline-report-baseline`，登录材料不进入版本库；交付证据需脱敏。

## 调用链与现有组件

1. `ClineContextAnswerService.answer` 绑定用户、Agent、材料与图文偏好；`CodingAnalysisService` 构造原有 report row schema、工具和任务合同。
2. `ClineCodingAdapter` 连接 Node；`coding-agent-node/src/runner.js` 使用固定 `@cline/{agents,core,sdk}` 0.0.93，自主调用 `input_read`、`skill_read`、资源工具、`python_execute` 和报告复核工具。
3. `skills.js::taskSkills/readBuiltin` 从镜像 `skills/catalog.json` 提供元数据，正文按需读取；原生内容经 Cline parser 和哈希核验；内置同名保护、动态技能优先级与引用边界已存在。Docker 以 node 用户运行，`.cline/skills` 指向镜像包。不能假定裸 Agent SDK 自行发现磁盘技能。
4. `CodingKnowledgeService.call/resourceCall` 每次重验当前权限，经 `CodingResourceReader` 固定 MCP 控制面访问已有文档、新闻和行情；证据 ID 与源版本绑定。不能用索引命中替代真实源读取。
5. `CodingPythonExecutionBridge.start` 使用已授权的不可变输入，通过 MCP Python Sandbox 执行。`CodingDataSupplyService.bindInputs` 已有逐次执行输入版本冻结机制。
6. `CodingEvidenceCollector.collect/collectText` 核验执行停止、文件、结果 schema 和证据；`VisualReportArtifacts.collect` 校验并归档图表。`ClineModelCallbackController` 提供 artifact_read 和引用复核。`AnalysisCoverageCoordinator` 从注册结果取得报告，不由 Java 再撰写正文。
7. 前端已有 `ReportRenderer`、`ReportMarkdown`、`PythonVisualReport`、`PythonReportChart`；沿现有报告资源授权下载和历史恢复路径展示。是否需修改前端应由实际浏览器验收决定。

## 已定位缺口
第四轮 `03fdc3ce-2be4-46dd-b784-edbac7463d70` 又给出真实的收尾路径证据：Cline 已交付“数据不包含该客户”的限定报告，`AgentAnswerFinalizer.finishWithDecision` 仍调用 `AgentResultPresentationService.appendToolResultTable`，在正文后附上大量原始市场/资讯行。其 `governedAnalysisResponse` 只识别旧 returnedDataAnalysisRequired/governedNarrativeAnalysisAppended 标志，漏掉 codingOwnedAnalysisActive。补充该既有所有权标志，保留结构化附件与证据，禁止在 Cline 已拥有报告时追加原始表。回归同时覆盖旧标志及 Coding 所有权，普通明细查询维持原回退。此缺陷独立于 Cline 自行编写逐条记录，因此需分别修复。

图文交付进一步实测：`e8abe700-85db-4a8d-99c9-f8c2a906119c` 的最终 Runtime 元数据与 `primaryCodingReceipt.visualReport` 都保留图文对象，浏览器实际请求了 `resources/visual-report`，不能将此例称为 Java 丢失图表。PNG 中文已可读，多来源元素未明确 `dataRefs`，现有校验记录 `ELEMENT_SOURCE_AMBIGUOUS`，保留其展示警示。首次浏览器脚本未滚动到懒加载图表而误判无图；修复脚本并正常重开历史后，真实图表下载 HTTP 200，1200×600 图片完整加载，页面与历史均通过。无需更改前端图表组件来绕过校验。另在源码交付边界发现，读取已验证的 visual receipt 仍额外要求 DATA_ANALYSIS 分类；将其改为本次明确的 visual 偏好，普通 off 模式不接收或恢复旧 visual。两种偏好以及非 DATA_ANALYSIS 分类的传播回归验证，均不增加执行权限。

真实浏览器提交任务的 QUESTION 事件还有前端 `responseContract.prompt`：`ChatAssistantView.js::RESPONSE_RENDER_CONTRACT` 无条件要求展示所有返回行，包括不符合目标的记录。这与 Cline 的报告筛选/去重提示冲突，也是报告出现逐条清单的上游诱因。将明细保留要求限定为用户明确请求明细/列表；分析报告使用相关证据与计算摘要，源明细仍由现有证据与产物保存。元数据物理字段展示规则不变。该修复不指定业务结论，需用部署后的真实 UI 请求验证新合同。


第一轮候选真实任务 `fbdf04fc-1633-3944-800e-da1bb34039ff` 另暴露了修复反馈缺口：三次已归档验证均为 `rowSchemaMatched=true`、`declaredReportScopeConsistent=false`，并非文件字段/类型错误。Cline 以 UNCONFIRMED 字段声明提交 COMPLETE，现有结构一致性规则正确拒绝，但 callback 仅返回笼统格式诊断，导致重复修复。补充读取同一任务验证记录中的固定布尔白名单，并返回既有 REPORT_SCOPE_RULE；不返回被拒绝的源值、不降低校验规则、不由 Java 修改报告结论。首轮失败必须保留为验收问题，不能用后续修复覆盖。

同轮任务 `15d18607-f7f6-4bb8-b42f-c81ee931eeae` 证明 Agent 配置和用户授权是两层交集：配置启用了行情/新闻，但 admin 未获得 DATA_RESOURCE 显式授权，实际合同只有 knowledge_documents。数据库中的两项 ALLOW 属于另一用户，当前 admin 的 system:api:all 不应绕过资源边界。该任务诚实交付缺少行情的部分报告，没有 Python/虚构图表；已通过正常会话历史加载并实际请求归档 answer 资源。补充资源授权需用户明确同意。

客户任务 `ee474911-b1d7-3c7b-9f72-a76f533ce955` 真实执行 5 次 Python，最后两次 VALIDATED，但 12 轮模型预算耗尽前又改写报告，未完成显式 artifact_read 复核，最终正确标记 FAILED。追加已有复核路径的剩余轮次提示：有完整、引用和图表技术校验未失败的既存报告时，最终模型轮次只用于自主审阅和确认；若认为报告不受支持，仍保持不完成。没有增加预算或跳过复核。该客户任务的 PNG 已通过真实用户资产 API 下载并校验 SHA-256，人工检查发现中文方框；归档代码先设置 CJK 字体，随后调用 `rcParams.update(rcParamsDefault)` 又将其重置。追加技术指导禁止此类重置，后续真实输出仍需重新检查，不能以这次图表作为可读性通过证据。

- `ComputedResultBoundary` 只保留超过 2,048 字符的单段源文本，并比较完整容器/记录。将多个较短源字段改排成 Markdown 后，既不等于完整 JSON，也不包含足够长的单段原文；实际原始记录列表因此通过校验。需对大篇幅重复引用多个源片段补充结构性检查，允许简短证据引用，不由 Java 判断业务结论。
- `CodingAnalysisService.run` 对 `knowledgeTask` 删除 python_execute，并授予空 sideEffects；资源任务目标同时声明“no code-execution permission”。图文偏好即使抵达这一分支，也不能凭技能获得真实图表能力。应复用当前输入快照与执行冻结，交付已授权资源读取结果后才允许 Sandbox 分析，不能直接开放数据库、网络或任意 MCP。
- 默认包有 Python 分析及微软技能，缺少专用的报告交付方法；安装技能不能修复上述合同与校验缺口。
- 图文 schema、文件清单、SHA-256、Silo 与前端组件已存在，不应另起 Manifest、报告模型或渲染产品。
- 资源 report_submit 自复核在同一 Cline 会话完成。Java `tool_result_review` 属于原工具编排路径，应依据本次真实调用记录检查是否存在重复；不能全局删除安全/参数治理来减少调用。

## 改造约束

保持 Java 权限、停止证明、预算、租户用户隔离和历史数据。Java 只做交付校验与授权快照，不选择企业结论或固定报告章节，不增加额外 LLM 报告流程。复用已有 Python 环境与报告 schema。远端部署必须留存精确回滚，不改既有生产参数、不执行破坏性或不可逆变更。真实 MCP、Python 图表、页面展示、历史恢复及失败场景需分别记录，未验证项不能标通过。
