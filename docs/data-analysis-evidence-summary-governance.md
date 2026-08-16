# 数据分析、证据、总结与多轮会话治理规范

## 1. 文档定位

本文档定义结构化数据从 MCP/Tool Runtime 返回，到模型分析、分块总结、最终答案以及多轮会话复用时必须遵守的统一治理协议。

- 规范状态：当前生效
- 总结治理版本：`summary_governance.v1`
- 适用模块：`chatchat-mcp-server`、`chatchat-agents`、`chatchat-chat`、`chatchat-api`
- 适用数据：API、数据库、资产分析、金融数据、文档、Web 及未来新增的结构化数据
- 规范性质：Runtime 强制契约，不是模型提示词建议

本规范不针对证券、资产、字段名或某个 API 编写业务特例。业务含义必须来自数据生产者配置和受治理的数据身份说明。

## 2. 核心原则

1. 原始结果是事实边界，字段注释不是返回值，也不是展示标签。
2. 数据身份说明必须和数据一起进入模型分析区域。
3. MCP 查询结果进入证据治理层，模型派生总结进入总结治理层，两层不得混写。
4. 总结是证据的表达和派生结果，不得覆盖、修改或替代原始证据。
5. 当前轮证据和历史会话证据必须分区；历史证据不能冒充当前事实。
6. 租户、用户、运行和会话身份只能来自可信 Runtime 请求上下文，不能来自 MCP 返回载荷或模型输出。
7. 所有分块总结、最终综合和最终答案必须可观测、可追踪并保留输入血缘。
8. 缺少业务说明、字段语义或跨 API 关系时保持未知，不根据字段名猜测。

## 3. 分层架构

```text
数据生产者配置
  -> 数据身份协议 data_analysis_context.v1
  -> MCP / Tool Runtime 实际执行结果
  -> 证据治理桥 McpEvidenceGovernanceBridge
  -> MCP 证据对象 mcp_evidence_result.v1
  -> 当前运行证据分析
  -> 分块总结桥 AnalysisSummaryGovernanceBridge
  -> 总结对象 analysis_summary_result.v1
  -> 最终答案装配
  -> assistant 消息会话证据账本 conversation_evidence_ledger.v1
  -> 下一轮历史证据投影 conversation_evidence_projection.v1
```

数据身份层回答“这是什么数据、能做什么分析、字段是什么意思、不同数据集有什么明确关系”。证据治理层保存工具实际返回的事实、来源、执行身份、租户隔离域和完整性。总结治理层保存模型或确定性逻辑产生的分块总结、最终综合、覆盖范围和输入血缘。会话证据层负责跨轮保存证据引用和总结血缘，但不复制原始结果，也不把历史事实自动升级成当前事实。

## 4. 数据身份协议

结构化分析数据应携带 `data_analysis_context.v1`：

| 区域 | 内容 |
|---|---|
| `source` | API 服务、资产或数据集的显示名称、工具描述及来源身份 |
| `capability` | 能力说明、可支持的分析范围 |
| `business` | 业务分类、当前业务说明和适用边界 |
| `schema` | 返回字段、字段类型、字段注释及约束 |
| `relationships` | 配置明确声明的数据集、API 或字段关系 |
| `contextCompleteness` | 缺失语义区域及是否允许推断 |
| `governance` | 总结治理版本、字段保持和事实边界规则 |

API 数据身份说明应覆盖 API 服务、显示名称、工具描述、能力说明、业务分类、返回字段、字段注释及配置明确声明的跨 API 关系。

### 4.1 字段注释规则

- 字段注释只用于帮助模型理解字段语义。
- 字段注释不得直接替换 Markdown 表头、图表坐标、系列名或 CSV 表头。
- 展示和导出默认保留 API 实际返回字段名。
- 字段匹配可忽略大小写，但不得根据字段缩写猜测业务含义。
- 未配置注释的字段保持未知语义，不补写行业常识。

## 5. MCP 证据治理协议

