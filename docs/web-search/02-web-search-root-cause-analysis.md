# 02 · web_search 多源检索 —— 根因分析

> 纪律声明：本文严格区分 **已确认事实（代码可验证）**、**未验证假设（需运行数据）** 与 **无法验证项**。
> 仓库内**不存在**任何运行日志、执行轨迹或真实调用记录（见 §6），因此任何关于"模型实际如何选择来源"的陈述均只能是假设。
> 本文不编造任何调用记录或测试结果。

---

## 一、对问题陈述前提的核验

原始问题陈述：**"模型在执行市场分析、证券研究及报告生成任务时，经常绕过已有的本地资讯和本地金融数据，直接发起外部联网检索。"**

### 核验结论：该前提在 `web_search` 路径上**结构性地不成立**

| 证据 | 位置 |
| --- | --- |
| 模型只有一个检索工具 `web_search`，没有任何"只联网"的工具可调 | `RemoteNewsMcpToolProvider.java:28-64`；测试 `RemoteNewsMcpToolProviderTest.java:359-373` `exposesOnlyUnifiedWebSearch` |
| `external_web_search` 被显式标注为内部专用、不注册 | `ExternalWebSearchToolExecutor.java:21-27`；测试 `NewsMcpToolProviderTest.java:30-38` |
| 每次 `web_search` 都先跑本地金融检索，再跑本地资讯 | `WebSearchExecutionWorkflow.java:183-201`，注释 :183-184 明写"must finish before" |
| 外部检索只在本地证据不足 / 显式请求 / 强制开关时才触发 | `WebSearchToolExecutor.java:116-125` |
| 测试锁定"本地证据充足时跳过付费外部 API" | `WebSearchToolExecutorTest.java:87-111`、`:115-135` |
| 测试锁定"金融检索严格早于资讯检索" | `RemoteNewsMcpToolProviderTest.java:211-228` `localFinancialIndexRunsBeforeNewsRuntimeEvenWhenNewsIsCancelled` |

**也就是说：模型不可能"选择绕过本地"，因为它根本没有可以绕过本地的选项。**

因此问题需要重新表述为：

> **为什么最终产出看起来像是"只用了外部联网结果"？**

下面按根因分类给出答案。

---

## 二、已确认问题（代码可验证）

### 问题 1（高）：能力语义被单工具吞并，模型无法表达来源意图

**分类**：能力暴露 / 工具设计
**对应问题 A、B**

- 模型侧只有 `web_search` 一个名字。三类来源的**选择权完全不在模型**：顺序写死在 `WebSearchExecutionWorkflow.java:93-99`：
  ```java
  List.of("ANALYZE_QUERY", "SEARCH_GOVERNED_FINANCIAL_DATA", "SEARCH_LOCAL_NEWS",
          "SUPPLEMENT_EXTERNAL_WEB", "MERGE_AND_RANK", "VERIFY_EVIDENCE")
  ```
- 并在输出中自述该顺序（`:228-233` `retrievalOrder`、`externalSearchRole="supplementary_fallback"`）。
- 模型能够影响来源的手段只有 3 个弱信号参数：`external_search_required`、`financial_data_required`、`dataset`，且没有任何"只查本地资讯"或"只查金融数据"的表达方式。

**影响**：这是对核心设计原则第 1、5 条的直接违反——Runtime 通过硬编码检索顺序取代了模型选择。模型即便判断"这个问题只需要本地金融数据"，也必须付出资讯检索与（在本地不足时）外部检索的延迟与成本。

**影响程度**：高（架构性）。

---

### 问题 2（高）：Planner Prompt 与工具描述相互矛盾，且遗漏金融数据源

**分类**：能力发现机制
**对应问题 A、D**

两段文本同时进入模型上下文：

