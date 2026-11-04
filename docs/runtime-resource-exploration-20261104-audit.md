# 现有资源索引及 Agent 调用链审查报告

审查时间：2026-11-04。远端环境：192.168.195.221；本地分支 `feature/runtime-knowledge-exploration`。执行 `git fetch origin` 后，远端 main 仍为 `743a01304`；本地增加知识探索实现 `89acd56bc` 和验收记录 `e1b998792`。本报告提交之前未修改本轮业务源码、索引或生产授权。

## 实际数据结构与复用结论

| 资源 | 实际运行结构 | 应复用的实现及方法 | 本轮边界 |
| --- | --- | --- | --- |
| 企业文档 | `runtime_os_stage_documents` 453 条、`chatchat_documents_dev` 3954 条，数量为索引条目而非文档数。含 fileId、sourceVersion、chunkIndex、section、tenantId、chunkText；旧数据缺少可靠 PDF 页码 | `chatchat-api/.../runtime/coding/knowledge/CodingKnowledgeService.java` 的 call/source/validateContext/validateReport；`chatchat-knowledge-base/.../OpenSearchDocumentIndexService.java`；SearchService 原文读取 | 复用已上线的 5 个工具、实时 ACL 和 Evidence，禁止虚构页码 |
| 财经新闻 | `runtime-news-2026.10.29` 至 `runtime-news-2026.11.04` 共 7 个日索引。字段为 documentId、sourceId、title、content、summary、publishTime、collectTime、sourceUrl、contentHash、categories、tags、metadata。采样显示发布时间与采集时间不同。metadata 中按来源存在 securityCode、stockList、legalRisk、legalDisclaimer 等 | `chatchat-runtime-news/.../store/NewsDocumentStore.java` 的 search/findById；`.../store/opensearch/OpenSearchNewsDocumentStore.java`；`.../tool/executor/NewsSearchToolExecutor.java` | 新闻正文查询已存在，不重写 OpenSearch 新闻引擎。来源级许可元数据不齐全，缺失时显式 unknown，不能推断为商业使用许可 |
| 金融目录 | `financial-data-asset` 12 条，字段为 dataset_code、asset_name、fields、last_observation_date、last_collected_at、history_granularity、storage_location、read_tool 等；read_tool 目前为 web_search | `chatchat-runtime-market/.../storage/FinancialAssetCatalogService.java` 的 search；`chatchat-mcp-server/.../market/McpMarketAssetCatalogIndex.java` | OpenSearch 是金融数据发现目录，不能误认为已存全部行情观测 |
| 金融观测 | 目录映射到 `financial-market.<dataset>`；包括 market_quote_daily、index_valuation_daily、margin_trade_daily 等 12 个数据集。目录声明 DAILY_7D_WEEKLY，不保证连续逐日历史；最新日期按数据集不同，月度数据可明显早于今日 | `chatchat-runtime-market/.../storage/FinancialDataStore.java` 的 catalog/query，已有参数化字段白名单、并发隔离、查询超时、冷热层合并去重；`chatchat-mcp-server/.../market/SnapshotFinancialReadOperations.java`、FinancialQueryPoolConfiguration | 复用现有存储读路径，既不复制行情到新索引，也不把快照当实时行情；动态字段、单位、复权口径须根据目录/原记录输出，缺失须声明 |

完整 mappings、条目数量和采样字段见 [索引审查](runtime-resource-exploration-20261104-evidence/index-audit.json)；12 个数据集的实际字段、截止时间及冷热策略见 [金融目录](runtime-resource-exploration-20261104-evidence/financial-datasets.json)。这些证据不包含原文正文、账号令牌或连接凭据。索引数量随采集持续变化。

## Agent 调用、权限和治理

`chatchat-api/.../controller/agent/AgentWorkshopController.java` 的 createAgent/updateAgent/validateResourceBindings 已校验文档和工具绑定，并调用 SkillCatalogService 保存 Agent；SkillDefinition.workflowConfig 已支持任意 JSON 扩展并保留版本快照，无需新建 Agent 配置表。现有 AgentWorkshopView.vue 与对应 JS 是唯一配置入口，应在原页面增加轻量资源授权区域。

`chatchat-chat/.../skills/runtime/DatabaseSkillExecutionScopeService.java` 的 resolve 已实现用户/角色授权与 Agent 文档绑定交集，空绑定不是全库。`chatchat-enterprise/.../service/ResourceAuthorizationService.java` 的 evaluate 已从数据库读取有效用户、角色、到期时间和 DENY 规则，不能接受模型声明的角色。现有 ResourceGrant 可按逻辑 ID 增加数据资源类型授权，不能以勾选 Agent 配置替代用户授权。

文档 Cline 链路为 RoleChatModeHandler → ClineContextAnswerService → CodingAnalysisService → 同一 Node worker → 签名 ClineModelCallbackController → CodingKnowledgeService。工具预算、任务租约、操作 journal、Evidence 和报告引用校验已有实现；KnowledgeDisclosureGuard 保护后续模型回合、历史 JSON、SSE 和产物读取。金融与新闻新增披露必须接入同一失效机制，不能只在首次检索核权。

