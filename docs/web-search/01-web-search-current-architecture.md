# 01 · web_search 多源检索 —— 现有架构核查

> 审查范围：`web_search` 能力从工具注册、模型可见契约、三类数据源实现、Semantic Policy 到证据入上下文的完整链路。
> 本轮只读，未修改任何实现、配置、提示词或数据库结构。
> 所有结论均标注代码位置（`路径:行号`）。未经实际运行验证的内容在文档 02 中单独归类为"假设"。

---

## 一、结论速览

| 核查项 | 实际情况 |
| --- | --- |
| 模型可见的检索工具数量 | **1 个**：`web_search` |
| `local_news_search` 是否存在为模型可调用工具 | **否**（`news_search` 仅在 news runtime 进程内注册，未经 MCP 发布给模型） |
| `financial_data_search` 是否存在为模型可调用工具 | **否**（存在实现，但刻意不注册为 Spring Bean） |
| `external_web_search` 是否存在为模型可调用工具 | **否**（显式标注 `internal_bridge_only`） |
| 检索顺序由谁决定 | **Runtime 硬编码**：金融数据 → 本地资讯 → 外部搜索 |
| 模型能否跳过本地直接联网 | **不能**（通过 `web_search` 路径在结构上不可能） |
| Runtime 能否在模型未请求时自行联网 | **能**（`FinalSummaryWebSearchEnhancer`，生产默认开启） |

---

## 二、模块与进程拓扑

```
┌──────────────────────────┐
│ chatchat-agents          │  模型自主探索循环 / Planner Prompt / 最终答案增强
│  AgentOrchestrationEngine│
└───────────┬──────────────┘
            │ ToolRuntimeService → ToolRegistry（工具名形如 mcp_chatchat_mcp_server_web_search）
            ▼
┌──────────────────────────┐
│ chatchat-runtime-mcp     │  McpToolRegistry：注册 / 去重 / 能力开关 / 发布 ToolMetadata
└───────────┬──────────────┘
            ▼
┌──────────────────────────────────────────────────────────────┐
│ chatchat-mcp-server                                          │
│  RemoteNewsMcpToolProvider  ──只发布 1 个工具： web_search    │
│          │                                                   │
│          ▼                                                   │
│  WebSearchExecutionWorkflow （search.web.v1，分阶段工作流）   │
│     ├── InternalFinancialDataSearchExecutor（内部桥接，不可见）│
│     │        └── FinancialEnrichmentService → 金融数据存储    │
│     └── NewsSearchService → NewsRuntimeClient (HTTP)          │
└───────────┬──────────────────────────────────────────────────┘
            │ POST /internal/v1/news/tools/web_search
            ▼
┌──────────────────────────────────────────────────────────────┐
│ chatchat-runtime-news （独立部署进程）                        │
│  WebSearchToolExecutor （公开桥）                             │
│     ├── NewsDocumentStore → OpenSearch 本地资讯索引           │
│     └── ExternalWebSearchToolExecutor（内部，不注册）         │
│              ├── WebSearchCache（OpenSearch 缓存）            │
│              └── TencentWebSearchClient（腾讯 WSA API）       │
└──────────────────────────────────────────────────────────────┘
```

---

## 三、工具注册与模型可见契约

### 3.1 唯一被发布的工具定义

`chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/news/tool/RemoteNewsMcpToolProvider.java:28-64`

```java
McpToolDefinition webSearch = definition("web_search", "Unified Web Search",
    "Retrieve news/web content AND registered financial observations, including market-price datasets when available. "
  + "Inspect the publisher capabilityManifest for actual dataset codes. You choose the dataset supporting your analysis; "
  + "operation=discover_datasets returns registered fields and descriptions without reading data. "
  + "operation=read_dataset with dataset, filters, startDate/endDate reads stored observations directly. "
  + "Search auto-matches are candidates, not proof of coverage. Refine or explicitly read when they do not support your conclusion. "
  + "Unified one-call retrieval for current hotspots, place names, knowledge beyond the local corpus, "
  + "news, and governed financial data. Governed financial data and local news are searched first; "
  + "external search is a supplemental fallback and is not "
  + "a separate user-facing tool. ...",
```

**类名/方法**：`RemoteNewsMcpToolProvider#RemoteNewsMcpToolProvider(WebSearchExecutionWorkflow)`，发布入口 `definitions()`（:74）、执行入口 `findExecutor()`（:78-80）、能力清单 `capabilityManifest()`（:75-77）。

