# 03 · web_search 多源检索 —— 架构优化建议

> 本文给出方案 A / B 的技术对比、明确推荐、MCP 接口调整建议、兼容性评估、分阶段实施路径与验收标准。
> 本轮不修改任何代码；以下均为建议。
> 前置约束：文档 02 §3 列出的假设（尤其 **H6：本地金融富化服务是否装配**）必须先验证，否则任何架构改造都可能作用在错误的根因上。

---

## 一、设计约束复核

优化建议必须同时满足审查任务给出的 7 条约束。下表用于后续方案自检：

| # | 约束 | 本文如何遵守 |
| --- | --- | --- |
| 1 | 不得用 Runtime 硬编码检索顺序取代模型选择 | 建议**移除**固定 `retrievalOrder`，改为模型声明来源 |
| 2 | 不得强制每个问题依次执行三类检索 | 建议默认**不预检索**，由模型按需调用 |
| 3 | 不得引入关键词匹配业务意图的固定路由 | 不新增任何关键词→来源映射；现有 `AgentPlanCandidateScorer` 的关键词仅用于计划打分，不扩散到来源路由 |
| 4 | 不得为提高本地使用率而增加无效调用 | 用"覆盖情况元信息"替代"强制预检索" |
| 5 | 模型自主决定数据源、深度与何时停止 | 来源选择、深度、终止条件全部交还模型 |
| 6 | Runtime 提供元信息/契约/结果/证据状态，不做业务裁决 | 建议下线最终总结阶段的 Runtime 自发联网轮（问题 3） |
| 7 | 不得以复杂 Driver/Worker 流程为默认解 | 保留现有 `AbstractStagedExecutionWorkflow` 与 MCP 注册体系，不引入新编排层 |

---

## 二、方案 A：保留统一 `web_search`

### A.1 方案内容

保留单一工具入口，通过以下方式补足语义：

1. 新增 `sources` 参数（`string[]`，枚举 `local_news` / `financial_data` / `external_web`），缺省为"由模型不填则 Runtime 按当前顺序执行"。
2. 结果层已有的来源标识（`retrievalSource` / `resultType` / `documentKind`）升级为**统一证据封装协议**，每条结果强制带 `source`、`asOf`、`evidenceId`、`coverage`。
3. 在 `capabilityManifest` 中新增**数据源覆盖元信息**（各来源是否可用、索引时间窗、最新观测日期）。
4. 修正 Planner Prompt 与工具描述的矛盾（问题 2）。

### A.2 优点

| 维度 | 评价 |
| --- | --- |
| 兼容性 | **最好**。工具名、注册体系、Semantic Policy、`agent_tool_intent_binding`、`GovernedExternalResearchOperator` 的工具名白名单（`web_search` / `mcp_*_web_search`）、`ToolDirectModeHandler` 的 `web_search` 特判、`ToolLogSummarizer` 的专用分支全部无需改动 |
| 工具数量 | 不增加，无注意力稀释风险 |
| 实施成本 | 最低，主要是参数扩展 + 文案修正 |
| 多源并行 | 单次调用内可由 Runtime 并行三路，延迟优于三次串行工具调用 |
| 跨源一致性 | 单次调用天然共享同一 query 上下文与 `discovery_id`，便于交叉校验 |

### A.3 缺点

| 维度 | 评价 |
| --- | --- |
| 参数表复杂度 | 当前已有 16 个参数（`RemoteNewsMcpToolProvider.java:43-63`），其中 9 个只对金融来源有意义（`dataset` / `filters` / `startDate` / `endDate` / `historyMode` / `limit` / `financial_*`），3 个只对资讯/外部有意义（`queryTerms` / `keywords` / `intent`）。再加 `sources` 会进一步恶化 |
| 查询契约冲突 | 金融侧是"数据集 + 已注册字段过滤 + 时间范围"的**结构化读取契约**；资讯侧是"自由文本 + 关键词扇出"的**全文检索契约**。两者挤在同一参数表里，模型必须自行判断哪些参数在本次调用中有效——这是实际的理解成本来源，比工具数量更昂贵 |
| 语义模糊 | 工具名 `web_search` 字面含义与"读取本地金融数据集"相去甚远。即使描述写清楚，命名本身持续产生误导（这是问题 2 的深层原因） |
| 错误定位 | 一次调用混合三路，任一路失败只能通过 `warnings[]` 字符串表达，模型难以精确重试某一路 |
| 粒度治理 | 风险等级、超时、限流、审计策略只能按工具配置。金融读取（本地、只读、低风险）与外部联网（出网、有成本、有合规面）被迫共享同一套治理参数（`McpToolDefinition` 的 `Duration.ofSeconds(30)`、`riskLevel`、`operationType`） |