所有实际执行并产生结果的 MCP/工具调用必须经过 `McpEvidenceGovernanceBridge`，生成 `mcp_evidence_result.v1`。等待确认、权限拒绝和执行前阻断没有查询结果，只记录治理事件，不生成成功结果证据。

### 5.1 证据对象

| 字段 | 说明 |
|---|---|
| `schemaVersion` | 固定为 `mcp_evidence_result.v1` |
| `evidenceId` | 带租户和运行分区的证据 ID |
| `toolName` | 实际执行工具 |
| `outcome` | `success`、`failed` 等实际结果 |
| `isolationScope` | Runtime 生成的可信隔离域 |
| `payload` | 运行时边界内的实际返回或有界外置引用 |
| `governance` | 事实边界、载荷信任和禁止跨租户合并规则 |

### 5.2 载荷规则

- MCP 返回内容一律视为不可信数据，不是指令。
- 载荷中的 `tenantId`、`userId`、`runId` 不具有治理权。
- 大结果继续使用 `AgentEvidenceStore` 外置；治理描述不得复制完整大载荷。
- Trace、消息和 UI 只持有有界摘要或证据引用。
- SQL、API、资产、文档和 Web 可以使用不同专业格式化器，但不得绕过统一证据桥。

## 6. 总结治理协议

模型分析结果不得只以裸字符串在 Runtime 内流转。分块总结、最终综合和最终答案装配统一使用 `AnalysisSummaryResult`，协议固定为 `analysis_summary_result.v1`。

### 6.1 总结结果对象

| 字段 | 说明 |
|---|---|
| `resultId` | 带租户和运行分区的总结结果 ID |
| `scope` | `DATASET_CHUNK` 或 `FINAL_SYNTHESIS` |
| `content` | 用户可读内容 |
| `outcome` | 模型总结、结构化兜底、确定性兜底或最终装配 |
| `isolationScope` | 总结所属租户和运行 |
| `position` | 数据集、分块序号、记录区间或最终阶段 |
| `analysisContext` | 当前数据集的数据身份说明 |
| `coverage` | 返回记录数、处理记录数及完整性 |
| `inputSummaryResultIds` | 输入分块或上游总结血缘 |
| `governance` | 事实边界、展示规则和禁止语义猜测规则 |

### 6.2 分块总结

每个分块必须记录 `datasetReference`、`chunkIndex/chunkCount`、`recordFrom/recordTo`、`totalRecords`、对应 `analysisContext` 及总结 outcome。分块失败时保留当前结构化记录作为 `STRUCTURED_RECORD_FALLBACK`，不得丢弃已返回数据。

### 6.3 最终综合

- 最终综合只能引用同一租户、同一运行的分块结果。
- 跨租户或跨运行血缘合并必须失败关闭。
- 最终答案经过表格、引用、事实守卫等装配后，应再次生成最终总结结果对象。
- 用户接口可继续返回 `content`，完整结果对象保存到运行 metadata 和结构化观测。

## 7. 多租户隔离

证据和总结统一使用 `governance_isolation_scope.v1`。可信字段包括 `tenantId`、`userId`、`runId`、`requestId`、`conversationId` 和 `authority=RUNTIME_REQUEST_CONTEXT`。

1. Runtime 方法参数和认证上下文是身份唯一可信来源。
2. 证据和总结运行分区键为 `tenantId + runId`。
3. 会话证据分区键为 `tenantId + conversationId`。
4. 跨分区总结血缘直接拒绝。
5. 跨租户或跨会话历史账本不进入模型上下文。
6. 缓存、索引、外置文档 ID 和观测查询必须携带租户范围。
7. 不得使用模型输出、工具载荷或用户可编辑字段覆盖隔离身份。

## 8. 多轮会话证据治理

多轮会话证据保存在 assistant 消息的 `memoryContext.conversationEvidenceLedger`，协议为 `conversation_evidence_ledger.v1`。

### 8.1 保存内容