**参数 Schema**（:43-63，共 16 个参数）：

| 参数 | 类型 | 默认 | 语义 |
| --- | --- | --- | --- |
| `operation` | string enum | `search` | `search` / `discover_datasets` / `read_dataset` |
| `query` | string | — | 原始用户问题，**不会直接发给外部搜索商** |
| `queryTerms` | string[] ≤8 | — | 分析后的检索词；本地逐条检索，外部合并使用 |
| `keywords` | string[] ≤8 | — | `queryTerms` 的别名 |
| `intent` | string | — | 无关键词时外部检索使用 |
| `num_results` | number | 10（1–50） | 统一结果上限 |
| `external_search_required` | boolean | false | 显式要求外部补充 |
| `financial_data_required` | boolean | false | 兼容标记 |
| `financial_dataset_limit` | number | 2（1–3） | 动态匹配数据集上限 |
| `financial_row_limit` | number | 20（1–50） | 每数据集行数上限 |
| `dataset` | string | — | 精确数据集代码 |
| `filters` | object | — | 已注册字段精确过滤 |
| `startDate` / `endDate` | string | — | `YYYY-MM-DD` |
| `historyMode` | string | auto | `auto` / `daily` / `weekly` |
| `discovery_id` | string | — | 检索链审计标识 |
| `limit` | number | 50（1–200） | 指定 dataset 时行数上限 |

### 3.2 MCP 注册与发布机制

`chatchat-runtime-mcp/src/main/java/com/chatchat/runtime/mcp/registry/McpToolRegistry.java`
- `:26-46` 构造期遍历所有 `McpToolProvider`，`validatePublicationName` 做语义名冲突检测，`putIfAbsent` 冲突即抛 `Duplicate MCP tool registration`。
- `:88-120` `publishOne()` 将 `McpToolDefinition.description()` 原样写入 `ToolMetadata.description`，并带上 `inputSchema` / `outputSchema` / `governance` 元数据。
- `:142-146` `capabilityEnabled()` 按 `McpCapabilitiesProperties` 与 `McpCapabilityStatePort` 做能力级开关（粒度为 capability，不是单工具）。

工具名归一化：`chatchat-common/src/main/java/com/chatchat/common/tool/McpToolNamePolicy.java:48-67`，剥离 `mcp_`、`chatchat_mcp_server_` 等传输前缀。模型侧实际看到的名称形如 `mcp_chatchat_mcp_server_web_search`（见 `chatchat-chat/src/test/java/com/chatchat/chat/task/core/AgentTaskServiceTest.java:1264`）。

### 3.3 被刻意隐藏的两个能力

**（1）`financial_data_search`** — `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/news/tool/FinancialDataMcpToolProvider.java:22-27`

```java
/**
 * Legacy compatibility wrapper for direct financial-data calls.
 *
 * <p>It is intentionally not a Spring component: production search exposes only web_search and
 * runs this capability as an internal bridge stage, preventing independent query rewriting.</p>
 */
```

该类已具备完整的模型级契约（名称 `:29`、描述 `:39-43`、10 个参数 `:46-57`、20s 超时 `:60`），描述中明确写有 `"This tool never searches the public web."` 与 `"Never invent or hardcode dataset codes."`。但它**没有 `@Component`**，因此永远不会进入 `McpToolRegistry`。
实际运行时走的是包级私有类 `InternalFinancialDataSearchExecutor`（`:19-77`），其 `TOOL_NAME = "financial_data_search"`（:21）、`PURPOSE = "web_search_bridge_financial_analysis"`（:22），并在 `bridgeInput()`（:53-73）中注入 `userFacingTool=false`、`bridgeParentTool=web_search`。

**（2）`external_web_search`** — `chatchat-runtime-news/src/main/java/com/chatchat/runtime/news/tool/executor/ExternalWebSearchToolExecutor.java:21-27`

```java
/**
 * Internal-only external retrieval tool. It is intentionally not registered by
 * {@link NewsMcpToolProvider}; callers can reach it only through the public web_search bridge.
 */
public class ExternalWebSearchToolExecutor implements NewsToolExecutor {
    public static final String INTERNAL_TOOL_NAME = "external_web_search";
```

### 3.4 news runtime 进程内的工具集（不等于模型可见集）