| 来源 | 内容 | 位置 |
| --- | --- | --- |
| 工具描述（MCP） | "Retrieve news/web content **AND registered financial observations** ... `operation=read_dataset` with `dataset`, `filters`, `startDate/endDate` reads stored observations directly ... **You choose the dataset**" | `RemoteNewsMcpToolProvider.java:29-42` |
| Planner Prompt | "`web_search` retrieves **standardized local news** and, when the internal Tencent WSA enhancer is configured, can supplement current hotspots ... from the public web." | `AgentPlannerPromptBuilder.java:248-250` |
| Planner Prompt | "Internal source routing is owned by the tool implementation; **do not invent hidden tools, source identifiers, or dataset codes**." | `AgentPlannerPromptBuilder.java:236` |

三处冲突：
1. Prompt 把 `web_search` 定义为"本地资讯 + 外部补充"，**完全没有提及本地金融数据**。模型因此不会预期该工具能返回行情/财务观测值。
2. Prompt 的 "do not ... invent ... dataset codes" 与工具 Schema 的 `dataset` 参数、`operation=read_dataset`、能力清单的 `selectionAuthority: "MODEL"`（`WebSearchExecutionWorkflow.java:44`）直接冲突。模型被同时告知"你来选数据集"和"不要提数据集代码"。
3. Prompt 的 "Web evidence workflow" 要求发现类工具的 snippet 不得当作最终证据，必须经 crawler 取全文（`AgentPlannerPromptBuilder.java:238-247`）。该规则是按"外部网页"假设写的，却**无差别地套用在本地资讯与本地金融行上**——本地结构化金融观测值没有可爬取的 URL。

**影响**：模型在规划阶段对"本地金融数据可用"这件事缺乏认知；在执行阶段又被提示不要指定 dataset，从而几乎不会使用 `operation=discover_datasets` / `read_dataset` 这条深度路径，只会落到默认 `search`。默认 `search` 的金融读取是"自动相关性匹配 + 有界行数"（能力清单 `:46-49` 自述 `"may not cover all requested metrics"`），容易覆盖不全。

**影响程度**：高。

---

### 问题 3（高）：最终总结阶段由 Runtime 发起"必须含外部结果"的联网轮

**分类**：路由机制 / Runtime 越权
**对应问题 C**

`FinalSummaryWebSearchEnhancer`（生产默认开启，`packaging/config/application-prod.yml:180`）：

1. `:265-269` 下发参数**只有** `query`（单个关键词）与 `num_results`：
   ```java
   return execute(toolName, Map.of(
       "query", keyword,
       "num_results", properties.finalSummaryWebSearchResultLimit()), metadata);
   ```
   —— 未传 `queryTerms`。而 `ExternalWebSearchToolExecutor.java:52-55` 要求必须有分析后的关键词才会外呼；此处的单个关键词被当作 `query` 传入，外部查询改写走 `ExternalSearchQueryResolver`（`:19-32`）的降级分支。
2. `:110-112` 判定 `containsTencentWsaEvidence(output.getData())`，**只要结果里没有腾讯 WSA 条目就记为失败**（`failures.add(keyword + ": external web provider unavailable")`）。即：**本地资讯与本地金融证据在这一轮里不计分**。
3. `InternalFinancialDataSearchExecutor.java:59-62`：当 `internalPurpose == final_summary_web_enhancement` 且未显式要求金融数据时，不注入 `financial_data_required`，该轮**完全不读本地金融行**。测试锁定：`RemoteNewsMcpToolProviderTest.java:141-164`。

**影响**：报告生成的最后一跳，系统性地偏向外部网页证据。这正是"报告里看起来只有联网结果"的最直接机械原因，且**与模型的来源选择无关**——是 Runtime 自己发起的。它也触碰了设计原则第 6 条（Runtime 不应越权做业务裁决）。

**影响程度**：高。

---

### 问题 4（中高）：`GovernedExternalResearchOperator` 会把无 URL 的本地证据整体丢弃

**分类**：证据治理
**对应问题 C（本地证据进入上下文后被截断或遗失）**

`chatchat-api/.../GovernedExternalResearchOperator.java`：
- `:36-40` 接受 `web_search` / `mcp_*_web_search` 的输出；
- `:73-79` + `:102-108` 要求每条结果必须有 http/https 公网 URL、无端口、非内网主机；
- `:62` 要求结果数必须落在 1–5；
- 不满足则整条丢弃，`GovernedExternalResearchOperatorTest.java:74-79` 断言"无可归属公网源时整体拒绝"。