---

## 三、方案 B：拆分为三类模型可调用工具

### B.1 方案内容

```
local_news_search       ← 复用 chatchat-runtime-news 的 NewsDocumentStore / NewsLocalQueryPlanner / NewsRelevanceRanker
financial_data_search   ← 复用已存在的 FinancialDataMcpToolProvider（当前未装配）
external_web_search     ← 复用已存在的 ExternalWebSearchToolExecutor（当前内部专用）
```

### B.2 重复建设核查结果（关键）

**三类能力的实现全部已存在，拆分不需要新建检索基础设施，只需要改变"暴露面"。**

| 目标工具 | 已有实现 | 当前状态 | 需要新增的内容 |
| --- | --- | --- | --- |
| `local_news_search` | `NewsSearchToolExecutor`（`news_search`，`NewsToolNames.java:5`）已在 news runtime 内注册（`NewsMcpToolProvider.java:26`） | 仅进程内可用，未经 MCP 发布 | 在 `chatchat-mcp-server` 增加一个 `McpToolProvider`，经 `NewsRuntimeClient.invoke("news_search", ...)` 透传 |
| `financial_data_search` | `FinancialDataMcpToolProvider` 已有完整定义：名称 `:29`、描述 `:39-43`、10 参数 `:46-57`、执行 `:79-142`、字段省略 `:170-202` | 类存在但**刻意无 `@Component`**（`:24-27`） | 加上装配注解；移除"legacy compatibility"定位 |
| `external_web_search` | `ExternalWebSearchToolExecutor` 已有完整执行逻辑（`:47-125`）、查询改写（`ExternalSearchQueryResolver`）、缓存、降级 | 内部专用，显式不注册（`:21-27`） | 在 mcp-server 侧补一个工具定义并透传；保留"原始问题不出境"约束 |

> 结论：**方案 B 的实现成本被严重高估**。三个执行器齐备，拆分本质上是把两个被刻意隐藏的能力重新公开，并把 `news_search` 从进程内提升到 MCP 层。

### B.3 需特别检查项的逐条答复

| 检查项 | 答复 |
| --- | --- |
| 是否已有相似能力，避免重复建设 | **已全部存在**，见 B.2。不存在重复建设风险 |
| 是否能复用现有 MCP 注册体系 | **能**。`McpToolRegistry`（`:26-46`）按 `McpToolProvider` 集合自动发现；新增 Provider 即自动注册、发布、带 governance 元数据。注意 `validatePublicationName` 的语义名冲突检测：`external_web_search` 与 `web_search` 归一化后不冲突（`McpToolNamePolicy.workflowSemanticKey` 只剥前缀，不做包含匹配） |
| 是否保持与现有调用接口兼容 | **可以**，条件是**保留 `web_search` 作为组合入口**（见推荐方案）。否则将破坏：`GovernedExternalResearchOperator.java:36-40` 的工具名白名单、`ToolDirectModeHandler.java:129-132` 的特判、`ToolLogSummarizer.java` 的 `web_search` 专用摘要分支、`agent_tool_intent_binding` 的 `webSearch` 绑定、`AgentToolNameResolver` 的解析 |
| 是否需要调整 Semantic Policy 的能力分类 | **需要**。当前只有 `WEB_SEARCH` / `WEB_DISCOVERY` 两个角色、且都 `CONTAINS "web_search"`（文档 02 问题 6）。拆分后必须新增来源级角色，否则 `external_web_search` 会因不含 `web_search` 子串而**丢失 `WEB_SEARCH` 角色**（`RuntimeSemanticPolicy.java:288-299` 的 `CONTAINS` 匹配），进而影响所有依赖 `hasRole()` 的治理分支 |
| 本地金融检索是否应与资讯全文检索采用不同的查询契约 | **是，且这是拆分的最强理由**。金融侧需要 `dataset` + 已注册字段精确过滤 + 日期范围 + `historyMode` + 行上限；资讯侧需要自由文本 + 关键词扇出 + 相关性门槛。两套契约语义不交叠，合并只会产生"大部分参数在本次调用中无意义"的噪声 |
| 三种工具是否应有统一的证据封装协议 | **必须统一**。当前证据模型已区分 `StructuredDataEvidence`（含 `dataset` / `rows` / `asOf`）与 `ExternalResearchEvidence`（含 `url` / `publisher` / `publishedAt`），但**缺少本地资讯专用类型**，且 `GovernedExternalResearchOperator` 把一切都塑造成 `ExternalResearchEvidence`（文档 02 问题 4）。建议统一为带 `source` / `asOf` / `retrievalSource` / `coverage` 的信封，三工具共用 |
| 是否支持并行检索和异步执行 | **当前不支持**。`WebSearchExecutionWorkflow.executeSearch` 是严格串行（`:185-201`），且注释明确要求金融先完成。拆分后模型发起的多个工具调用能否并行，取决于 `AgentOrchestrationEngine` 的计划执行器是否支持同层并行步骤——**此点需单独确认**，不能假定 |
| 多工具是否造成工具数量膨胀和注意力稀释 | 净增 2 个工具（若保留 `web_search` 组合入口则为 +3/-0）。`AgentPlannerPromptBuilder` 无工具数量硬上限，受 32000 字符预算约束（`:44-51`），紧凑模式下每工具描述上限 800 字符。风险**可控但真实**：三个名字高度相似的检索工具会增加误选概率，必须靠描述正交化缓解 |