`chatchat-runtime-news/src/main/java/com/chatchat/runtime/news/tool/NewsToolNames.java` 定义 4 个名字：`web_search` / `news_search` / `news_latest` / `news_source_status`，由 `NewsMcpToolProvider:22-30` 注册。
但 news runtime 是一个**内部 HTTP 服务**，`chatchat-mcp-server` 只通过 `NewsRuntimeClient.invoke("web_search", ...)` 调用其中一个（`NewsSearchService.java:39`）。**`news_search` / `news_latest` / `news_source_status` 不会出现在模型工具列表中。**

### 3.5 测试证据（锁死了"只暴露一个工具"）

- `chatchat-mcp-server/src/test/java/com/chatchat/mcpserver/news/tool/RemoteNewsMcpToolProviderTest.java:359-373` — `exposesOnlyUnifiedWebSearch`：断言 definitions 只有 `web_search`；`findExecutor` 对 `news_search` / `news_latest` / `news_source_status` / `search_financial_dataset` / `get_financial_data` 一律返回 empty。
- `chatchat-runtime-news/src/test/java/com/chatchat/runtime/news/tool/registry/NewsMcpToolProviderTest.java:30-38` — `exposesOnlyNewsRuntimeExecutors`：断言 `external_web_search` 不被注册。
- `chatchat-mcp-server/src/test/java/com/chatchat/mcpserver/news/tool/FinancialDataMcpToolProviderTest.java:122-134` — `toolContractMakesLocalAndWebResponsibilitiesExplicit`：验证 `financial_data_search` 契约文本，但该 Provider 从未被 Spring 装配。

---

## 四、完整调用链

### 4.1 `operation=search`（默认路径）

`chatchat-mcp-server/.../WebSearchExecutionWorkflow.java`

| 阶段 | 行号 | 行为 |
| --- | --- | --- |
| `validateInput` | :72-75 | 仅校验非空 |
| `analyze` | :77-90 | 解析 `dataset` / `query` / `operation`，产出 mode：`CATALOG` / `DATASET_QUERY` / `DISCOVERY` |
| `plan` | :92-100 | DISCOVERY 模式固定步骤：`ANALYZE_QUERY → SEARCH_GOVERNED_FINANCIAL_DATA → SEARCH_LOCAL_NEWS → SUPPLEMENT_EXTERNAL_WEB → MERGE_AND_RANK → VERIFY_EVIDENCE` |
| `executeSearch` | :122-276 | 实际执行 |
| `verify` | :107-115 | 写回 `executionWorkflow` / `workflowMode` / `workflowSteps` / `workflowVerified` |

执行细节：
- `:183-190` **注释明写**："Local governed financial retrieval is authoritative and must finish before the news runtime is allowed to consider supplemental internet recall." —— 金融检索先于资讯，且不可由模型调换。
- `:199-201` 统计 `financialObservationCount`，并作为 `upstreamLocalEvidenceCount` 透传给 news runtime（`NewsSearchService.java:25-38`）。
- `:205-207` 仅当 news 与 assets 均为空且两路都告警时才整体失败。
- `:209-211` `interleave()`（:430-438）交错合并"金融在前、资讯在后"，截断到 `num_results`。
- `:228-233` 输出中写死声明：

```java
data.put("retrievalOrder", List.of(
    "financial_data_search_internal",
    "local_news_index",
    "external_web_search_internal"));
data.put("externalSearchRole", "supplementary_fallback");
```

### 4.2 `operation=read_dataset` / `discover_datasets`

- `discover_datasets`：`:124-131`，返回 `result_type=dataset_capability_catalog`、`selectionAuthority=MODEL`、`isObservationEvidence=false`。
- `read_dataset` / 带 `dataset` 的调用：`:132-172`，**不做二次截断**（:138-140 注释："Do not apply a second presentation-layer row/field truncation at the MCP boundary"），输出 `result_type=financial_dataset_query`、`retrieval_stage=EXECUTION`、`resultView=complete_fact_rows`。
- 能力清单 `capabilityManifest()`（:38-70）向模型声明 `selectionAuthority=MODEL`、`searchContract`、`readContract` 及 `datasets` 目录。

### 4.3 news runtime 侧的二次路由

`chatchat-runtime-news/.../WebSearchToolExecutor.java`