本地金融观测行（`WebSearchExecutionWorkflow.java:299-308` `financialFactResult`）**没有 `url` 字段**；本地资讯文档若 `sourceUrl` 为空也会被 `localDocumentKey` 退化为 title-key（`WebSearchToolExecutor.java:218-228`）。

**影响**：凡经由该算子的路径，本地结构化证据与无 URL 的本地资讯会被静默过滤，产出的证据类型名也写死为 `ExternalResearchEvidence`（`:84-90`）。

**影响程度**：中高（取决于该算子在实际链路中的覆盖面，见 §3 假设 H4）。

---

### 问题 5（中）：`ToolLogSummarizer` 的 `web_search` 专用分支丢弃资讯条目

**分类**：证据可见性
**对应问题 C（模型使用了本地证据，但最终报告未体现来源）**

`chatchat-common/.../ToolLogSummarizer.java:77-155`，`web_search` 专用摘要 `web_search_fact_audit.v1` 只保留：

```
query, provider, count, newsCount, financialDatasetCount, financialObservationCount, financialFacts
```

金融事实行被 `redactComplete` **完整保留**，而**本地资讯条目只剩一个计数 `newsCount`，标题/URL/发布时间/来源名全部不进入摘要**。

**影响**：在任何消费该摘要的下游环节（日志审计、二次推理上下文、回放），本地资讯的可归因信息丢失，无法形成引用。这会直接造成"本地资讯被检索到了，但报告不引用它"。

**影响程度**：中。

---

### 问题 6（中）：Semantic Policy 没有来源级能力分类

**分类**：能力发现机制
**对应问题 D**

种子策略（`database/migration/*/V20261009_01__agent_runtime_semantic_policy.sql`）中与检索相关的角色只有 `WEB_SEARCH` 与 `WEB_DISCOVERY`，二者均以 `CONTAINS "web_search"` 命中同一个工具。不存在 `LOCAL_NEWS` / `FINANCIAL_DATA` / `EXTERNAL_WEB` 角色。

`agent_tool_intent_binding` 种子行把 `webSearch` 意图绑死到 `web_search`：

```sql
('webSearch', 'web_search', 'web_search', '_web_search', true)
```

**影响**：策略层无法表达"这是本地资产"与"这是外部公开信息"的区别；任何按角色做的过滤、排序、治理都把三类来源当成一类。若未来拆分工具，此处必须同步扩展角色（见文档 03）。

**影响程度**：中。

---

### 问题 7（中）：本地资讯的召回门槛可能在正常业务场景下判定为"不足"

**分类**：路由机制
**对应问题 C（模型调用了本地能力，但结果质量不足而转向联网）**

- 阈值：`minimum-local-results: 3`（`chatchat-runtime-news/src/main/resources/application.yml:79`，默认同 `NewsRuntimeProperties.java:47`）。
- 质量过滤发生在计数之前：`NewsRelevanceRanker.java:71-83` 要求 `matchedTerms > 0`、`coverage >= 0.08`、`score >= bestScore * 0.20`。
- 因此"检索到 10 条但只有 2 条过了质量门槛"将被判为 `localSufficient=false`，触发外部补充（`WebSearchToolExecutor.java:122-125`）。
- 测试明确承认该行为：`WebSearchToolExecutorTest.java:139-163` `lowQualityLocalCandidatesDoNotSuppressExternalSupplement`。

- 索引保留期只有 7 天：`application.yml` `open-search.retention-days: 7`。对"最近一个月"类问题，**本地资讯索引在结构上无法覆盖全部时间窗**。

**影响**：阈值本身设计合理（省外部调用成本），但**这个判定结果对模型是"事后告知"**（通过 `localEvidenceSufficient`、`qualifiedLocalNewsCount` 等字段），模型无法在调用前知道本地覆盖情况，也无法据此调整策略。这是"数据源覆盖信息未向模型暴露"的具体体现。

**影响程度**：中。

---

### 问题 8（低中）：证据与引用的硬性裁剪

