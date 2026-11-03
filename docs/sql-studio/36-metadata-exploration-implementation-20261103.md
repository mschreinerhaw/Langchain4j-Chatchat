# SQL Studio 元数据探索重构与验收

源码审查最初基于 `40b0f121b`，随后同步 `origin/main b764ab22e`。后者只增加已经部署的灵感按钮修复，没有修改本报告的元数据调用链。实现位于 `feature/sql-metadata-exploration-20261103`，保留此前修复。

## 调用链与职责

新请求：`SqlStudioView.generate` → `sqlStudioApi.submitSqlGeneration` → `SqlStudioController.submitCoding` → `SqlStudioService.submitCoding` → `CodingWorkspaceService` → Node `runner.js` / Cline → 签名的 `workspace_metadata` 回调 → `SqlWorkspaceMetadataTools.query` → `SqlStudioMetadataService.operateScoped` → `SqlStudioMetadataGateway` → MCP `sql_schema_context_query` → `SqlMetadataDiscoveryService.discover` → `LuceneMcpSearchService.indexedMetadata` → `OpenSearchMcpSearchService.searchMetadataEvidence`。

只有模型明确选择有原因的 `VERIFY_LIVE_SCHEMA` 才进入 `MetadataIndexService.verifyTable`，通过现有 JDBC 驱动读取单个表的表、字段、主键、导入外键信息。表名模式转义并精确复核，禁止借此做业务查询。原有显式刷新、采集事件及调度机制保留。

| 位置（当前实现） | 行为 |
|---|---|
| SqlStudioService.java:526 prepareExploration | 默认 explore 接入 SQL_WORKSPACE_V2，不增加前置模型规划 |
| SqlStudioService.java:552 previousExplorationContext | 复用同一所有者、发现上下文的历史结构，重新核对授权与版本 |
| SqlStudioService.java:588 sqlExamplesScoped | 按当前租户/所有者检索现有 SQL 文件及历史版本；仅参考，不授权执行 |
| SqlWorkspaceMetadataTools.java:72 query | 逻辑别名复用已有物理工具；任务查询缓存命中前执行轻量 freshness 和授权检查 |
| SqlMetadataDiscoveryService.java:179 | 已授权表引用过滤后的 OpenSearch 搜索；载荷版本不符报告 INDEX_STALE，索引缺失报告 INDEX_MISS |
| SqlMetadataDiscoveryService.java:86 SEARCH_SQL_EXAMPLES | 复用已有模板目录的租户、模板授权、数据源授权与模板白名单，不调用执行器 |
| OpenSearchMcpSearchService.java:951 searchMetadataEvidence | 查询现有 assets-sql-datasource，按数据源、metadata_table 来源、授权 tableRef 过滤，取完整结构载荷 |
| MetadataIndexService.java:43 verifyTable | 单表按需核验，记录原因、连接次数、耗时；不更新全库快照 |
| CodingWorkspaceService.java:299 diagnostics | 归档工具时间、检索来源、版本、缓存命中、回源和候选/约束证据 |
| coding-agent-node/src/runner.js:552 | Cline 自主探索；初始结构是可扩展缓存，不是固定候选表白名单 |

原有静态 SQL 校验继续使用目标方言、真实发现字段及 SQLGlot；缺失对象返回 METADATA_REQUIRED，模型可补充发现并修复。Cline 决定提交结果，Java 校验权限、预算及结构。普通字段查询、已存储 SQL 读取不再套用统计指标定义门槛。没有外键时允许继续寻找字段及关系证据，并明确区分推导等值关联与已验证外键；不将名称相似当作已确认约束。

## 覆盖与限制

物理索引新增完整字段/键/关系 JSON、结构版本、快照版本和采集时间，复用现有资产索引写入器、事件及指定数据源重建服务。活动索引没有向量字段，继续使用已有关键词权重、精确对象过滤和关联字段扩展，没有重建向量索引。视图原始定义尚未采集，明确标记 NOT_COLLECTED。源库实际更新时间未知时报告 UNKNOWN，不能把最近索引写入时间当作源库未变化的证明。

生产目录实际发布 39 个工具；存在 `enterprise_metadata_search`，不存在旧版 `data_query_query`。因此 `search_sql_examples` 通过现有 `sql_schema_context_query` 新增操作复用 `TemplateAssetCatalogService.businessTemplates(false)`，先执行既有租户、TEMPLATE、数据源和模板白名单过滤，再读取选定数据源的可读 SQL 内容、来源与版本；同时检索当前用户拥有的 SQL 文件和历史版本。不调用动态模板执行器，不把参考 SQL 当成物理结构或执行授权。业务文档继续走既有 `enterprise_metadata_search`。

轻量版本核对不访问 OpenSearch 或源库。相同任务、相同参数、相同授权版本命中任务缓存，回源次数归零。权限或版本变更使旧证据失效。跨任务复用现有索引，跨轮结构只在当前授权及版本核对通过后使用。现有周期同步/变更事件保持；不新增数据库 CDC。缓存和权限边界的异常验收使用真实服务测试夹具，不能冒充生产故障注入。

前端默认四阶段：发现相关表与字段、推导表关联关系、生成 SQL、验证 SQL 结构。候选对象和已确认约束数可见，详细调用时间/缓存/回源记录折叠展示。证据不足可交付明确说明，不把无 SQL 的结果标为结构验证通过。

## 验证记录

MCP 相关回归 36 项通过；API 相关回归 90 项通过（包含固定 SQLGlot 30.23.0 和 H2 的真实结构校验），SQL 历史/工具新增测试 57 项通过；最后模板不可用降级及权限测试 16 项通过。Node 47 项、前端 44 项通过；生产 Worker 镜像离线运行相同 47 项测试通过。不同批次包含重叠测试，不相加作独立用例数。