### B.4 优缺点

**优点**
- 来源语义在**工具名层面**即明确，不依赖描述文本被正确阅读。
- 各工具参数表精简、契约自洽（金融 10 参数、资讯 ~5 参数、外部 ~4 参数），模型理解成本显著下降。
- 可按工具独立配置超时、风险等级、限流、审计与权限（外部出网 vs 本地只读）。
- 失败可定位、可单路重试。
- 模型可在"只需要行情"的场景下只调一个工具，消除无效调用（契合约束 4）。

**缺点**
- 跨源一致性需要模型自己维护（同一 `discovery_id` 需跨调用透传）。
- 三次串行调用延迟可能高于单次内部并行，除非编排层支持并行步骤。
- 兼容面改动点多（见 B.3 第三行）。
- Semantic Policy、工具名白名单、日志摘要分支需同步扩展。

---

## 四、技术对比与明确推荐

### 4.1 对比矩阵

| 维度 | 方案 A（统一入口） | 方案 B（三工具拆分） |
| --- | --- | --- |
| 来源语义清晰度 | 中（依赖描述文本） | **高**（工具名即语义） |
| 模型自主选择能力 | 低→中（靠 `sources` 参数） | **高** |
| 参数表复杂度 | **高且继续恶化**（16→17+） | 低（各 4–10） |
| 查询契约自洽性 | 差（两套契约混用） | **好** |
| 工具数量 / 注意力 | **优**（1 个） | 中（3–4 个） |
| 多源并行 | **优**（内部并行） | 依赖编排层支持 |
| 跨源交叉校验 | **优**（同一上下文） | 需模型维护 |
| 独立治理（超时/风险/限流） | 差 | **优** |
| 失败定位与单路重试 | 差 | **优** |
| 实施成本 | **低** | 中（实现已存在，主要是暴露面与兼容面） |
| 兼容性风险 | **低** | 中 |
| 对约束 1/2/5 的符合度 | 部分符合 | **符合** |

### 4.2 推荐：**方案 B 的渐进式变体 —— "三工具 + 保留 `web_search` 作为组合入口"**

**推荐理由**

1. **问题的本质是契约冲突，不是工具数量。** 金融的结构化读取契约与资讯的全文检索契约不可能在一个参数表里自洽；方案 A 无法解决这一点，只能持续用文档描述打补丁（现状已证明失败：Prompt 与描述已经互相矛盾）。
2. **三套实现已全部存在**，拆分不是新建而是"公开已被刻意隐藏的能力"，成本远低于直觉。
3. **符合核心设计原则**：来源选择权回到模型（原则 5），Runtime 退回到提供能力契约与执行（原则 6），且不需要重建 Driver/Worker（原则 7）。
4. **保留 `web_search` 组合入口**可同时拿到方案 A 的兼容性与并行优势：
   - 所有既有依赖（工具名白名单、直调模式、日志摘要分支、意图绑定）零改动；
   - 模型在"综合研究"场景仍可一次调用拿到多源交叉结果；
   - 在"只要行情"或"只要最新突发"场景下改调专用工具，消除无效调用。