| 行号 | 行为 |
| --- | --- |
| :60-64 | 校验 query；本地索引与外部均不可用时返回 `NEWS_STORE_UNAVAILABLE` |
| :72-74 | `NewsLocalQueryPlanner.plan()` 做"原问题 + 关键词扇出"（策略 `original_plus_keyword_fanout_v1`，最多 8 个子查询） |
| :80-101 | 逐个子查询调用 `NewsDocumentStore.search()`，合并去重 |
| :90-96 | `NewsRelevanceRanker.rank()` 质量过滤（策略 `news_semantic_idf_ngram_v1`） |
| :116-125 | **外部补充决策**（见下） |
| :134-150 | 满足条件才委托 `ExternalWebSearchToolExecutor` |
| :156 | `fuse()`（:240-247）RRF 式融合，同 URL/标题标记 `corroborated=true` + `retrievalSources` |
| :157-191 | 组装 24 个可观测字段 |

外部补充的判定（`:116-125`）：

```java
boolean forceExternal = properties.getWebSearch().getCache().isForceExternal();
int requiredLocalResults = Math.min(size, Math.max(1, properties.getWebSearch().getMinimumLocalResults()));
int upstreamLocalEvidenceCount = upstreamLocalEvidenceCount(input);
int totalLocalEvidenceCount = qualifiedLocalNewsCount + upstreamLocalEvidenceCount;
boolean localSufficient = (localSucceeded || upstreamLocalEvidenceCount > 0)
    && totalLocalEvidenceCount >= requiredLocalResults;
boolean externalRequested = input.getParameterAsBoolean("external_search_required", false);
boolean externalSupplementRequired = externalRequested || forceExternal || !localSufficient;
```

审计日志 `:126-130` 输出 `webSearchBridgeRoute ...`；跳过时输出 `internalExternalWebSearchSkipped ... reason=local_evidence_sufficient`（:146）或 `reason=provider_unavailable`（:149）。

---

## 五、三类数据源实现

### 5.1 本地资讯检索（LOCAL_NEWS）

| 组件 | 位置 |
| --- | --- |
| 存储接口 | `chatchat-runtime-news/.../store/NewsDocumentStore.java:10-16`（`bulkIndex` / `search` / `findById`） |
| OpenSearch 实现 | `chatchat-runtime-news/.../store/OpenSearchNewsDocumentStore.java:86-116`（索引）、`:140-198`（词法 + 可选向量检索）、`:201-216` |
| 查询规划 | `chatchat-runtime-news/.../search/query/NewsLocalQueryPlanner.java:28-41`，策略常量 `:13` |
| 相关性排序 | `chatchat-runtime-news/.../search/ranking/NewsRelevanceRanker.java:29-84`，字段权重（title 6.0 / tags 5.0 / categories 4.5 / summary 3.0 / sourceName 1.5 / content 1.0）、IDF 加权、`RELATIVE_SCORE_FLOOR=0.20`、`MINIMUM_QUERY_COVERAGE=0.08` |
| 结果项构造 | `WebSearchToolExecutor.java:202-216`，`retrievalSource=news_index`、`resultType=news` |
| 配置 | `chatchat-runtime-news/src/main/resources/application.yml:94-99`（`open-search.enabled: true`、`index-name: runtime-news`、`retention-days: 7`） |

索引入口：资讯采集/清洗/聚合流水线写入 `bulkIndex`，索引名按天滚动（`index-name` 前缀），默认保留 7 天。

### 5.2 本地金融数据检索（LOCAL_FINANCIAL_DATA）

| 组件 | 位置 |
| --- | --- |
| 内部桥接 | `chatchat-mcp-server/.../tool/InternalFinancialDataSearchExecutor.java:30-51` |
| 富化服务 | `chatchat-mcp-server/.../news/financial/FinancialEnrichmentService.java:20-21`（`@Service`），对外提供 `enrich` / `queryDataset` / `datasetCatalog` |
| 数据集定义 | `chatchat-runtime-market/.../storage/FinancialDatasetDefinition.java:64`（`"通过受控金融数据API采集的结构化数据集：" + code`） |
| 行读取 | `FinancialDataStore` 已按调用方 limit 裁剪；MCP 边界不再二次截断（`WebSearchExecutionWorkflow.java:138-140`） |
| 遗留直调封装 | `FinancialDataMcpToolProvider.java:79-142`，字段 >4000 字符自动省略（:193-194），行上限强制 200（:204-206），并输出 `_omitted_fields` |
| 资产目录项 | `WebSearchExecutionWorkflow.java:320-364` `assetResult()`，含 `storageLocation` / `archiveStorageLocation` / `retentionPolicy` / `availableFields` / `followUp` |
| 索引引导 | `:366-402` `financialIndexGuide()`，`name=financial-data-asset`、`contractVersion=financial_index_capability_v1` |