OpenSearch 过期、无外键关系候选、授权撤销、单表 JDBC 模式转义/禁止全表扫描、重复回源抑制、跨轮证据复用、索引 HTTP 请求和完整多字段载荷均有针对性测试。测试没有证明生产 P95 或 10 秒目标。

服务器发布使用原运行镜像与驱动，仅替换目标类、前端资源及 Worker runner；逐条校验所有无关 JAR 条目一致。已有灵感按钮修复保留。发布失败自动恢复旧容器和配置，数据库备份保留在服务器私有目录。早期重试发现工具别名校验及发布清单缺字段，已修复，并将必要字段检查前移到停止服务之前。

实际运行证据、用例结果及耗时见同目录验收附件；静态验证成功始终不等同于在业务数据库运行成功。

## 固定用例实测

完整原始结果见 [首轮任务](metadata-exploration-20261103-evidence/acceptance-result.json)、[复用任务](metadata-exploration-20261103-evidence/followup-result.json)、[生成 SQL](metadata-exploration-20261103-evidence/dataservice_api_by_group.sql) 和 [逐环节计时](metadata-exploration-20261103-evidence/timings.json)。

- 首轮没有 Java 预选业务表。Cline 先读取目标表，继续搜索及描述 `ld_dataservice_group`、`ld_project`。API 表完整字段 33 个，包含真实 `project_id`、`group_id`、`sql_content`；每张表返回 schemaVersion 和 snapshotId，来源 OPENSEARCH。
- 无声明外键，继续探索并交付 API → 分组、API → 项目的两条推导等值关联；结构诊断保留 JOIN_RELATION_UNCONFIRMED。不宣称已证明数据一致性或业务粒度。
- 两轮均交付 `PARSED; FIELDS_CHECKED; SQL_NOT_EXECUTED`；首轮一次静态校验失败后再次校验通过。后轮继承三张结构证据，连续两次同参数 DESCRIBE 的第二次 reusedEvidence=true，回源数 0。权限仍逐次重新核对。
- 另因索引采集时间为 2026-07-30，明确原因后单表 `VERIFY_LIVE_SCHEMA` 成功，返回 33 字段，sourceQueryMs=94、sourceReadCount=1、API 311.46ms；它未覆盖全库、未更新全库快照，也不能据此宣称所有索引都新鲜。[核验记录](metadata-exploration-20261103-evidence/live-verification.json)
- 浏览器显示四阶段、20 个检索候选、0 条已确认约束，以及三张完整表证据与 SQL；页面错误 0。[截图](metadata-exploration-20261103-evidence/sql-studio-acceptance.png)

| 首轮环节 | 次数 | 耗时 ms |
|---|---:|---|
| 模型（MODEL_TIMING，包含包装开销） | 6 | 4427 / 7656 / 11297 / 14181 / 9976 / 19112；合计 66649 |
| OpenSearch | 5 | 8 / 188 / 172 / 7 / 6；合计 381 |
| Java 元数据工具（含权限/网络层，不能与 OS 嵌套时间重复相加） | 6 | 211 / 464 / 572 / 620 / 397 / 428；合计 2692 |
| 校验与提交 | 3 | 667 / 1128 / 1003 |
| 正常探索源库核验 | 0 | 无回源 |
| tool_result_review | 0 | 无额外语义审核调用 |
| 首轮任务服务器端到端 | 1 | 80873；客户端提交至轮询观察完成 83896.32 |

近秩法的小样本观察值：首轮 OS P50/P95=8/188ms（n=5），单次模型调用 P50/P95=9976/19112ms（n=6）；两轮任务服务器端 P50/P95=80873/83059ms（n=2）。单表回源仅一个样本 94ms，不构成有意义的 P95。探索已能完成，但完整任务仍未达到 10 秒优化目标；模型延迟与输出轮数是本次主要耗时。后续优化必须继续保持工具自主选择、字段校验和证据说明，不能通过跳过验证制造低延迟。

## 最终发布核对

完整两轮 Cline 用例在 r1 上执行。r2 仅补齐已有模板目录的工具接入，API/MCP 相关新增回归共 71 项通过（13 个 MCP / 58 个 API），没有改动 Worker 或普通物理元数据流程。r2 上再次通过真实接口读取目标结构及 5 条当前数据源已授权 SQL 示例，并重新浏览首轮结果；页面错误 0。[目录检索记录](metadata-exploration-20261103-evidence/catalog-verification.json)

最终 API/MCP 镜像为 `runtime-os-api:sql-metadata-exploration-20261103-r2` / `runtime-os-mcp:sql-metadata-exploration-20261103-r2`，Worker 保持已验证的 `runtime-os-coding-agent:sql-metadata-exploration-20261103-r1`。工具目录 HEALTHY，39 个工具；会话内容、消息索引数量及无关容器保持一致。[最终部署报告](metadata-exploration-20261103-evidence/catalog-deployment.json)、[产物逐项核对](metadata-exploration-20261103-evidence/catalog-artifact-verification.json)。临时登录文件已清理，服务器私有备份保留用于回滚。下载发布结果时一次 SSH 连接中断，重新连接后核对了部署报告和实际镜像，未重复发布。

仍然明确的能力边界：没有向量的活动索引不启用混合向量搜索；视图定义尚未采集；源库变更感知复用现有事件/周期机制，不新增 CDC；两轮样本不足以给出可信环境 P95。无外键场景真实验收通过，过期版本冲突、授权撤销及精确单表读取边界由针对性测试证明。没有把测试夹具当成生产故障注入。