5. **但必须同时修正 Runtime 侧偏置**（文档 02 问题 3、4、5）。**单纯拆分工具无法解决这三个问题**——它们发生在模型完成工具调用之后的证据与总结环节。这是本推荐最重要的限定条件。

> **明确声明**：拆分**不是**当前现象的充分条件，也不是必要条件。若验证发现主因是 H6（金融服务未装配）或问题 3（最终总结强制联网轮），则这两项的修复优先级**高于**拆分。

---

## 五、MCP 工具接口调整建议

### 5.1 工具集（建议）

| 工具 | 定位 | 建议参数 | 建议治理 |
| --- | --- | --- | --- |
| `local_news_search` | 本地资讯库全文检索 | `query`、`queryTerms[≤8]`、`num_results`、`startDate`/`endDate`、`sources[]`（资讯源筛选） | 只读 / 低风险 / 10s |
| `financial_data_search` | 受控金融观测值读取 | 沿用 `FinancialDataMcpToolProvider.java:46-57` 的 10 参数，补 `operation=discover_datasets\|read_dataset` | 只读 / 低风险 / 20s |
| `external_web_search` | 外部公开信息检索 | `queryTerms[≤8]`（必填）、`intent`、`num_results`；**保留"原始问题不出境"约束**（`ExternalWebSearchToolExecutor.java:52-55`） | 出网 / 中风险 / 30s / 计费可观测 |
| `web_search`（保留） | 多源组合检索 | 现有参数 + 可选 `sources[]`；默认行为保持不变以确保兼容 | 维持现状 |

### 5.2 统一证据封装协议（建议）

三工具与 `web_search` 的每条结果统一携带：

| 字段 | 含义 | 现状 |
| --- | --- | --- |
| `evidenceId` | 稳定标识，供引用绑定与去重 | 仅金融 discovery 有 `discovery_id` |
| `source` | `LOCAL_NEWS` / `LOCAL_FINANCIAL_DATA` / `EXTERNAL_WEB` | 现有 `retrievalSource` 可直接升格 |
| `retrievalSource` | 具体通道（`news_index` / `financial_dataset_query` / `tencent_wsa` / `tencent_wsa_cache`） | **已有** |
| `asOf` | 证据的事实时间（资讯发布时间 / 观测日期 / 网页抓取时间） | 资讯有 `publishTime`；金融有 `observation_date`；外部有 `publishTime`（常缺） |
| `ingestedAt` | 进入本地库的时间 | **缺失**（这是"旧资讯被误认为最新"的关键缺口） |
| `coverage` | 本次检索的时间窗与范围 | 金融有 `queryScope`；资讯侧缺失 |
| `corroborated` / `retrievalSources[]` | 多源互证标记 | **已有**（`WebSearchToolExecutor.java:258-262`） |

### 5.3 数据源覆盖情况如何向模型暴露（替代"强制预检索"）

建议扩展 `capabilityManifest`（`WebSearchExecutionWorkflow.java:38-70` 已有骨架），在**不执行任何检索**的前提下声明：

- `local_news`：索引时间窗（当前 `retention-days: 7`）、文档总量级、最近索引时间、是否启用向量检索。
- `financial_data`：已注册数据集列表（**已有**，`:55-63`）、每个数据集的 `lastObservationDate`（**已有**）、字段清单（**已有**）、历史粒度与归档保留（**已有**，`assetResult` 的 `retentionPolicy`）。
- `external_web`：供应商是否可用、是否命中缓存策略、调用成本等级。

这满足约束 4：模型在**调用前**就知道覆盖情况，可据此决定是否调用，而不是靠"先全查一遍"。

### 5.4 Semantic Policy 调整（必须）

新增来源级角色，并保留既有角色以兼容：

```
LOCAL_NEWS_SEARCH   ← EXACT  local_news_search
FINANCIAL_DATA      ← EXACT  financial_data_search
EXTERNAL_WEB        ← EXACT  external_web_search
WEB_SEARCH          ← 追加   EXACT external_web_search（避免因不含 "web_search" 子串而丢角色）
WEB_DISCOVERY       ← 追加   EXACT local_news_search / financial_data_search（按治理需要）
```

同时评估 `agent_tool_intent_binding` 是否需要新增 `localNewsSearch` / `financialData` 意图行。