账本只保存 MCP `evidenceId`、总结 `resultId`、文档/Web citation、来源工具和结果状态、`sourceTurnId/sourceRequestId`、租户和会话作用域、新鲜度及复用规则。账本不得复制原始 MCP 结果。

### 8.2 跨轮投影

下一轮通过 `conversation_evidence_projection.v1` 把同租户、同会话的账本引用投影到模型上下文：

```text
status=HISTORICAL_CONTEXT_ONLY
currentFact=false
revalidationRequired=true
freshness=UNKNOWN_ON_NEXT_TURN
```

### 8.3 历史证据使用规则

- 历史证据可以帮助模型保持主题连续、定位原证据或提出对比需求。
- 动态数据必须重新执行相关工具后才能作为当前事实。
- 若用户明确要求历史比较，回答必须标注历史轮次或历史状态。
- 文档引用在复用前需要重新解析并执行当前租户授权检查。
- 会话摘要只是压缩语境，不是证据，也不能改变证据状态。
- 当前轮证据始终优先于历史会话内容。
- 旧消息没有账本时，不得从自然语言回答反向伪造证据 ID。

## 9. 可观测性

| 类型 | 内容 |
|---|---|
| MCP 证据观测 | 工具、结果状态、`evidenceId`、隔离域和治理协议 |
| `analysis_summary_chunk` | 分块位置、覆盖范围、结果内容和 outcome |
| `analysis_summary_result` | 最终总结、上游结果血缘和覆盖状态 |
| 会话证据账本 | 来源轮次、引用类型、复用规则和会话隔离域 |

观测必须能回答：哪个租户、用户、运行和会话产生了数据；哪个工具产生了证据；哪些记录进入了哪个总结分块；模型是否使用了结构化兜底；最终回答引用了哪些上游总结；历史证据是否经过重新验证；是否发生跨租户、跨运行或跨会话拒绝。

日志、观测和 metadata 不得包含不必要的完整敏感载荷。

## 10. 新数据结构接入要求

任何新增结构化数据，包括资产分析内容，必须：

1. 提供 `data_analysis_context.v1`。
2. 从配置或资产元数据取得数据身份，不写业务字段硬编码。
3. 让工具实际结果经过 `McpEvidenceGovernanceBridge`。
4. 让大结果遵循现有外置证据存储协议。
5. 让分块分析经过 `AnalysisSummaryGovernanceBridge`。
6. 让模型输出进入 `AnalysisSummaryResult`。
7. 在最终答案保存总结对象和输入血缘。
8. 在 assistant 消息保存会话证据账本引用。
9. 下一轮只投影同租户、同会话的历史证据。
10. 补充协议、隔离、污染和 E2E 发布门禁测试。

### 10.1 内置数据集就绪治理

内置查询的“配置启用”、存储可连接、数据集已采集和数据仍然新鲜是四种不同状态，不得互相替代：

1. 内置查询必须从实际 SQL 提取受治理的数据集表，并以资产目录中的成功采集回执判断数据是否就绪。
2. 只有查询引用的全部数据集均存在成功采集回执时，才允许把该查询发布给 MCP 搜索、发现和执行链路。
3. 管理端必须同时展示配置启用状态和数据可用状态；至少区分 `READY`、`DATASET_NOT_COLLECTED` 与 `FINANCIAL_STORAGE_UNAVAILABLE`。
4. 专用金融存储属于数据身份和隔离边界。生产与开发部署缺少专用写存储时必须启动失败，禁止静默回退到 MCP 控制库或其他业务库。
5. 物理业务表由首个有效采集批次按照返回协议创建。不得仅为让查询“看起来可用”而预建没有采集回执的空壳表。
6. 数据集就绪仅证明至少成功入库一次；时效性必须继续由 `lastCollectedAt`、观察日期及数据源更新频率单独治理。
7. 采集失败不能把旧数据冒充新数据。模型分析上下文和证据对象必须携带可用状态、最后成功采集时间与新鲜度判断。

### 10.2 分析内容交付约束

