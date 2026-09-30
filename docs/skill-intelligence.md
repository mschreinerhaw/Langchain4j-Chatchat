# Skill Intelligence 与 Google ADK 接入

2026-09-30：主 Agent 工作流现通过 ADK 渐进加载 Skill，形成贯穿计划、分析、验证和报告的请求内方法快照；未显式绑定时可发现已授权 Skill。它不会启动独立的业务取数流程。独立 Intelligence API 保留，以下引擎和固定数据绑定说明仍适用于该 API。主流程细节与验证见 [Skill 分析上下文贯穿 Runtime](skill-analysis-lifecycle-20260930.md)。

## 已实现的边界

Agent Runtime OS → SkillIntelligenceLayer → SkillCompositionRuntime / SkillRuntime → AgentRuntimeAdapter。
平台负责意图、授权、技能组合、依赖、数据获取、执行预算和结果评估；框架适配器不持有独立授权库。

当前可执行引擎：

- `GOOGLE_ADK_NATIVE`：google-adk-langchain4j 1.7.0，使用原生 ADK LlmAgent、Runner、SkillToolset。
  LangChain4j 在这条路径仅桥接现有模型连接；编排和技能工具循环由 ADK 执行。
- `LANGCHAIN4J`：现有平台 AgentRuntime 路径。
- `OPENAI_COMPATIBLE`：现有模型目录校验与运行路径。

`GOOGLE_ADK` 仍表示已有 A2A 外部 Agent 桥接，不与原生引擎混用。
Spring AI 独立 SDK 适配器尚未接入；请求 `SPRING_AI` 明确拒绝，不静默降级。
自建 Runtime 目前作为统一编排核心，不另包装一个同义 `NATIVE` 引擎标签。

## Agent 任务启用

在「领域技能」发布 Skill，并按需要在 Agent 中勾选。显式绑定限定候选范围；未绑定时允许自动发现当前身份可使用的已发布 Skill。发布和绑定本身不授予资源权限。

`AgentChatModeHandler` 与 `RoleChatModeHandler` 默认接入 `SkillAnalysisContextService`。该子阶段使用 Agent 配置的模型和原生 ADK 读取方法，等待结束后进入原工作流，不额外执行 Skill 的独立取数绑定。角色问答仍仅作说明，不执行 MCP 业务取数。没有可用或相关 Skill 时继续基础回答，不要求选择 MCP。

通过原 Agent 任务接口提交请求，沿用任务队列、取消信号、会话隔离和事件监控。Skill 阶段作为 `OBSERVATION_RECORDED` 输出；结果的 `metadata.skillAnalysisContext` 提供状态、Skill 版本和指纹。完整方法快照保留在 Runtime 内部上下文，贯穿计划、取数语义裁决、数据分析、验证和报告。

`ds_domain_skill.execution_engine/execution_model` 和旧 `skillIntelligenceEngine` 配置继续服务于独立 Skill Runtime / Intelligence 调用，不决定主业务 DAG 的路由。独立执行时可以按 Skill 的发布配置覆盖请求引擎和模型；这与主流程的方法上下文准备是不同职责。对应历史数据库迁移仍为 `V20260929_03__skill_execution_binding.sql`，本次生命周期接入没有新增表。

## 独立分析 API

需要平台认证和当前 Agent 的访问权限：

- `POST /api/v1/data-science/domain-skills/intelligence/plan`
- `POST /api/v1/data-science/domain-skills/intelligence/execute`

```json
{
  "query": "分析该组合的收益来源和风险，并说明缺少哪些数据",
  "agentId": "已授权的 Agent ID",
  "modelName": "平台中可用的模型名称",
  "engine": "GOOGLE_ADK_NATIVE",
  "skillIds": ["可选的技能范围"],
  "capabilities": [],
  "workflowIds": {},
  "inputs": {"portfolioId": "组合标识"},
  "maxSkills": 4
}
```

空 `capabilities` 由模型从已授权候选技能的能力列表中选择；不允许发明能力。
`workflowIds` 为 skillId → 已授权 workflowId 的可选映射。多工作流时应明确选择。
显式请求的能力不会因模型规划而被丢弃。模型规划失败标记 `PLANNING_UNAVAILABLE`；
无显式能力时不强行执行。执行结果存入现有 analysis-runs，不创建最近对话。
独立 API 为同步调用；现有 Agent 任务接口用于异步运行与实时监控。

## 安全与生命周期

1. 候选先做角色/Agent 授权，最多 40 条元数据参与意图识别；正文按执行选择加载。
2. ADK SkillSource 为单次执行构建，只有选中的技能，加载正文/资源时再次校验权限及版本。
3. 不开放脚本、文件系统、任意 SQL、网络请求或原始 MCP 工具。SkillToolset 只有读取工具。
4. 有数据契约的技能由 SkillDataAcquisition 调用唯一匹配的已授权工作流，证据含 provenance。
5. 纯说明技能只有在未声明外部数据/工具/工作流需求时可执行内置 instruction-only 分支；
   不因用户问题要求而自动授予数据权限。它只产生知识型分析，不表示完成了数据核实。
6. 普通正文更新仍不编译；下一次请求使用新正文/版本。执行中的版本变化将拒绝继续加载。
7. 补充轮次最多 3 轮，总技能次数最多 8（默认 4），不会重复执行同一技能。
8. 意图模型调用超时 30 秒；ADK 每技能最多 6 次模型调用、64 个事件、60 秒。
   整体调度预算 120 秒，在阶段间检查；数据工作流仍服从自身超时。不是跨外部服务的强制终止保证。
9. 意图模型最多 4 个并发工作线程，ADK 最多 8 个；过载拒绝，任务不无限排队。

评估检查能力覆盖、执行状态、必需数据可用性与来源、确定性计算步骤状态。
缺少用户参数提前返回 `USER_INPUT_REQUIRED`，取数失败保留局部结果并尝试其他已授权技能。
`COMPLETED` 表示上述结构性条件满足，不是事实正确性保证；生产分析仍需人工审核。
尚无跨模型 token/金额总预算或基于真实业务标注的质量评测，需上线前补充验证。

## 验证

离线测试覆盖真实 ADK 的 load_skill → 模型回复循环（mock 模型，不产生外部费用）、最新正文、
未知技能/撤权拒绝、能力幻觉拒绝、补充技能、输入缺失、执行预算及 API 身份隔离。
上线前还需用部署环境中的已发布模型及真实数据绑定进行验收，不能以离线通过代替生产质量评测。