**分类**：证据治理

| 限制 | 值 | 位置 |
| --- | --- | --- |
| 引用前提条数 | 6 | `AgentTaskService.java:2992` |
| 单条引用字符 | 900 | `AgentTaskService.java:88, 2999-3004` |
| 单次补证证据数 | 5 | `KnowledgeAgentEvidenceSupplement.java:78` |
| 治理算子结果数 | 固定 5 | `GovernedExternalResearchOperator.java:55, 62` |
| 工具结果摘要 | 2000 字符 / 列表 8 项 / 字符串 320 | `ToolLogSummarizer.java:17-21` |
| 最终联网增强关键词 | 2（生产） | `application-prod.yml:181` |

**影响**：多源研究天然需要更多证据位；6 条引用上限会迫使跨来源证据相互挤占。当本地金融行与本地资讯同时存在时，很可能只剩下少数几条进入最终引用。

**影响程度**：低中（需实测确认，见假设 H5）。

---

## 三、未验证假设（需运行数据才能确认）

以下**均未经验证**，列出以便设计最小验证方案。

| 编号 | 假设 | 为什么无法用代码确认 | 影响面 |
| --- | --- | --- | --- |
| H1 | 模型在规划阶段因 Prompt 未提金融数据，而不会主动使用 `operation=discover_datasets` / `read_dataset` | 取决于模型实际生成的计划，仓库无调用记录 | 高 |
| H2 | 用户观察到的"直接联网"主要来自 `FinalSummaryWebSearchEnhancer` 这一轮，而非模型的 `web_search` 调用 | 需要带 `stage=final_summary_web_enhancement` 的运行日志比对 | 高 |
| H3 | 生产环境中 `localSufficient` 在证券研究类问题上经常为 `false`（本地资讯 7 天保留期 + 质量门槛） | 需要 `webSearchBridgeRoute` 日志统计 | 高 |
| H4 | `GovernedExternalResearchOperator` 在实际 Agent 链路中覆盖哪些调用路径（是否所有 `web_search` 结果都经过它） | 需要运行期装配关系与调用栈 | 中高 |
| H5 | 6 条引用上限实际挤掉了本地证据，而不是本地证据根本没产生 | 需要带 citations 的执行产物 | 中 |
| H6 | 生产环境 `FinancialEnrichmentService` 是否真的装配可用（它是 `Optional` 注入，`WebSearchExecutionWorkflow.java:29-34`）。若缺失，`skippedReason="capability_unavailable"`，金融数据**完全不会被检索** | 需要生产启动日志 / `capabilityManifest` 实际返回 | **高** |
| H7 | 本地金融数据集目录实际收录了哪些资产、`last_observation_date` 是否足够新 | 需要生产数据库 | 高 |
| H8 | 外部搜索（腾讯 WSA）凭证在生产是否配置（`secret-id`/`secret-key` 在 yml 中刻意留空） | 需要部署环境变量 | 中 |

> **特别提示 H6**：若 `FinancialEnrichmentService` 未装配，模型将 100% 拿不到本地金融数据，而报告会完全由资讯 + 外部网页构成——这与用户描述的现象完全吻合，且与"模型决策"毫无关系。**这是优先级最高的待验证项。**

---

## 四、按问题 A–D 的归档

### 问题 A：能力暴露

**已确认**：存在一个名为 `web_search` 的工具，内部混合三个来源。
- 工具描述（`RemoteNewsMcpToolProvider.java:29-42`）**确实**说明了金融数据与本地优先，描述质量尚可；
- 但 Planner Prompt（`AgentPlannerPromptBuilder.java:248-250`）**覆盖性地**把它重述为"本地资讯 + 外部补充"，**抹掉了金融数据语义**；
- 返回结构确实带来源标识（`retrievalSource` / `resultType` / `documentKind` / `retrievalOrder` / `externalSearchRole`），来源区分在**结果层是清晰的**，在**调用层是不可选的**。

### 问题 B：自主选择