### 5.5 必须同步修正的 Runtime 偏置（优先级高于拆分）

| 项 | 位置 | 建议 |
| --- | --- | --- |
| 最终总结强制外部 WSA 证据 | `FinalSummaryWebSearchEnhancer.java:110-112` | 改为"证据充分性由模型判定"，本地证据同等计分；或在金融/资讯证据充足时直接跳过该轮 |
| 该轮不读金融数据 | `InternalFinancialDataSearchExecutor.java:59-62` | 允许模型在该轮请求金融补充 |
| 该轮丢失分析关键词 | `FinalSummaryWebSearchEnhancer.java:265-269` | 透传 `queryTerms` 而非单关键词 `query` |
| 无 URL 证据被整体丢弃 | `GovernedExternalResearchOperator.java:73-79` | 按 `source` 分流：结构化金融证据用 `dataset+asOf` 归因，不要求 URL |
| 摘要丢弃资讯条目 | `ToolLogSummarizer.java:77-155` | `web_search_fact_audit.v1` 增加 `newsFacts`（标题 / url / sourceName / publishTime），与 `financialFacts` 对称 |
| 引用名额不足 | `AgentTaskService.java:2992` | 多源研究场景下提高上限，或按 `source` 分配配额 |
| Prompt 与描述冲突 | `AgentPlannerPromptBuilder.java:236, 248-250` | 补充本地金融数据语义；删除与 `dataset` 参数冲突的 "do not invent dataset codes"；"必须经 crawler 取全文"规则限定为仅适用于 `EXTERNAL_WEB` 来源 |

---

## 六、对模型自主探索的影响

### 6.1 职责边界（明确回答"哪些由模型决定，哪些由 Runtime 执行"）

| 决策 | 应归属 | 当前归属 | 差距 |
| --- | --- | --- | --- |
| 需要哪些数据源 | **模型** | Runtime（硬编码顺序） | ❌ |
| 检索顺序 | **模型** | Runtime（`:93-99`） | ❌ |
| 查询词与改写 | **模型** | Runtime 自动扇出/改写 | ⚠️ 部分（模型可传 `queryTerms`） |
| 选哪个金融数据集 | **模型** | Runtime 自动相关性匹配 | ⚠️ 有 `dataset` 参数但 Prompt 劝阻使用 |
| 检索深度（行数/结果数/时间窗） | **模型** | 模型可设，Runtime 夹边界 | ✅ |
| 何时停止探索 | **模型** | Runtime 在最终总结阶段追加一轮 | ❌ |
| 证据是否充分 | **模型** | Runtime 判定（`localSufficient`、`containsTencentWsaEvidence`） | ❌ |
| 来源矛盾如何解释 | **模型** | —（无机制） | ⚠️ 缺失 |
| 工具能力与契约声明 | Runtime | Runtime | ✅ |
| 数据源覆盖元信息 | Runtime | 部分（仅金融目录） | ⚠️ |
| 执行、超时、取消、限流 | Runtime | Runtime | ✅ |
| 安全边界（原始问题不出境、私网 URL 过滤、敏感字段脱敏） | Runtime | Runtime | ✅ |
| 证据封装与可追溯标识 | Runtime | 部分（缺 `ingestedAt`、无统一信封） | ⚠️ |
| 事实结论与报告 | **模型** | 模型 | ✅ |

### 6.2 第三部分场景的可行性评估

以"分析九阳股份最近一个月的行情变化、资金表现与相关新闻，并判断主要驱动因素"为例：

| 理想探索步骤 | 当前是否支持 | 说明 |
| --- | --- | --- |
| 1. 检索本地金融数据取行情 | ⚠️ 部分 | `web_search` 会自动匹配数据集，但模型无法声明"我要日线收盘+成交量"；需 `operation=read_dataset`，而 Prompt 劝阻指定 dataset |
| 2. 检索本地资讯取新闻公告 | ⚠️ 部分 | 自动执行，但索引仅保留 7 天，**无法覆盖"最近一个月"**；覆盖缺口未向模型声明 |
| 3. 外部补最新信息 | ⚠️ 部分 | 由 `localSufficient` 阈值决定，不由模型决定 |
| 4. 跨源时间/代码/口径/事实一致性校验 | ❌ | 无机制。`corroborated` 仅做 URL/标题去重，不做证券代码或指标口径对齐 |
| 5. 证据不足时主动追加检索 | ⚠️ 部分 | 有补证机制（`KnowledgeAgentEvidenceSupplement`，上限 5 条）与 `SemanticGapEvidenceBridge`，但触发由 Runtime 判定 |

