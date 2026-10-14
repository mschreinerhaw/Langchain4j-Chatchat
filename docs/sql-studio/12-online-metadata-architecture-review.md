# SQL Studio 联机元数据架构审查报告

审查日期：2026 年 10 月 14 日。审查对象为当前工作区源码，包含尚未提交的 SQL Studio 模式与变更审查改动。本次交付为代码审查与设计，十项联机能力的真实环境验收列入实施计划。

项目已有数据源维护、元数据索引、MCP 发布与鉴权、Runtime 模型规划和工具执行能力。建设重点是把这些能力接入 SQL Studio，补齐元数据授权投影、关系结构、分页、版本证据和生成执行隔离。不能把现有通用结构化数据查询流水线直接当作元数据生成工作流。

配套交付：[技术方案](13-online-metadata-design.md)、[MCP 契约](14-online-metadata-mcp-contract.md)、[工作流与应用接口契约](15-online-metadata-workflow-contract.md)、[实施与验收计划](16-online-metadata-implementation-plan.md)。以下“现有”表示源码已有，“拟新增”表示设计内容。

## 当前 SQL Studio 调用链路

```text
SqlAssistantPanel.vue
  → SqlStudioView.js generate()
  → sqlStudioApi.prepareSqlGeneration()
  → POST /api/v1/data-science/sql/projects/{projectId}/generations
  → SqlStudioController.prepare()
  → SqlStudioService.prepare() 固定文件与 Mapping 上下文、保存 Generation
  → 前端 submitAgentTask(prepared.taskRequest)
  → POST /api/v1/agent/tasks
  → AgentTaskService → InteractionOrchestrationService
  → llm_chat 或 agent_chat
  → bindSqlTask() 将任务绑定 Generation
  → GET generations/{generationId} 轮询任务和事件
  → SqlOutputBundle 校验结果包 → 差异审查 → apply() 原子保存文件版本
```

入口见 [SqlStudioController](../../chatchat-api/src/main/java/com/chatchat/api/controller/datascience/SqlStudioController.java)、[SqlStudioService](../../chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioService.java)、[sqlStudioApi](../../chatchat-api/web-app/src/services/sqlStudioApi.js)。

`SqlStudioService.prepare()` 当前明确要求不连接数据库、不执行 SQL。没有选择 Agent 时进入 `llm_chat`；`LlmChatModeHandler` 直接调用模型，不提供自主 MCP 探索循环。选择 Agent 后才进入已有 `agent_chat` 工具规划链路。仅在提示词中要求“先查元数据”，不能把默认无工具入口升级为联机能力。

当前生成参数包括操作类型、方言、Mapping 文档或工程文件、历史 SQL 引用、Agent 或模型；没有数据源绑定、元数据快照或探索会话。每轮创建独立 Runtime 会话，`historyWindow=0`，前端将少量历史文本拼入当前请求。这能隔离旧对话记忆，但不能保留可靠的跨轮元数据证据集合。续问必须补上服务端发现会话和证据引用，不能靠放开所有历史记忆解决。

现有多文件输出、版本冲突检测、工程归属校验、文件树、Monaco、只读解释和变更审查可以继续使用。`GenerationView.validation` 当前固定为 `NOT_RUN`；文件包校验和浏览器语法诊断不是数据库字段校验。

## Runtime 工作流与模型职责

| 能力 | 现有代码及行为 | 本任务处理方式 |
| --- | --- | --- |
| 通用工作流注册 | [WorkflowRuntime](../../chatchat-common/src/main/java/com/chatchat/common/runtime/workflow/WorkflowRuntime.java) 以字符串注册类型，提供 register、start、find、cancel | 复用端口，不为一个 SQL 应用新增核心工作流枚举 |
| 注册契约 | `WorkflowRegistration<I,O>` 绑定输入、输出与 `WorkflowDefinition.execute(input,context)` | 有持久异步操作需求时沿用契约 |
| 执行适配器 | `LocalWorkflowRuntime`；`TemporalWorkflowRuntime` 和 `TemporalWorkflowDefinitionRegistry`；Temporal 配置按 provider 条件启用 | 区分本地执行与持久化执行保证，不假定部署已启用 Temporal |
| Agent 生命周期 | `DefaultAgentRuntime` 注册现有 Agent 工作流，复用 RunStore、KernelScope 与任务生命周期 | 继续调用现有 Agent，模型规划留在原有编排层 |
| MCP 执行治理 | [McpExecutionWorkflow](../../chatchat-runtime-mcp/src/main/java/com/chatchat/runtime/mcp/workflow/McpExecutionWorkflow.java)，ID 为 `execute.mcp-tool.v1` | 复用契约预检、目录发现、路由、扩展、结果治理和审计 |
| MCP 领域工作流 | [SqlQueryExecutionWorkflow](../../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/execution/workflow/SqlQueryExecutionWorkflow.java) 继承 `AbstractStagedExecutionWorkflow`，由工具发布端委派执行 | 元数据工作流采用同一领域执行扩展方式，不复制 Agent 循环 |
| 工具工作流角色 | [ToolWorkflowContract](../../chatchat-common/src/main/java/com/chatchat/common/tool/ToolWorkflowContract.java) 使用发布者声明，角色已有 `DIRECT` 等 | 元数据操作声明 DIRECT；不伪装为必须执行 SQL 的模板执行角色 |
| 结构化数据分析 | [StructuredDataAnalysisWorkflow](../../chatchat-agents/src/main/java/com/chatchat/agents/runtime/analysis/workflow/StructuredDataAnalysisWorkflow.java) 默认包含 `SQL_PLAN`、`QUERY_EXECUTE` 等操作 | 不直接作为生成路径，防止生成自动进入查询执行 |