**已确认**：模型**只能调用统一入口**，来源由 Runtime 内部决定。
内部路由确实隐式承担了本应由模型负责的检索决策：
- 来源顺序（`WebSearchExecutionWorkflow.java:93-99`）；
- 是否联网（`WebSearchToolExecutor.java:116-125`，阈值 `minimum-local-results`）；
- 查哪个金融数据集（`InternalFinancialDataSearchExecutor` 自动相关性匹配，模型不参与）；
- 查询词如何改写（`NewsLocalQueryPlanner` / `ExternalSearchQueryResolver`）。

模型保留的决策面仅剩：`query` / `queryTerms` / `num_results` / `external_search_required` / `dataset`。

### 问题 C：调用结果

必须区分的五种情况，逐一给出当前可判定性：

| 情况 | 当前能否判定 | 依据 |
| --- | --- | --- |
| 模型完全没有选择本地能力 | **不可能发生** | 单一入口强制本地优先 |
| 模型调用了本地能力但执行失败 | 可判定 | `warnings[]`、`financialEnrichmentSkippedReason`、`NEWS_STORE_UNAVAILABLE` |
| 本地返回结果但模型未使用 | **需运行数据** | 结果字段齐全，但无记录 |
| 本地证据进入上下文后被截断/遗失 | **已确认存在机制** | 问题 4、5、8（摘要丢弃资讯条目、无 URL 证据被丢弃、6 条引用上限） |
| 模型使用了本地证据但报告未体现来源 | **已确认存在机制** | 问题 5（`web_search_fact_audit.v1` 不保留资讯标题/URL） |

### 问题 D：能力发现机制

**已确认**：
- Semantic Policy 无来源级分类（问题 6）；
- Planner Prompt 覆盖并窄化了工具描述（问题 2）；
- `mcpToolConfigs[].description` 可被运行期配置**整体覆盖**工具描述（`AgentPlannerPromptBuilder.java:826-850`）——若生产环境配置了该覆盖，模型看到的描述可能与代码中完全不同。**此项需在生产核对**（追加假设 H9）。
- 工具过滤粒度是 capability（`McpToolRegistry.java:142-146`），不是单工具，不构成本地能力不可见的原因。

---

## 五、根因分类与影响程度汇总

| 根因类别 | 具体问题 | 程度 | 确认状态 |
| --- | --- | --- | --- |
| **工具设计** | 三类来源压缩进单工具、单参数表（问题 1） | 高 | 已确认 |
| **能力暴露** | Prompt 遗漏金融数据 + 与 Schema 冲突（问题 2） | 高 | 已确认 |
| **路由机制** | 来源顺序与联网阈值由 Runtime 硬编码（问题 1、7） | 高 | 已确认 |
| **Runtime 越权** | 最终总结阶段强制要求外部 WSA 证据（问题 3） | 高 | 已确认 |
| **证据治理** | 无 URL 本地证据被过滤、资讯条目被摘要丢弃、引用名额不足（问题 4、5、8） | 中高 | 已确认机制，影响面待测 |
| **能力分类** | Semantic Policy 无来源角色（问题 6） | 中 | 已确认 |
| **模型决策** | —— | **未发现证据** | 无法验证（无运行记录） |
| **环境装配** | 金融富化服务 / WSA 凭证是否可用（H6、H8） | 高 | **未验证，优先级最高** |

**核心判断：当前现象的主因不在"模型决策"。** 模型在 `web_search` 这条路径上没有绕过本地的能力；真正把产出推向外部的是 Runtime 侧的 Prompt 窄化、最终总结强制联网轮、以及证据治理对无 URL 本地证据的过滤。

---

## 六、可复现 / 验证步骤（最小化验证方案）

### 6.1 仓库现状盘点：没有可用的运行证据

明确声明：以下在仓库中**不存在**，因此文档 03 之前**无法**完成第四部分要求的"基于真实日志的场景验证"：

- ✗ 无 `.log` 文件
- ✗ 无执行轨迹 / 事件回放 JSON
- ✗ 无预录制的 `web_search` 响应 fixture
- ✗ 无带引用的对话产物样本
- ✗ 无端到端测试覆盖"模型自主选择来源"的行为