新闻与金融已有 MCP 搜索桥接：`chatchat-mcp-server/.../news/tool/InternalFinancialDataSearchExecutor.java` 会将 web_search 原始查询交给 FinancialEnrichmentService；`FinancialDataMcpToolProvider` 当前不是 Spring component。该桥接按固定父查询执行，不能直接宣称满足本任务要求的 Cline 自主选择、改写及深入读取。应保留旧桥接兼容，通过受控读取适配器复用底层服务，让 Cline 独立选择已授权资源。

API 已有 `controller/mcp/McpControlPlaneClient.java` 固定来源、服务端凭据、超时和错误处理；可用于调用 MCP 上的受控只读适配器，凭据不能传给 Cline。新适配器只接收允许的逻辑操作与业务条件，不允许用户输入索引、DSL、SQL、角色、租户或服务地址。

`chatchat-agents/.../orchestration/AgentOrchestrationEngine.java` 确有 tool_result_review 模型阶段；已上线的 Cline 知识工具回调不经过这一阶段。新增资源工具应沿用签名回调的确定性校验路径，不能回接旧编排审查循环，也不修改其他业务模板的审查机制。

Skills 复用 `coding-agent-node/src/skills.js`、Java BuiltinCodingSkills 与已有 skill_read 渐进加载。原生知识技能已存在；金融新闻、金融市场与证据问答技能按资源任务可见，不应成为所有 Coding Agent 的常驻上下文。微软技能的许可证和服务依赖需在 P1 实施时核验；依赖 Azure 的技能不得冒充 OpenSearch 适配。

## 已交付代码与本轮整改清单

此前已提交的实际逐文件改动见 [实际代码改动清单](runtime-resource-exploration-20261104-evidence/existing-code-changes.json)，该清单是 `git diff --name-status 743a01304..e1b998792` 的结果，不代表扩展任务已完成。此前 50 题为 42 成功、7 部分结果、1 超时，P95 168.961 秒；不能作为新闻、金融或全部语义正确性验收。

| 顺序 | 需要新增/修改的代码 | 要达到的行为与验证 |
| --- | --- | --- |
| P0-1 | common 增加资源策略/作用域契约；chat 在现有 SkillCatalog/SkillExecutionScope 周围增加资源授权解析；AgentWorkshopController 和现有 Vue/JS 扩展 | 逻辑资源 ID 持久化到 workflowConfig；仅展示可分配资源；保存时校验；实时取用户 ∩ Agent ∩ 任务权限；授权变更沿用快照/审计 |
| P0-2 | MCP 新增受控读取适配器，内部委托 NewsDocumentStore、FinancialAssetCatalogService、FinancialDataStore；API 增加固定来源调用适配 | 复用查询引擎，独立发现/读取新闻与金融；有界参数、超时、输出及分页；没有新采集器或第二套存储 |
| P0-3 | 扩展 CodingAnalysisService、ClineModelCallbackController、Node knowledge/resource tools、ClineToolProfiles 与披露/证据校验 | 启动只暴露授权工具；模型自主多轮调用；回合和引用读取再次检查资源有效性；新增资源也禁止历史绕过 |
| P1-1 | BuiltinCodingSkills、Node skills 及原生 SKILL.md | 按需新闻/金融/证据分析指导；保留微软原文与依赖边界 |
| P1-2 | 原消息进度、引用弹窗及报告来源结构 | 合并探索进度，保留新闻发布/采集时间与金融截止时间、口径、来源；复用既有报告渲染 |
| P1/P2 | 专项测试、真实 Agent 验证脚本、性能证据与实施总结 | 覆盖 AT-01～AT-14、跨资源分析、撤权、无数据和来源冲突；分别报告执行成功、引用可定位及结论支持，禁止以 Mock 替代端到端 |

## 兼容性与实施风险

旧 Agent 未配置新策略时应保留既有显式文档绑定语义；新增金融/新闻资源默认关闭，不能自动赋予现有用户或 Agent。资源策略若选择“仅用户明确要求”，须由每次任务的可信入口显式选择并在服务端检查，不能让模型自行声称获得同意。撤权使旧结果暂不可读的保守行为须继续说明。

不同来源新闻的证券字段不统一；需优先使用已映射字段和证券代码，不把全文关键词匹配伪称为完整证券关联。市场目录有些指标缺单位/复权元数据；返回未知并限制结论。冷热历史可能稀疏，不能补造交易日。存在外部商业许可风险的资源必须依授权注册策略限制，不因公开 URL 自动放行。

先提交本报告和现有实际改动清单，再依次实现 P0、P1、P2；最终按实际 Agent 的资源获取与分析结果验收。目标环境的上一轮知识 P0 发布保持运行，后续部署须保留备份并完成对应端到端验证。