查询契约与资讯全文检索**已经不同**：金融侧是 `dataset + filters(已注册字段) + startDate/endDate + historyMode + limit`，资讯侧是 `query + queryTerms + num_results`。两套契约目前被压在**同一个工具的同一个参数表**里。

### 5.3 外部搜索引擎（EXTERNAL_WEB_SEARCH）

| 组件 | 位置 |
| --- | --- |
| 内部执行器 | `chatchat-runtime-news/.../tool/executor/ExternalWebSearchToolExecutor.java:47-125` |
| 查询改写 | `chatchat-runtime-news/.../search/query/ExternalSearchQueryResolver.java:19-32`，优先级 `queryTerms/keywords`（`analyzed_keywords`）→ `intent`（`analyzed_intent`）→ 空（`missing_analyzed_query`）；上限 8 词 / 256 字符，并剔除原始问题 |
| 供应商客户端 | `chatchat-runtime-news/.../search/provider/TencentWebSearchClient.java:63-71`（`enabled()` / `search()`）、`:184-201`（TC3-HMAC-SHA256 签名） |
| 缓存 | `WebSearchCache`（OpenSearch），`minimum-similarity: 0.72`、`retention-days: 7` |
| 结果项 | `resultType=web`、`documentKind=external_web_page`、`retrievalSource=tencent_wsa` 或 `tencent_wsa_cache` |
| 配置 | `application.yml:66-93`，`enabled: true`、`max-results: 20`、`timeout-millis: 10000`、`max-snippet-chars: 1200`、`minimum-local-results: 3`、`maximum-local-query-terms: 8`、`cache.force-external: false` |

**关键约束**：原始用户问题永不出境（`ExternalWebSearchToolExecutor.java:52-55` 直接拒绝无分析关键词的外部检索）。

---

## 六、Runtime Semantic Policy 的影响

### 6.1 策略来源

- 表结构与种子数据：`database/migration/{h2,mysql,postgresql}/V20261009_01__agent_runtime_semantic_policy.sql`（表 `agent_runtime_semantic_policy(policy_key, policy_json)`，种子键 `default`）。
- 工具意图绑定：`database/migration/*/V20261008_03__agent_tool_intent_binding.sql`，种子行：
  ```sql
  ('webSearch', 'web_search', 'web_search', '_web_search', true)
  ('documentWorkflow', 'document_search', '', '', true)
  ```
- 加载：`chatchat-chat/.../interaction/service/DatabaseRuntimeSemanticPolicySource.java:48-51`（启动校验）、`:54-87`（`snapshot()`，并合并能力注册表中 `runtimeRoles` 扩展）。
- 匹配：`chatchat-agents/.../runtime/plan/RuntimeSemanticPolicy.java:157-165` `hasRole()`，规则匹配模式 `EXACT/SUFFIX/CONTAINS/REGEX`（:288-299）。

### 6.2 与检索相关的角色映射（种子策略原文）

```json
{"role":"WEB_SEARCH",    "mode":"CONTAINS","value":"web_search"}
{"role":"WEB_DISCOVERY", "mode":"CONTAINS","value":"web_search"}
{"role":"WEB_DISCOVERY", "mode":"CONTAINS","value":"web_page_analyze"}
{"role":"WEB_DISCOVERY", "mode":"CONTAINS","value":"site_intelligence"}
{"role":"WEB_DISCOVERY", "mode":"CONTAINS","value":"generic_web_site_search"}
{"role":"WEB_DISCOVERY", "mode":"CONTAINS","value":"web_site_search"}
{"role":"WEB_DISCOVERY", "mode":"CONTAINS","value":"site_search","exclude":"search_and_extract"}
```

策略中存在的角色集合包含 `WEB_SEARCH` / `WEB_DISCOVERY` / `DOCUMENT_SEARCH` / `ASSET_DISCOVERY` / `SQL_*` 等，**但不存在 `LOCAL_NEWS`、`FINANCIAL_DATA`、`EXTERNAL_WEB` 之类的来源级角色**。即：在语义策略层面，本地资讯与本地金融数据**没有独立的能力分类**，二者与外部联网同属 `WEB_SEARCH` / `WEB_DISCOVERY` 一个桶。

---

## 七、模型调用工具前实际获得的信息

### 7.1 工具描述组装