**存在且可复用的**：

| 资产 | 位置 | 可验证什么 |
| --- | --- | --- |
| `WebSearchToolExecutorTest`（11 个用例） | `chatchat-runtime-news/src/test/.../WebSearchToolExecutorTest.java` | 本地/外部路由判定、关键词扇出、质量门槛、缓存、降级、取消 |
| `RemoteNewsMcpToolProviderTest`（17 个用例） | `chatchat-mcp-server/src/test/.../RemoteNewsMcpToolProviderTest.java` | 工具暴露面、金融先于资讯、数据集选择、日期抽取、最终总结轮不读金融 |
| `FinancialDataMcpToolProviderTest`（5 个用例） | `chatchat-mcp-server/src/test/.../FinancialDataMcpToolProviderTest.java` | 金融契约、字段省略、行数上限 |
| `GovernedExternalResearchOperatorTest`（8 个用例） | `chatchat-api/src/test/.../GovernedExternalResearchOperatorTest.java` | URL 治理、公私网过滤、拒绝条件 |
| `NewsMcpToolProviderTest`（3 个用例） | `chatchat-runtime-news/src/test/.../NewsMcpToolProviderTest.java` | 工具注册面 |
| `ProductionWebSearchTimeoutIsolationE2E` | `chatchat-e2e-tests/src/test/.../ProductionWebSearchTimeoutIsolationE2E.java` | 超时隔离与取消链（非来源选择） |

### 6.2 阶段一：环境装配核验（零改码，最高优先级）

目标：确认 H6 / H7 / H8。

1. 调用 MCP `tools/list`，取 `web_search` 的 `capabilityManifest`：
   - 若 `operations == ["search"]` 且无 `datasets` → `FinancialEnrichmentService` **未装配**，本地金融数据完全不可用（H6 成立）。
   - 若 `directoryStatus == "UNAVAILABLE"`（`WebSearchExecutionWorkflow.java:64-67`）→ 目录服务异常。
2. 调用 `web_search` with `operation=discover_datasets`，记录返回的 `financialAssets[].dataset` 与 `lastObservationDate`（验证 H7 的数据新鲜度）。
3. 检查 news runtime 日志中是否出现 `internalExternalWebSearchSkipped ... reason=provider_unavailable`（验证 H8）。

### 6.3 阶段二：按五类代表性查询做单工具级验证（零改码）

直接对 MCP `web_search` 发起调用，不经 Agent，逐项记录返回字段。

| 场景 | 调用参数 | 需记录的字段 | 判定标准 |
| --- | --- | --- | --- |
| 1. 历史证券行情分析 | `query="九阳股份最近一个月的收盘价与成交量"` | `financialDatasetCount`、`financialObservationCount`、`requires_second_query`、`financialEnrichmentSkippedReason` | 观测值 > 0 且时间窗覆盖 30 天 |
| 2. 证券相关新闻分析 | `query="九阳股份最近公告与新闻"` | `newsCount`、`qualifiedLocalNewsCount`、`localEvidenceSufficient`、`externalSearchRequired` | 本地资讯是否足量 |
| 3. 最新突发消息 | `query="今日 A 股突发消息"` | `externalSearchRequired`、`externalSearchSucceeded`、`externalProvider` | 外部是否成功 |
| 4. 综合投资研究 | `query="九阳股份行情、资金与新闻的主要驱动因素"` | 全部来源计数 + `records[]` 的 `resultType` 分布 | 三类是否同时出现 |
| 5. 本地缺失场景 | `query=` 某个本地必然无覆盖的冷门标的 | `warnings[]`、`financialEnrichmentSkippedReason`、`externalSearchRequired` | 是否正确降级 |

**关键观测日志**（均已在代码中，无需新增埋点）：
- `WebSearchToolExecutor.java:126-130` `webSearchBridgeRoute query=... localCandidateCount=... qualifiedLocalNewsCount=... requiredLocalCount=... externalRequired=...`
- `WebSearchToolExecutor.java:146/149` `internalExternalWebSearchSkipped ... reason=...`
- `InternalFinancialDataSearchExecutor.java:34-41` `internalFinancialDataSearch started/completed ... assetCount=... datasetCount=... skippedReason=...`