**针对第三部分提出的六个明确问题**

1. **数据源覆盖情况应如何向模型暴露** —— 通过 `capabilityManifest` 的静态声明（时间窗、数据集目录、`lastObservationDate`、供应商可用性），不执行检索；并在每次结果中附 `coverage`（实际检索到的时间窗与范围）。
2. **证据时间和来源应如何标识** —— 统一信封强制 `source` + `retrievalSource` + `asOf` + `ingestedAt` + `evidenceId`。
3. **如何避免把采集的旧资讯误认成最新资讯** —— 关键在于**同时暴露 `asOf`（事实时间）与 `ingestedAt`（入库时间）**，当前缺后者；并在 `coverage` 中显式写出"本次检索覆盖的时间窗"与"索引保留期"，让模型看到 7 天保留期与 30 天问题区间之间的缺口。
4. **如何避免搜索引擎摘要覆盖更完整的本地结构化数据** —— 三点：(a) 结构化证据不参与 `interleave` 的位次竞争，单独成区（现有 `financialFacts` / `records` 已有雏形）；(b) 摘要层对称保留资讯与金融事实（修正 `ToolLogSummarizer`）；(c) 引用名额按来源分配配额，而非先到先得。
5. **对来源矛盾、证据缺失和时效性差异如何表达** —— Runtime 只做**事实性标注**（同一指标在不同来源取值不同 → 标 `conflict`；请求时间窗未被覆盖 → 标 `coverageGap`；`asOf` 差异超阈值 → 标 `staleness`），**不给出裁决**，由模型在报告中解释。这正是原则 6 的落点。
6. **如何保持模型自主推理，而不是 Runtime 直接拼接分析结论** —— 删除 Runtime 单方面的证据充分性判定（`containsTencentWsaEvidence`、最终总结强制联网轮），把 `localSufficient` 从"路由开关"降级为"向模型上报的事实字段"。

---

## 七、对现有 Runtime OS 的兼容性

| 组件 | 影响 | 缓解 |
| --- | --- | --- |
| `McpToolRegistry` | 无（自动发现新 Provider） | 注意 `validatePublicationName` 冲突检测 |
| `web_search` 现有调用方 | 无（保留组合入口） | 不改默认行为 |
| `GovernedExternalResearchOperator:36-40` 工具名白名单 | **需扩展** | 增加 `external_web_search` / `local_news_search` / `financial_data_search`，并按来源放宽 URL 强制要求 |
| `ToolDirectModeHandler:129-132` 的 `web_search` 特判 | 无 | 保留 |
| `ToolLogSummarizer` 的 `web_search` 分支 | **需扩展** | 增加三工具分支 + 对称保留资讯事实 |
| Semantic Policy / `agent_tool_intent_binding` | **需扩展** | §5.4 |
| `AgentPlannerPromptBuilder` 检索段落 | **需重写** | §5.5 |
| `AgentToolNameResolver` / `preferredWebSearchTool` | **需核对** | 多个候选工具时的优选逻辑 |
| `FinalSummaryWebSearchEnhancer` | **需改造** | §5.5 |
| 数据库结构 | 仅 Semantic Policy 种子数据变更，无表结构变更 | 走标准 migration |
| news runtime HTTP 契约 | 无（`news_search` 端点已存在） | 复用 |

---

## 八、分阶段实施建议

### 阶段 0 · 事实核验（零改码，**最高优先级**）
- 执行文档 02 §6.2 的环境装配核验，确认 H6（金融富化服务是否装配）、H7（数据集覆盖与新鲜度）、H8（WSA 凭证）、H9（`mcpToolConfigs` 是否覆盖描述）。
- 执行 §6.3 的五类查询单工具级验证，采集 `webSearchBridgeRoute` / `internalFinancialDataSearch` 日志。
- **出口判据**：确认主因后再决定是否进入阶段 2。若主因是装配缺失，阶段 1 之后即可能解决问题。