`SQL_METADATA_DISCOVERY` 设计为领域执行工作流，处理模型已经选择的一次元数据操作。逻辑上的探索会话可以包含任意次数、任意顺序的调用；Runtime 不负责决定查哪张表、如何 JOIN 或采用何种增量加载规则。工作流、Agent 任务和前端“新建会话”是不同标识，不能混成一个 sessionId。

## MCP Gateway 与已发布元数据能力

[McpGatewayClient](../../chatchat-integration/src/main/java/com/chatchat/integration/mcp/service/transport/McpGatewayClient.java) 已有 streamable HTTP、SSE、STDIO proxy 和兼容 REST 传输，并支持工具目录变化通知。服务目录、注册桥接和契约审计已有实现；SQL Studio 不应自行建立 JDBC 连接或新的 MCP 客户端。

[SqlMcpToolPublisher](../../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/tool/SqlMcpToolPublisher.java) 的有效发布入口为 `contribute()`：

| 工具名 | 源码状态 | 可复用范围与限制 |
| --- | --- | --- |
| `sql_schema_context_query` | 已发布 | 只读物理元数据检索；支持 query、tableName、assetId/assetName、逻辑 executionContext 和字段信息；每次 catalog/detail 最多 5 张表 |
| `sql_metadata_search` | 内部能力，列入 retired 名称 | 底层搜索服务可复用；不能将其当作现成公开工具交给模型 |
| `sql_query_execute` | `contribute()` 中实际发布 | 已有查询执行网关，支持路由、只读 SQL、模板和多语句只读脚本；元数据发现默认工具集合必须排除它 |
| `enterprise_metadata_search` | 独立发布者已存在 | 企业标准字段、术语及业务定义的检索来源；不能用业务术语命中替代真实物理字段存在性证明 |

`retiredToolNames()` 中也包含执行网关名称，用于旧发布清理；不能只据此认定网关已经下线，应结合 `contribute()` 判断。

现有桥接返回表目录、详细字段、来源、排名、路由和执行绑定等信息，但没有完整的授权 Schema 树分页、关系查询、指定快照续查及用户可见的新鲜度协议。每次 Top 5 是载荷限制，不能视为“只有 5 张授权表”，也不能作为固定探索终止条件。

## 数据源管理与鉴权

项目存在两个既有登记模型，不能假定它们的 ID 或凭据互通：

1. 企业后台 `data_source`，由 `DataSourceConfig`、`DataSourceConfigRepository`、`EnterpriseAdminController /data-sources` 和 `EnterpriseAdminService` 管理，包含 tenant 与 passwordCipher。
2. MCP 运行侧 `mcp_sql_datasource`，由 [SqlDatasourceConfigService](../../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/datasource/SqlDatasourceConfigService.java) 和 `SqlAdminController /api/v1/sql/datasources` 管理，包含数据库类型、环境、元数据范围、刷新策略、表字段限制和执行目标绑定；另有元数据资产登记及逻辑执行目标路由。

SQL Studio 应通过既有 MCP 运行侧逻辑资产、执行目标及权限投影绑定数据源。企业管理记录若未绑定 MCP 逻辑资产，应返回“未完成执行目标绑定”，不能另写同步框架、复制连接配置或默认两边同名即同源。管理 API 返回的连接配置不应直接用作普通开发者的数据源下拉选项。

已有安全链路包括：