### 6.4 阶段三：Agent 级验证（确认 H1 / H2 / H5）

对同样 5 个场景走完整 Agent 链路，捕获：

1. **模型实际看到的工具定义** —— 打印 `AgentPlannerPromptBuilder.describeTools()` 的输出，并核对是否被 `runtimeAttributes.mcpToolConfigs[].description` 覆盖（验证 H9）。
2. **模型实际生成的计划与工具参数** —— 记录是否出现 `operation`、`dataset`、`queryTerms`、`external_search_required`。
3. **最终总结轮** —— 搜索日志 `准备联网检索 stage=final_summary_web_enhancement`（`FinalSummaryWebSearchEnhancer.java:93`）与 `finalSummaryWebSearchSkippedReason`，统计触发率（验证 H2）。
4. **引用构成** —— 统计最终 citations 中 `retrievalSource` 的分布（`news_index` / `financial_dataset_query` / `tencent_wsa`），验证 H5。

### 6.5 阶段四：对照实验（确认根因）

在不改代码的前提下，仅通过配置做 A/B：

| 实验 | 配置变更 | 预期（若对应根因成立） |
| --- | --- | --- |
| E1 | `chatchat.agent.final-summary-web-search-enabled: false` | 报告中外部网页引用占比显著下降 → 证实问题 3 是主因 |
| E2 | `chatchat.runtime.news.web-search.minimum-local-results: 1` | `externalSearchRequired` 触发率下降 → 量化问题 7 |
| E3 | `chatchat.runtime.news.web-search.enabled: false` | 若报告质量不降反升，说明外部结果在挤占本地证据位 → 证实问题 8 |

> 以上实验应在非生产环境执行，且每轮使用同一组 5 个查询以保证可比。

---

## 七、对"五个必须明确回答的问题"的阶段性答复

> 完整推荐见文档 03。此处仅给出根因层面的结论。

1. **当前为什么经常直接调用外部搜索？**
   不是模型选择的结果。模型在 `web_search` 上无法跳过本地。外部结果之所以在产出中占优，机械原因是：(a) 最终总结阶段 Runtime 自发的联网轮强制要求 WSA 证据且不读金融数据（问题 3）；(b) 本地资讯 7 天保留期 + 质量门槛导致 `localSufficient=false` 频繁触发外部补充（问题 7）；(c) 证据治理把无 URL 的本地证据滤掉、摘要层丢弃资讯条目（问题 4、5）。此外需优先排除"本地金融富化服务根本未装配"（H6）。

2. **属于工具设计、能力暴露、路由机制还是模型决策问题？**
   **工具设计 + 能力暴露 + 路由机制三者叠加，不是模型决策问题。** 目前没有任何证据指向模型决策失当，因为模型在这条路径上基本没有决策空间。

3. **是否需要拆分 `web_search`？** 见文档 03 §推荐。简述：需要，但拆分**不是**解决当前现象的必要条件，也不能单靠拆分解决问题 3、4、5。

4. **如何保持模型自主探索，同时提高本地数据资产利用率？** 见文档 03。核心是把"来源选择"与"覆盖情况"交还给模型，同时移除 Runtime 单方面的外部偏好。

5. **哪些由模型决定，哪些由 Runtime 执行？** 见文档 03 §职责边界表。

---

## 八、本轮未能验证的部分（明确声明）

以下问题在**只读、无运行数据**的条件下无法给出结论，不做任何推断性断言：

- 模型实际选择了哪些工具与参数。
- 本地检索实际返回了多少结果、时间覆盖如何。
- 本地证据是否真的进入了模型上下文。
- 最终报告是否引用了有效证据、引用来源分布如何。
- `FinalSummaryWebSearchEnhancer` 的真实触发率。
- 生产环境是否通过 `mcpToolConfigs` 覆盖了工具描述。
- 生产金融数据集目录的实际覆盖面与新鲜度。

上述每一项都已在 §6 给出对应的最小验证步骤。