### 阶段 1 · 低风险纠偏（不改工具拓扑）
1. 修正 `AgentPlannerPromptBuilder` 的检索段落（补金融语义、消除 `dataset` 冲突、crawler 规则限定为外部来源）。
2. `ToolLogSummarizer` 对称保留资讯事实（`newsFacts`）。
3. `FinalSummaryWebSearchEnhancer` 不再强制要求 WSA 证据；透传 `queryTerms`。
4. `GovernedExternalResearchOperator` 按 `source` 分流归因要求。
5. 引用名额按来源分配配额。
- **预期**：若文档 02 的问题 2/3/4/5 是主因，本阶段即可显著改善报告的本地证据占比。

### 阶段 2 · 能力暴露（拆分）
1. 为 `FinancialDataMcpToolProvider` 补装配，发布 `financial_data_search`。
2. 新增 `local_news_search` Provider（透传 news runtime 的 `news_search`）。
3. 新增 `external_web_search` Provider（透传内部执行器，保留"原始问题不出境"约束）。
4. 同步扩展 Semantic Policy 角色、工具名白名单、日志摘要分支。
5. `web_search` 保持不变作为组合入口。

### 阶段 3 · 证据协同
1. 统一证据信封（`evidenceId` / `source` / `asOf` / `ingestedAt` / `coverage`）。
2. `capabilityManifest` 增加三类来源的覆盖元信息。
3. Runtime 做事实性标注（`conflict` / `coverageGap` / `staleness`），不做裁决。
4. 新增本地资讯证据类型，或在现有信封上区分，避免一律塑形为 `ExternalResearchEvidence`。

### 阶段 4 · 自主性回归
1. 把 `localSufficient` 从路由开关降级为上报字段，外部检索完全由模型 `external_web_search` 决定。
2. 评估编排层是否支持同层并行工具调用；若支持，`web_search` 内部改并行以降低延迟。

> 阶段 1 与阶段 0 可并行准备；阶段 2 之前必须完成阶段 0 的结论。

---

## 九、回归测试与验收标准

### 9.1 必须保持绿色的既有测试

| 测试 | 保护的行为 |
| --- | --- |
| `RemoteNewsMcpToolProviderTest`（17 例） | 工具暴露面、金融先于资讯、数据集选择、日期抽取、最终总结轮语义 |
| `WebSearchToolExecutorTest`（11 例） | 本地/外部路由、关键词扇出、质量门槛、缓存、降级、取消 |
| `FinancialDataMcpToolProviderTest`（5 例） | 金融契约、字段省略、行上限 |
| `GovernedExternalResearchOperatorTest`（8 例） | URL 治理、公私网过滤、拒绝条件 |
| `NewsMcpToolProviderTest`（3 例） | 工具注册面 |
| `McpToolRegistryTest` | 注册、去重、名称冲突 |
| `ProductionWebSearchTimeoutIsolationE2E` | 超时隔离与取消链 |

> 注意：`RemoteNewsMcpToolProviderTest:359-373` `exposesOnlyUnifiedWebSearch` 与 `NewsMcpToolProviderTest:30-38` **会因阶段 2 的拆分而失效**。这两个用例是对"刻意隐藏"的断言，属于被变更的设计决策，须随方案一并更新断言（而非删除）。其余用例不得修改。

### 9.2 新增回归测试（建议）

| 编号 | 覆盖 | 断言要点 |
| --- | --- | --- |
| R1 | 工具暴露面 | 四个工具均被注册；各自 `capabilityCode` / 超时 / 风险等级正确 |
| R2 | Semantic Policy | `external_web_search` 仍命中 `WEB_SEARCH` 角色；三个来源角色各自可解析 |
| R3 | 证据信封 | 三工具结果均含 `evidenceId` / `source` / `asOf` / `ingestedAt` / `coverage` |
| R4 | 覆盖元信息 | `capabilityManifest` 在**不执行检索**的前提下返回三类来源的覆盖声明 |
| R5 | 摘要对称性 | `ToolLogSummarizer` 同时保留 `financialFacts` 与 `newsFacts` 的可归因字段 |
| R6 | 归因分流 | 无 URL 的结构化金融证据不被 `GovernedExternalResearchOperator` 丢弃 |
| R7 | 最终总结轮 | 本地证据充足时不触发 Runtime 自发联网；触发时透传 `queryTerms` |
| R8 | 安全边界 | 原始用户问题在任何路径下都不进入外部供应商请求 |
| R9 | 兼容性 | `web_search` 旧参数调用的输出结构与阶段 0 基线一致 |
| R10 | 引用配额 | 多源场景下三类来源各自至少获得一个引用位（若该来源有有效证据） |