`chatchat-agents/.../orchestration/planning/generation/AgentPlannerPromptBuilder.java`
- `:723-749` `describeTools()`：对每个工具输出 `- <name>: <description>`，随后追加 `appendApplicability`、`CapabilityManifestInjector.append(...)`（注入 `capabilityManifest`，即 `operations` / `searchContract` / `readContract` / `datasets`）、`appendBatchInputSchema`。
- `:826-850` `configuredToolDescription()`：`runtimeAttributes.mcpToolConfigs[].description` **可整体覆盖** 已注册的工具描述。
- `:209` 将结果拼入 `Available tools:`。
- `:44-51` 预算常量：`MAX_USER_QUERY_PROMPT_CHARS = 32_000`、`COMPACT_USER_QUERY_PROMPT_CHARS = 12_000`、`COMPACT_TOOL_DESCRIPTION_CHARS = 800`；紧凑模式入口 `:479 describeToolsCompact`。无工具数量硬上限。

### 7.2 Prompt 中额外追加的检索指引（原文）

`AgentPlannerPromptBuilder.java:235-237`：

```java
prompt.append("Unified search contract:\n");
prompt.append("- Pass the user's original query to the governed search capability. Internal source routing is owned by the tool implementation; do not invent hidden tools, source identifiers, or dataset codes.\n\n");
```

`AgentPlannerPromptBuilder.java:248-250`：

```java
prompt.append("web_search retrieves standardized local news and, when the internal Tencent WSA enhancer is configured, "
    + "can supplement current hotspots, place names, and knowledge beyond the local corpus from the public web. "
    + "The external provider remains an internal implementation detail, not a separate tool.\n\n");
```

`:238-247` 另有 "Web evidence workflow" 5 条，要求发现类工具的 snippet 不得作为最终证据，必须经 crawler 取全文后才能 `final_answer`。

> 注：该段 Prompt 与 `RemoteNewsMcpToolProvider` 的工具描述**不一致**，前者完全没有提到"本地金融数据"，且"do not ... invent ... dataset codes" 与工具 Schema 中的 `dataset` / `operation=read_dataset` 形成语义冲突。详见文档 02 §3.1。

### 7.3 计划打分中的关键词

`chatchat-agents/.../planning/selection/AgentPlanCandidateScorer.java:561-566`：

```java
private boolean expectsWebEvidence(PlannerValidationContext validationContext) {
    String text = requestText(validationContext);
    return containsAny(text, "web", "website", "site", "online", "internet", "current", "latest", "today", "recent",
        "网页", "网站", "联网", "互联网", "当前", "最新", "今天", "近期");
}
```

`:555-559` 同理有 `expectsDocumentEvidence`。这是**对计划候选打分**的关键词启发式，不是来源路由，但会提高"含最新/当前/今天"类问题选中 Web 证据路径的权重。

---

## 八、工具执行结果进入模型上下文的过程

### 8.1 结果摘要与截断

`chatchat-common/src/main/java/com/chatchat/common/tool/ToolLogSummarizer.java`
- `:17-21` 常量：`DEFAULT_MAX_CHARS = 2_000`、`MAX_DEPTH = 4`、`MAX_MAP_ENTRIES = 24`、`MAX_LIST_ITEMS = 8`、`MAX_STRING_CHARS = 320`。
- `:77-155` `summarizeResult()` 对 `web_search` 做**专用分支**，产出 `schemaVersion = "web_search_fact_audit.v1"`，仅保留：`query` / `provider` / `count` / `newsCount` / `financialDatasetCount` / `financialObservationCount` / `financialFacts`（或 `financialEvidenceRows`，经 `redactComplete` 保留完整金融行）。
- `:418-432` 敏感键脱敏（token/password/secret/apikey/cookie/sessionid 等）。

### 8.2 证据对象模型（Evidence Bundle）

`chatchat-common/.../runtime/analysis/evidence/`

| 类型 | 字段 | 行号 |
| --- | --- | --- |
| `EvidenceBundle` | `schemaVersion="analysis_evidence_bundle.v1"`, `evidence`, `limitations`, `metadata` | `EvidenceBundle.java:8-13` |
| `AnalysisEvidence`（接口） | `evidenceId` / `capability` / `content` / `attributes` | `AnalysisEvidence.java:1-12` |
| `ExternalResearchEvidence` | `evidenceId` / `url` / `publisher` / `publishedAt` / `content` / `attributes` | `ExternalResearchEvidence.java:1-15` |
| `StructuredDataEvidence` | `evidenceId` / `dataset` / `query` / `rows` / `asOf` / `content` / `attributes` | `StructuredDataEvidence.java:1-15` |
| `DocumentAnalysisEvidence` | `documentId` / `chunkId` / `section` / `citation` / `score` ... | `DocumentAnalysisEvidence.java:1-16` |
| `ToolAnalysisEvidence` | `toolName` / `invocationId` / `content` | `ToolAnalysisEvidence.java:1-14` |
| `ComputationEvidence` | `formula` / `inputEvidenceIds` | `ComputationEvidence.java:1-16` |