- API 的 workspace 可用性、对象租户和归属校验；文档使用 `SearchPermissionContext`。
- MCP 的可信调用身份、License、角色工具和 Scope 权限、执行目标路由及工具运行确认策略。
- [McpAuthorizationService.authorize()](../../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/authorization/McpAuthorizationService.java) 在执行时拉取新鲜权限快照；缺失、过期、不可用或拒绝均不放行。发现缓存不能当执行授权。
- `DatasourceMetadataSearchService.visibleObjects()` 已有 allowedTables、sensitiveTables 和 sensitiveFields 过滤，可复用，不另建 ACL。

**需要闭合的路径差异：**公开 Schema 工具目前调用 `SqlMetadataSearchService.search()`；该类候选数据源主要按请求中的资产、环境和类型过滤，结果字段由索引直接组装。它不等同于 `DatasourceMetadataSearchService.visibleObjects()` 路径。工具入口授权已经存在，但本次审查尚不能据此确认每条候选表字段均经过调用者的资源可见性投影。P1 必须沿原权限服务补齐或证明这一过滤链路，覆盖空资产检索、越权 assetId、跨 Schema、敏感字段和缓存命中等负向用例。

## 元数据目录与刷新能力

现有 [MetadataIndexService](../../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/metadata/MetadataIndexService.java) 已有内存缓存、可选 RocksDB 持久快照、按配置兼容性加载、刷新服务和刷新事件。`MetadataRefreshScheduler` 支持启用资产的定时刷新，后台也已有手动刷新入口。系统元数据查询提供者及 Trino、图数据库、搜索引擎等 Collector 可复用；不新建另一套连接池和采集框架。

存在以下语义缺口：

- `MetadataColumn` 有 columnKey，但没有统一的有序复合主键、外键两端字段配对、约束身份及业务关系证据契约。
- `MetadataIndex.refreshedAtMs` 是采集时间，不是数据库对象更新时间；刷新未发生时，旧快照不能声明为实时 Schema。
- `configurationFingerprint` 用于配置兼容性，输入还包含连接和安全配置，不能作为公开 Schema 内容版本返回给模型。
- `MetadataObject.id()` 是对象身份摘要，不是对象内容版本。
- `MetadataIndex.withChanges()` 当前记录 CHANGED、REMOVED 并截断历史，未提供完整的 ADDED 增量协议；无法保证从任意旧游标完整追溯。
- Schema/字段大小写、catalog/schema 多级标识和模糊同名必须保持，不能在新浏览器中只用 tableName 作为唯一键。

## SQL 方言 校验与执行

| 能力 | 现有实现 | 建设边界 |
| --- | --- | --- |
| 方言选择 | SQL Studio 已有 spark、hive、postgresql、mysql、oracle、tsql、trino、ansi | 区分源库方言、目标库方言及文件方言；UI 选择不是数据库类型证明 |
| 语法诊断 | [sqlDiagnostics.js](../../chatchat-api/web-app/src/js/editor/sqlDiagnostics.js) 按方言懒加载浏览器 parser；当前有五种方言构造器 | 继续使用；未支持方言或解析异常不能显示为“已通过联机验证” |
| 字段补全 | Monaco 和 `mappingSchemaColumns` | 接入授权元数据字段，保留证据版本及大小写 |
| 安全校验 | [SqlSafetyService](../../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/execution/SqlSafetyService.java) 限制 SELECT、SHOW、DESCRIBE、EXPLAIN，并防止部分执行副作用 | 可复用执行安全边界；它不是 SQL 名称解析器或 Schema 字段校验器 |
| DDL 与标准比对 | `MetadataGovernanceAnalysisService` 已使用 MCP 模块现有 JSqlParser 依赖解析 DDL、注释字段和比对企业标准 | 可复用解析与标准证据；不能宣称已支持任意 SELECT、JOIN、CTE 的联机字段解析；旧治理工具名也不能当作当前公开工具 |
| 查询执行 | `SqlQueryExecutionWorkflow`、`SqlQueryExecuteService`、`SqlScriptExecuteService`，有超时、行数和只读连接配置 | 仅放在独立授权执行入口，不从生成工作流自动调用 |
| EXPLAIN | 现有只读网关允许符合安全规则的 EXPLAIN | 复用后仍须执行权限与本次确认；不默认放行 ANALYZE 或其他会执行查询的变体 |
| ETL 写入 | Studio 可生成文件；现有只读网关拒绝 INSERT、MERGE、DDL 等 | 可以生成和静态校验 ETL，不能因此宣称可执行写入或绕开既有拒绝 |

## 限制模型探索的现有逻辑

