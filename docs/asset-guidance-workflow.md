# Asset Guidance Workflow

## Runtime OS 四类工作流

| 顶层分类 | 分析对象 | 执行边界 |
| --- | --- | --- |
| DOCUMENT | 已授权文档证据 | 文档检索、必要补证、解释 |
| DATA_ANALYSIS | 业务数据 | 受控取数、领域分析、计算 |
| ASSET_GUIDANCE | 已发布 MCP 资产模板 | 模板检索、参数/用途解释、领域使用建议 |
| ACTION | 待执行任务 | 原有授权、确认与操作治理 |

`RuntimeWorkflowFamily` 是顶层语义分类。现有 `AnalysisWorkflowType` 执行子类型保持兼容，新增 `ASSET_GUIDANCE`；补证、联邦调用等仍是内部机制，不是新的用户场景分类。本次不重写既有 Action 或 Data Analysis 执行引擎。

## 入口与步骤

Agent 问答识别“这个 API/表是做什么用”“哪些场景、使用效果如何”“怎么使用”“需要什么数据”后，在 Skill Intelligence 数据分析入口之前进入资产指导工作流。也可通过现有请求的 `toolInput.workflowFamily=ASSET_GUIDANCE` 显式选择；`toolInput.assetTemplateId` 可指定候选模板 ID。显式选择其他类型时不使用资产指导的自动匹配。

角色问答继续遵循“不调用 MCP”的既有约束，不自动进入需要 MCP 模板检索的路径。需要实时模板指导时使用 Agent 问答。

流程：资产目标识别 → 已授权 MCP 模板检索 → 使用证据检查 → 能力解释 → 已发布领域 Skill 增强 → 固定模板返回。

### Runtime 驱动的单向状态机

`execute()` 只生成 Asset Understanding Plan，不调用 MCP，也不生成最终答案。Runtime 顺序驱动 `TEMPLATE_RESOLVE → DATA_ACQUISITION_REQUIRED → DATA_BUNDLE_READY → DOMAIN_SKILL_ENRICHMENT → GUIDANCE_SYNTHESIS → GUIDANCE_READY`。没有模板时按 AssetType、Domain（未知则明确为 UNSPECIFIED）和 Intent 生成默认 `GuidanceDataRequestPlan`；有模板则从声明契约补充需求。模板已经返回的元数据直接复用，不重复调用。

默认需求交给受控 MCP 元数据获取适配器，仅允许 `ASSET_DISCOVERY`；模板解析仅允许 `TEMPLATE_DISCOVERY`。两阶段都遵守现有通用工具 Top-K、MCP 目录权限、角色资源权限及 Tool Runtime 执行治理。不会恢复 Top-K 未选中的工具，也不会遍历下一批工具。不调用业务数据 `TemplateSkillDataWorkflow`，因为其执行模板获取业务行数据，与元数据限定不符；复用的是 MCP 策略、授权、委派和 `ToolRuntimeService` 执行链路。

每个阶段最多一次，无重入、无分页补检索循环。缺少资产信息时执行一次默认元数据搜索，仍缺失则 ASK；存在歧义则要求用户选择，不混合资产。Runtime 将有证据但事实不完整或待选择映射为 `PARTIAL_SUCCESS`，最终无证据映射为 `NO_PRESENTABLE_RESULT`。响应包含 `dataRequestPlan`、`runtimeGuidanceDecision` 和 `guidanceExecutionPolicy=PLAN_ONCE_ACQUIRE_ONCE_NO_REENTRY`；说明文本不能被当成分析成功。

无证据诊断保留候选为空、缺少发现契约、授权拒绝和已检索但无匹配等区别；候选为空可能来自 Top-K 或权限过滤，不一概认定为角色未授权。

检索复用 `AgentToolPolicyResolver`、`ToolRuntimeService` 和已有动态子工具到父工具的委派。只允许发布契约声明为 `TEMPLATE_DISCOVERY` / `ASSET_DISCOVERY` 的读取工具，不能用 `TEMPLATE_EXECUTION` 或只读 SQL 代替。用户不能传入工具名称、SQL、模板执行参数来扩展此流程的权限。每个阶段重新解析用户/角色/Agent 权限，保留 Runtime 事件及证据存档。

## 资产上下文与输出

`AssetContext` 统一承载模板 ID、类型、名称、用途说明、技术元数据、使用/质量元数据、检索工具和来源版本信息。当前已有模板协议实际提供的是用途、分类和参数契约；没有提供的事实保持缺失，不从普通描述中提取成真实统计。

固定五段：

1. 这个资产是什么。
2. 可以解决什么问题（发布方声明，不是已验证效果）。
3. 当前在哪里使用、效果如何（无证据明确标注）。
4. 怎么使用（参数契约 + 领域建议）。
5. 注意事项、执行边界和来源。

Skill 在此处是**解释增强**，不是执行分析任务：只检索当前 Agent 已绑定、已发布、已授权的技能说明，最多 3 个；通过无工具模型调用生成明确标注为“推断、未验证”的建议。它不会进入 Skill 数据获取或执行适配器。模型使用当前 Agent 的绑定模型，未绑定时使用系统默认模型，30 秒超时后保留模板事实并降级。应用的 Skill ID 与状态写入响应和事件。

多模板存在歧义时返回候选并要求选择，不合并不同资产的参数。每次最多检索 4 个候选发现工具、20 个模板；分页不完整时返回 `hasMore` 和范围限制，不冒充全部目录。模板 ID 只在授权返回结果中匹配，不能越过发布范围。

## 非目标与后续协议扩展

本次没有新增资产管理数据库，也没有伪造使用日志、质量统计、输出字段或血缘。当前模板协议没有这些证据，因此指导结果为 `PARTIAL`，这不是运行失败。未命中为 `NO_EVIDENCE`；需选择为 `NEEDS_SELECTION`。

若后续 MCP 模板协议发布结构化输出契约、指标能力映射、实际使用场景、带时间窗口及来源的成功率/延迟等，可通过 `AssetGuidanceSource` 扩展，不需要改变工作流和执行授权边界。缺少元数据不会触发通用补证工作流，更不会自动执行取数补齐。