**存在 `ExternalResearchEvidence` 与 `StructuredDataEvidence`，但不存在 `LocalNewsEvidence` 这一独立证据类型**——本地资讯目前落在 `ExternalResearchEvidence` / `ToolAnalysisEvidence` 中。

### 8.3 外部研究治理算子

`chatchat-api/src/main/java/com/chatchat/api/runtime/GovernedExternalResearchOperator.java`

| 规则 | 行号 |
| --- | --- |
| 工具名白名单：`web_search` 或 `mcp_[a-zA-Z0-9_]+_web_search` | :36-40 |
| query ≤ 4000 字符 | :44 |
| 检索词 ≤ 8 条、每条 ≤ 80 字符、不得等于原 query | :46-51 |
| 固定下发 `num_results = 5` | :55 |
| 结果数必须 1–5 | :62 |
| URL 必须 http/https、公网主机、无端口 | :73-79, :102-108 |
| 截断：url 2048 / title 300 / snippet 2000 | :68-71 |
| 产出 `ExternalResearchEvidence`，`attributes.remoteProjection` | :84-90 |

测试：`chatchat-api/src/test/java/com/chatchat/api/runtime/GovernedExternalResearchOperatorTest.java:30-100`（公网源保留、私网源过滤、过期/无日期源保留交由用户判断、无可归属公网源时整体拒绝）。

### 8.4 引用与报告

`chatchat-chat/src/main/java/com/chatchat/chat/task/core/AgentTaskService.java`
- `:88` `UI_CITATION_PREMISE_LIMIT = 900`，`:2999-3004` 截断实现。
- `:2978-2997` `evidencePremises()`，**最多 6 条**（:2992）。
- `:290-314` 引用来源读取顺序：`citations` → `uiResponse.citations` → `executionResult.citations` → `executionResult.uiResponse.citations` → `executionResult.sources` → `references` → artifact 资源兜底。
- `:86-87` `MAX_IDLE_POLLS = 3`、`MAX_CONFIRMATION_ROUNDS = 20`（:1209-1210 超限抛错）。

`chatchat-chat/.../interaction/service/handler/ToolDirectModeHandler.java:28` `WEB_SEARCH_REFERENCE_LIMIT = 10`，:129-132 直调模式下固定注入 `query` + `num_results=10`。

### 8.5 补证机制

- `chatchat-agents/.../runtime/federation/KnowledgeAgentEvidenceSupplement.java:45-95`，单次补证上限 **5 条**（:78 `Math.min(5, Math.max(1, requirement.minimumCount()))`）。
- `chatchat-agents/.../orchestration/analysis/semantic/SemanticGapEvidenceBridge.java:273`：`"业务分析发现 " + requests.size() + " 项可补充的数据需求，正在进入补证决策。"`
- 契约文档：`docs/agent-runtime-evidence-augmentation-contract.md`、`docs/evidence-os-kernel-v1.md:20-32`（证据管线 `ToolOutput → WebEvidence → EvidenceTrustEvaluator → Citation Binding → Agent Reasoning → Answer Review`）。

---

## 九、自主探索循环中的工具调用与报告生成

### 9.1 预算与限制

`chatchat-agents/.../planning/validation/AgentRuntimeGuard.java`
- `:15` `MAX_CONFIGURABLE_STEPS = 50`（硬上限，配置再大也被 `:75-79` 夹住）。
- `:122-132` `maxToolCalls` 默认 `Integer.MAX_VALUE`，可由 `AGENT_MAX_TOOL_CALLS_ATTRIBUTE` 覆盖。
- `chatchat-agents/.../orchestration/AgentOrchestrationEngine.java:1279-1287`：超预算时置 `toolBudgetExceeded=true`、`stopReason=tool_budget_exceeded`，并以 `"执行计划超过本次请求的步骤或工具调用预算，已停止执行。"` 结束。

### 9.2 Runtime 自主发起的联网补充（非模型决策）