| 位置 | 审查结果 | 拟调整范围 |
| --- | --- | --- |
| `SqlStudioService.prepare()` | 禁联机提示、默认无工具模式、每轮独立会话 | 保留离线模式；新增联机应用上下文与只读工具策略 |
| `SqlMcpToolPublisher` | Top 5 无分页；执行工具描述包含特定 MySQL 发现模板和先定位后读字段的指导 | 保持旧兼容入口；新元数据协议支持游标与精确对象查询，不要求模型按模板先后调用 |
| `SqlMetadataSearchService.expandMetadataSearchQuery()` | 内置融资融券、客户、证券等业务同义词扩展 | 作为检索辅助审查，不能认作业务映射依据；新模式支持物理名精确检索及受治理的术语目录，不改全局排名规则来替代模型决策 |
| `AgentToolPolicyResolver` 与 `AgentPlanner` | 配置的 mandatoryTools 会形成必需工具及顺序约束 | 元数据应用配置不提供固定 mandatoryTools 序列；保留全局授权和其他业务工作流约束 |
| `StructuredDataAnalysisWorkflow` | 预置查询执行阶段 | 复用治理基础，不直接复用这条业务流水线 |
| 数据源、模板和只读限制 | 安全与兼容治理 | 保留；模型自主性不能扩大授权、放开写入或凭空指定连接目标 |

## 复用清单与缺口清单

直接复用：现有两个数据源登记体系及逻辑资产绑定、MCP 目录与 Gateway、RBAC 与授权快照、Runtime Agent 规划及工具执行、元数据采集与缓存、文档权限端口、SQL 文件与版本、Monaco 和差异审查、事件和证据存储、只读查询网关。

P1 必须先闭合：逐候选资源可见性、可信绑定与工具集合约束、元数据发现禁止业务数据执行、双登记模型 ID 映射。

后续能力缺口：授权 Schema 分页浏览、主外键和业务关系证据、内容版本与新鲜度、增量与按对象刷新、跨轮元数据证据集合、联机字段解析与诊断、生成结果证据引用、受保护的独立 EXPLAIN/查询确认入口、Mapping/私有 SQL 资产分页检索。

`McpDocumentLibraryReadPort.list()` 当前取第一页 500 条；新资产搜索不能只在这 500 条上做客户端过滤后声称完整检索。

## 拟调整文件与模块

| 模块或文件 | 拟调整内容 | 是否改 Runtime 核心 |
| --- | --- | --- |
| `chatchat-api/.../controller/datascience/SqlStudioController.java` | 应用绑定、发现会话、校验与独立执行接口 | 否 |
| `chatchat-api/.../datascience/sql/SqlStudioService.java`、`SqlOutputBundle.java`、`SqlStudioMigration.java` | 联机上下文、证据引用、结果 v2 兼容和增量持久化 | 否 |
| 拟新增 `SqlStudioMetadataContextService`、`SqlStudioValidationService`、`SqlStudioExecutionService`、应用数据类型 | 应用编排和现有端口适配 | 否 |
| `chatchat-mcp-server/.../sql/tool/SqlMcpToolPublisher.java` | 兼容扩展现有 Schema 工具及发布声明 | 否 |
| 拟新增 `.../sql/metadata/workflow/SqlMetadataDiscoveryWorkflow.java` | `SQL_METADATA_DISCOVERY` 元数据操作治理 | 否，领域扩展 |
| `MetadataIndexService.java`、`MetadataIndex.java`、`MetadataObject.java`、Collectors 与 `DatasourceMetadataSearchService.java` | 有证据的键关系、版本、分页、可见性和增量 | 否 |
| 现有授权服务、执行路由与 MCP 注册适配器 | 优先调用现有接口；仅在 P1 证明缺少资产投影端口时追加窄接口 | 不替换授权体系 |
| 文档检索端口与项目文件应用端口 | Mapping 和案例按权限分页搜索；必要时薄 MCP 发布适配 | 否 |
| `SqlStudioView.vue/js`、`SqlProjectTree.vue/js`、`SqlAssistantPanel.vue/js`、`SqlEditorPane.js`、`sqlStudioApi.js` | Schema 树、选中引用、探索事件、证据与诊断、插入或替换 | 否 |
| 相关测试与新增联机回归脚本 | 见十项验收矩阵 | 否 |

`AgentPlanner`、Kernel、通用工作流枚举、Temporal 核心、全局 MCP 路由和 RBAC 存储不列为默认修改对象。若窄接口验证仍无法满足，应先提交独立缺口证据和变更设计，不能在本任务中直接重构。