### 9.3 验收标准

验收的目标**不是**提高本地检索调用次数，而是按任务书的六个评价维度衡量：

| 评价维度 | 验收口径 | 度量方式 |
| --- | --- | --- |
| 数据源选择是否合理 | 五类代表性查询中，模型选择的来源组合与场景匹配；"只需行情"场景不触发外部检索 | 阶段 0 基线 vs 改造后对比 |
| 内部数据是否被充分利用 | 当 `capabilityManifest` 声明某来源覆盖该问题时，报告中出现该来源的引用 | 引用的 `source` 分布 |
| 证据完整性与时效性 | 每条引用可追溯到 `evidenceId` + `asOf`；覆盖缺口被显式说明而非沉默 | 人工抽检 + R3/R4 |
| 是否存在重复检索 | 同一 query 上下文内不出现语义等价的重复来源调用 | `discovery_id` 链路审计 |
| 模型自主探索能力 | Runtime 不再单方面判定证据充分性；不再强制追加联网轮 | R7 |
| 报告质量 | 能对来源矛盾、时效差异给出解释，而非并列罗列 | 盲评（可复用 `docs/answer-quality-blind-evaluation.md` 的既有方法） |

**硬性门槛**
1. 原始用户问题不出境这一安全约束在所有路径下保持（R8）。
2. `web_search` 旧调用方零破坏（R9）。
3. 既有回归测试除 §9.1 注明的两例外全部保持绿色。
4. 不引入任何"关键词 → 数据源"的固定路由（人工代码评审确认）。
5. 不引入 Runtime 对业务结论的裁决逻辑（人工代码评审确认）。

---

## 十、对五个核心问题的最终回答

1. **当前为什么经常直接调用外部搜索？**
   并非模型选择。模型在 `web_search` 上结构性地无法跳过本地（`WebSearchExecutionWorkflow.java:183-201`）。外部结果在产出中占优的机械原因是 Runtime 侧偏置：最终总结阶段强制要求外部 WSA 证据且不读金融数据（`FinalSummaryWebSearchEnhancer.java:110-112`、`InternalFinancialDataSearchExecutor.java:59-62`）、本地资讯 7 天保留期叠加质量门槛导致频繁判定本地不足（`WebSearchToolExecutor.java:116-125`）、无 URL 的本地证据被治理算子丢弃（`GovernedExternalResearchOperator.java:73-79`）、摘要层不保留资讯的可归因字段（`ToolLogSummarizer.java:77-155`）。**此外必须优先排除本地金融富化服务根本未装配的可能（假设 H6）。**

2. **属于工具设计、能力暴露、路由机制还是模型决策问题？**
   **工具设计 + 能力暴露 + 路由机制三者叠加。不是模型决策问题** —— 当前架构下模型几乎没有来源决策空间。

3. **是否需要拆分 `web_search`？**
   **建议拆分，但保留 `web_search` 作为组合入口；且拆分不是当前现象的主要解药。** 拆分解决的是"金融结构化读取契约与资讯全文检索契约不可能在一个参数表里自洽"这一长期问题，三套实现均已存在，成本可控。但文档 02 的问题 3/4/5 发生在工具调用之后的证据与总结环节，拆分无法触及，必须在阶段 1 单独修复，且优先级更高。

4. **如何保持模型自主探索，同时提高本地数据资产利用率？**
   用**覆盖情况元信息**替代**强制预检索**：Runtime 在不执行检索的前提下声明各来源的时间窗、数据集目录、最新观测日期与可用性；模型据此自行决定调用哪些来源、调多深、何时停止。同时 Runtime 退出证据充分性裁决，只做 `conflict` / `coverageGap` / `staleness` 的事实性标注。本地利用率的提升来自"模型知道本地有什么"，而不是"Runtime 强制先查本地"。

5. **哪些由模型决定，哪些由 Runtime 执行？**
   见 §6.1 职责边界表。简述：
   - **模型**：需要哪些来源、检索顺序、查询词、数据集选择、检索深度、何时停止、证据是否充分、来源矛盾如何解释、最终结论与报告。
   - **Runtime**：工具能力与契约声明、数据源覆盖元信息、执行与并发、超时/取消/限流、安全边界（原始问题不出境、私网过滤、敏感脱敏）、证据封装与可追溯标识、事实性冲突标注。