`chatchat-agents/.../orchestration/answer/FinalSummaryWebSearchEnhancer.java`
- 类注释（:31-33）："Adds at most one bounded web-retrieval round before final answer review. The WSA provider remains hidden behind the existing governed web_search tool."
- `:37` `CANDIDATE_STAGE = "final_summary_web_enhancement"`。
- `:62-72` 关闭条件：`finalSynthesisRetrievalAllowed=false` 或 `final-summary-web-search-enabled=false`。
- `:78-96` 由模型做一次 `needed/keywords/reason` 的"是否需要联网"判定，随后记录日志 `"准备联网检索 stage={} runId={} tool={} keywords={} reason={}"`（:93）。
- `:265-269` `execute()` **只下发 `query`（单个关键词）与 `num_results`**，不传 `queryTerms` / `external_search_required` / 金融参数。
- `:110-112` 判定 `containsTencentWsaEvidence(output.getData())`，**若结果中没有腾讯 WSA 证据即记为失败**（`"external web provider unavailable"`）。
- `:279-280` 注入 `internalPurpose=final_summary_web_enhancement`、`userFacingTool=false`。

生产配置：`packaging/config/application-prod.yml:180-184` —— `final-summary-web-search-enabled: true`、`max-keywords: 2`、`result-limit: 6`、`evidence-max-chars: 16000`、`timeout-ms: 120000`。

对应的下游行为：`InternalFinancialDataSearchExecutor.java:59-62` —— 当 `internalPurpose == final_summary_web_enhancement` 且未显式要求金融数据时，**不注入 `financial_data_required`**，即该轮补充**不会读取本地金融行**。测试锁定：`RemoteNewsMcpToolProviderTest.java:141-164` `finalSummaryWebEnhancementNeverHydratesFinancialRows`（断言 `financialDatasetCount == 0`）。

---

## 十、关键代码位置索引

| 关注点 | 文件 | 行号 |
| --- | --- | --- |
| 工具定义与描述 | `chatchat-mcp-server/.../news/tool/RemoteNewsMcpToolProvider.java` | 28-64 |
| 统一检索工作流 | `chatchat-mcp-server/.../news/tool/WebSearchExecutionWorkflow.java` | 72-276 |
| 固定检索顺序声明 | 同上 | 93-99, 183-184, 228-233 |
| 能力清单 | 同上 | 38-70 |
| 金融内部桥 | `chatchat-mcp-server/.../news/tool/InternalFinancialDataSearchExecutor.java` | 19-77 |
| 金融遗留工具（未装配） | `chatchat-mcp-server/.../news/tool/FinancialDataMcpToolProvider.java` | 22-61 |
| news runtime 桥 | `chatchat-runtime-news/.../tool/executor/WebSearchToolExecutor.java` | 58-192 |
| 外部补充判定 | 同上 | 116-130 |
| 外部检索执行器（内部） | `chatchat-runtime-news/.../tool/executor/ExternalWebSearchToolExecutor.java` | 21-125 |
| 外部查询改写 | `chatchat-runtime-news/.../search/query/ExternalSearchQueryResolver.java` | 19-45 |
| 本地查询扇出 | `chatchat-runtime-news/.../search/query/NewsLocalQueryPlanner.java` | 28-41 |
| 本地相关性排序 | `chatchat-runtime-news/.../search/ranking/NewsRelevanceRanker.java` | 29-84 |
| MCP 注册表 | `chatchat-runtime-mcp/.../registry/McpToolRegistry.java` | 26-46, 88-120, 142-146 |
| Semantic Policy | `chatchat-agents/.../runtime/plan/RuntimeSemanticPolicy.java` | 157-165, 288-299 |
| Policy 加载 | `chatchat-chat/.../interaction/service/DatabaseRuntimeSemanticPolicySource.java` | 48-87 |
| Planner Prompt | `chatchat-agents/.../planning/generation/AgentPlannerPromptBuilder.java` | 209, 235-250, 723-749, 826-850 |
| 结果摘要截断 | `chatchat-common/.../tool/ToolLogSummarizer.java` | 17-21, 77-155 |
| 证据模型 | `chatchat-common/.../runtime/analysis/evidence/*.java` | 见 §8.2 |
| 外部研究治理 | `chatchat-api/.../runtime/GovernedExternalResearchOperator.java` | 36-108 |
| 最终答案联网增强 | `chatchat-agents/.../orchestration/answer/FinalSummaryWebSearchEnhancer.java` | 37, 62-120, 265-290 |
| 运行预算 | `chatchat-agents/.../planning/validation/AgentRuntimeGuard.java` | 15, 75-79, 122-132 |
| 引用与截断 | `chatchat-chat/.../task/core/AgentTaskService.java` | 86-88, 290-314, 2978-3004 |
