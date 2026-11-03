# SQL Studio 元数据探索 P0 源码与运行审查

基线：2026-11-03 fetch 后的 origin/main `40b0f121b1a68c45ac362937e88336dc38c565c2`。独立工作树 `feature/sql-metadata-exploration-20261103`；未混入此前灵感修复。

## 已复现的问题

在已有授权的 LiveData 数据源、MySQL 方言上提交原始固定问题：如何查询 `ld_dataservice_api`，通过关联项目 ID 和组 ID 找到指定分组下的服务 SQL 语句？

服务实际发现 `LiveDOP.ld_dataservice_api` 及 `ld_dataservice_group`，真实字段包含 `project_id`、`group_id`、`sql_content`。最终 Runtime 状态 SUCCESS，但 Studio 的 fieldValidation 为 NOT_PUBLISHED，semanticValidation 为 INSUFFICIENT_BUSINESS_EVIDENCE，blockedSqlProposalCount=1。成功的任务状态不代表已交付 SQL。

这不是“根本没找到目标表”：旧 Java 元数据助手把普通 JOIN 纳入必须提供 businessDefinitions 的派生统计审核，而输入的问题是在读取已存储 SQL。该处确有规则过宽。

## 真实入口与两条不同路径

| 环节 | 文件与方法、基线行号 | 实际行为 |
|---|---|---|
| 默认数据库助手 | web-app/src/js/views/SqlStudioView.js:285 generate | 默认 action=explore，提交 generations 后 submitSqlGeneration |
| Java 准备 | SqlStudioService.java:527 prepareExploration | 保存绑定与生成身份，mode=llm_chat，不设置 SQL_WORKSPACE_V2 |
| Runtime 路由 | chatchat-chat/.../handler/LlmChatModeHandler.java:18 handle | sqlMetadata.supports 优先于 CodingAnswerPort |
| 旧探索循环 | SqlStudioMetadataAssistant.java:87 answer；:139 Turn.execute | Java 自行调用模型，12 次模型/24 次工具上限，模型选择 SEARCH/DESCRIBE 等 |
| Cline 路径 | SqlStudioService.java:610 submitCoding；CodingWorkspaceService.java:102 submit | 只有 codingWorkspaceProfile=SQL_WORKSPACE_V2 的另一条生成路径进入 Cline |
| Cline 工具 | coding-agent-node/src/runner.js:107；SqlWorkspaceMetadataTools.java:53 query | 已有 workspace_metadata/workspace_validate；不是只能使用 Java 固定的种子表 |
| MCP 边界 | SqlStudioMetadataService.java:162 getScoped；:176 operateScoped；SqlStudioMetadataGateway.java:60 invokeScoped | 重查工程所有权、服务/工具授权，调用 sql_schema_context_query，签名可信 scope，不传数据库连接给模型 |
| MCP 发现 | SqlMetadataDiscoveryService.java:33 discover；:140 rank | indexFor 读取已采集快照，投影当前可见表，再 rankScopedMetadata |
| 排序实际实现 | LuceneMcpSearchService.java:437 rankScopedMetadata | 每次创建 ByteBuffersDirectory 临时 Lucene 索引；明确不调用外部 OpenSearch |
| 采集与数据库适配 | MetadataIndexService.java:203 refreshDatasource；:382/:402/:434 目录 SQL；:495/:537 JDBC fallback；:581 imported keys | 显式刷新通过 JDBC/系统目录；常规 discovery 不直接查询源库。此链路未找到 DBeaver 类调用，不将 JDBC 等同于 DBeaver |
| SQL 发布拦截 | SqlStudioMetadataAssistant.java:298 起 final 输出校验 | requireBusinessEvidence=true，解析器识别 JOIN 后进入 businessDefinitions 校验，缺失就移除 files |

默认 explore 的真实调用链为 Java → Java 模型循环 → MCP 元数据工具 → 已采集快照/临时 Lucene；不能把它写成 Java → Cline。另一条现有链路才是 Java → CodingWorkspaceService → Node/Cline → Java 签名回调 → SqlWorkspaceMetadataTools → 同一个 MCP 工具。

## 实测调用与时间

同一次固定问题：客户端等待 41,311.6 ms，服务统计 totalMs=40,464。模型调用分别 4,932 / 8,542 / 21,296 ms，总计 34,770 ms，3 次。元数据工具 4 次；完整逐操作计时从原始事件保留。最后组装校验 1,427 ms。工具结果审核 `tool_result_review` 在该次任务事件中为 0；默认 LLM_CHAT 路由没有经过 AgentOrchestrationEngine 的业务工具审核分支，不能将其认定为本次瓶颈。

相同授权项目的发现接口预检，四个 MySQL 数据源 SEARCH 分别 364.72 / 1,284.97 / 2,166.40 / 7,919.54 ms。这些数据是整个 API 往返，不能冒充 OpenSearch 耗时。此次 v2 discovery 源码不调用 OpenSearch，相关调用次数为 0。固定任务未请求 REFRESH；数据库回源未发生在该工具路径，不能把快照读取称为源库查询。

## 缓存、范围与终止边界

- 旧 Java 助手通过 RuntimeExecutionCheckpointPort 保留会话结构证据与对话，后轮按快照重新验证，不是完全丢失上下文。
- 现有 Cline 工具也持久化 task-local queries/tables/snapshots，但命中缓存仍执行一次 SEARCH 来核对快照；SEARCH 会全量投影和构建临时 Lucene，增加重复工作。
- Cline 的 validate 已把未发现对象报告为 METADATA_REQUIRED/UNDETERMINED，允许后续发现；没有确认“所有表必须属于初始快照”的普遍错误。不能删除这项已有正确行为。
- 旧 Java 助手的最终业务审核和硬工具预算控制终止；Cline 路径由模型发起完成、Java 做权限/结构/预算守卫。重构应统一新 explore 请求到已有 Cline 工作区路径，历史任务保留兼容读取。

## 索引覆盖

已实际读取运行环境 OpenSearch mappings 与文档计数：`chatchat_mcp_assets-sql-datasource` 有 4,432 个文档，其字段覆盖表、名称/注释及检索文本，但没有完整结构 JSON、约束、视图原始定义、结构版本或采集时间的独立存储字段。字段可能部分包含在截断 semanticProfileText 的全文中，不能因此声称可以从索引恢复完整字段或关系。SQL 模板使用已有 database-query-templates、templates 索引，业务文档使用 enterprise_metadata_catalog 系列，职责不同，不合并成一个权限不明的大索引。

环境中 dev/test 部分索引有 knn_vector，当前无前缀的物理元数据索引没有。必须按实际选中的索引判断向量可用性，不能看到其他索引有向量就启用。后续复用现有引擎、索引增量写入与权限边界，增加结构证据载荷；不为混合检索强制重建索引。官方参考：[Hybrid search](https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/)、[Normalization processor](https://docs.opensearch.org/latest/search-plugins/search-pipelines/normalization-processor/)。

后续实施与验收另行记录；本报告不将目标中的 10 秒写成已经达成，也不以单次运行计算可信 P95。