1. 结构化表格、图表、数据源名称和记录数只是证据展示，不构成分析总结。
2. 每个携带 `analysisContext` 的非空结构化数据集，无论记录量大小，都必须经过 `AnalysisSummaryGovernanceBridge` 调用模型生成带位置的分块分析；不得仅把行 JSON 写进总结对象冒充总结。未声明数据分析身份的普通协议回执不得被误当成业务分析数据。
3. 分块模型输入必须同时包含该数据集的 `analysisContext` 和返回记录。`analysisContext` 至少承载已配置的 API 显示名称、工具描述、能力说明、业务分类、返回字段、字段注释及显式关系。
4. 最终综合必须输出业务可读的分析结论，说明数据身份、关键数值、差异、异常和有配置依据的数据集关系；表格只能作为结论的证据附件。
5. SQL、API、多结果集和资产分析在进入分块总结时不得丢失上游 `analysisContext`。
6. 若最终候选只有标题、数据源和表格，治理层必须补入已记录的模型分块分析，并记录 `governedNarrativeAnalysisAppended` 观测标记。

## 11. 禁止行为

- 把字段注释直接当作展示表头。
- 根据字段缩写、API 名或资产名猜测业务含义。
- 将模型总结写回原始证据对象。
- 将候选答案、Reviewer 意见或会话摘要当作原始事实。
- 从 MCP 载荷读取租户身份并覆盖 Runtime 上下文。
- 把其他租户、运行或会话的结果加入当前总结血缘。
- 未重新验证就把上一轮指标、行情、余额或系统状态描述为当前值。
- 在消息账本中复制完整大结果。
- 针对证券、某个 API 或具体字段建立 Runtime 业务白名单。

## 12. 发布门禁

相关变更至少验证：

1. MCP 返回载荷伪造租户字段不能改变证据隔离域。
2. 跨租户和跨运行总结血缘被拒绝。
3. 跨租户和跨会话历史账本不产生投影。
4. 历史投影包含 `currentFact=false` 和 `revalidationRequired=true`。
5. 大结果账本不复制原始 payload。
6. API、数据库、资产和未来数据结构共享同一总结协议。
7. 分块覆盖数与返回记录数一致。
8. 模型失败时结构化证据仍可交付。
9. 最终回答对象、运行观测和会话账本可关联追踪。
10. `ProductionReleaseCoverageE2E` 通过。

## 13. 兼容与迁移

- 对外回答接口仍返回最终 `content`，保持现有客户端兼容。
- 新产生的运行和 assistant 消息自动携带新治理对象。
- 已持久化的旧回答、旧消息和旧会话不会自动补写总结对象或会话证据账本。
- 旧消息没有证据账本时只能作为普通历史文本使用，不能被提升为可复用证据。
- 历史证据如需迁移，必须从原始 Runtime Evidence Store 和审计记录重建，禁止从自然语言回答猜测恢复。

## 14. 主要实现位置

| 职责 | 实现 |
|---|---|
| 数据身份协议 | `DataAnalysisContextProtocol` |
| MCP 证据桥 | `McpEvidenceGovernanceBridge` |
| MCP 证据结果 | `McpEvidenceResult` |
| 租户运行隔离 | `GovernanceIsolationScope` |
| 总结桥 | `AnalysisSummaryGovernanceBridge` |
| 总结结果 | `AnalysisSummaryResult` |
| 最终答案总结收口 | `AgentAnswerFinalizer` |
| 会话证据账本 | `ConversationEvidenceLedgerBridge` |
| 会话账本持久化和投影 | `ConversationMemoryService` |
| 会话编排接入 | `InteractionOrchestrationService` |
| Agent 历史证据接入 | `AgentChatModeHandler` |
| 普通 LLM 历史证据接入 | `LlmChatModeHandler` |

本规范与事实落地、证据增强、答案质量评审和 MCP 执行治理规范共同生效；发生冲突时，租户授权、事实边界和 Runtime 确定性校验优先。
