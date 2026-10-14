# SQL Studio 架构修订与 Schema Explorer 第一阶段

本方案取代第 19 号记录中 SQL 请求无条件执行代码解释计划的设计。仅调整 SQL Studio 应用层，不修改 Runtime OS 通用规划、授权和执行协议。

## 实际集成点与计划门槛

SqlStudioService.prepare 当前在创建每个 Generation 前调用 StudioCodePlanService。这是应用添加的固定模型调用，并非生成 SQL 的必需业务契约，改为 planRequested=true 时显式启用；默认不调用该计划服务。既有 Agent 对可执行工具计划的合法性校验、权限治理、确认和证据要求保持原实现。工程归属、逻辑绑定、当前授权、快照一致性、文件版本和接受后保存属于必须保留的业务边界。

人工 Schema 浏览直接复用现有 SqlStudioMetadataController -> SqlStudioMetadataService -> SqlStudioMetadataGateway -> McpRuntimeKernel -> sql_schema_context_query -> SQL_METADATA_DISCOVERY。人工点击的操作由界面选择；未来模型探索由模型选择，不将人工浏览过程编译成固定模型工具顺序。

## 源码修改清单

| 位置 | 最小调整 |
| --- | --- |
| SqlStudioService / SQL 集成测试 | SQL 计划改为显式可选；回归默认生成及失败阻断 |
| Metadata Gateway / Controller | 提供已注册、支持 v2 的 MCP 服务安全投影，不暴露连接凭据 |
| SqlStudioMetadataService / SqlMetadataDiscoveryService | 接受 includeColumns；目录可省略字段；明确证据类别，按完整物理身份过滤字段 |
| sqlStudioApi.js | 复用现有 API，增加元数据客户端 |
| sqlMetadataSession.js | 内存缓存、并发去重、绑定世代、每次使用前重新检查授权快照 |
| sqlSchemaCompletion.js | 复用 dt-sql-parser，按语法位置和作用域补全，不访问数据库 |
| SqlSchemaExplorer.vue | 服务/资产/命名空间选择、分页搜索、按需展开字段、来源标记 |
| SqlStudioView / SqlEditorPane | 接入 Explorer 与提供器；保留原 Model、文件树、保存、Diff |

## 接口契约

根路径沿用 /api/v1/data-science/sql/projects/{projectId}/metadata。新增 GET /providers 返回已注册服务的 serviceId/name；该目录不授予任何资产访问权。GET /assets 返回当前授权资产分页。POST /contexts 保存 SOURCE 逻辑资产与 database/schema 绑定（database 对应当前索引中的 Catalog/数据库）；GET /contexts/{id} 恢复时仍重新检查权限。

POST /contexts/{id}/operations 继续接收 bindingId/arguments。arguments.operation 仅 LIST_NAMESPACES、SEARCH、DESCRIBE；增补 includeColumns:boolean。SEARCH includeColumns=false 返回轻量目录；DESCRIBE 按 database/schema/tableName 精确读取。页大小最多 100，第二页起携带 expectedSnapshotId。客户端不可替换服务器绑定中的资产、服务或扩大命名空间。每次操作由既有授权服务检查当前权限。

结果新增 evidenceClass=REGISTERED_METADATA、verificationStatus=NOT_VERIFIED_LIVE；来源、采集时间、快照和真实字段均来自原有采集索引，不将采集快照称为本轮联机核验通过。未来证据类别预留 VERIFIED_SCHEMA、INFERRED_RELATION、UNCONFIRMED_BUSINESS_RULE，不能从猜测字段升级为 VERIFIED_SCHEMA。

## 缓存与补全

缓存仅驻留内存，分区包含身份、工程、发现绑定、服务、资产、完整 Catalog/Schema 和快照。缓存的是元数据投影，方言解析和标识符引用随当前编辑器方言重新计算，不缓存生成 SQL。每次缓存使用及点击插入前，先通过新的轻量 SEARCH 检查当前授权与授权投影快照；不把缓存命中当作授权。并发检查可合并，完成后的授权检查不持久缓存。任一失败、快照变化、身份或绑定切换会清空缓存；异步结果通过世代和 Model/version 检查丢弃。失焦/页面隐藏清除可复用元数据；重新激活重新校验。不保证客户端在没有通知或新请求时获知外部撤权，界面会在焦点恢复及后续操作重新核验。

普通补全使用 dt-sql-parser 的光标建议和实体作用域，加上当前授权目录；表引用按完整命名空间匹配，歧义时不猜测字段。Spark/Hive/MySQL/PostgreSQL/Trino 使用已有语法类；其他方言明确限制语法能力，标识符引用按方言适配。无连接时保留原关键字/Mapping 补全。SQL 解析结果不作为权限或实库验证证据。

## 下一阶段边界

当前绑定、快照和证据引用作为后续 SQL/ETL 生成输入；保留 Generation、候选 files[]、Diff 和 expectedVersion。生成、静态验证、EXPLAIN、查询执行分别建模；本阶段不开放执行，不新增 Agent/数据库驱动，不引入 Electron 或 React。

## 验收

覆盖分页目录、按需字段、同名表隔离、别名与不同方言、重复请求、切换后迟到结果、撤权后缓存拒绝、快照变更、平台注册证据标记、可选计划和原编辑/保存/Diff 回归。模拟 MCP/索引测试与真实服务器验收分别记录，不将夹具测试声称为实库通过。

## 本阶段具体接口示例

所有路径以前述工程 metadata 根路径为前缀，沿用身份与工作空间请求头。浏览器不提交 tenantId、userId、数据库凭据或连接字符串。

```json
POST /contexts
{
  "purpose": "QUERY",
  "targetDialect": "trino",
  "bindings": [{"serviceId":"registered-service","assetRef":"authorized-source","role":"SOURCE","database":"catalog","schema":"public"}]
}
```

返回既有 Context：discoveryId、projectId、purpose、targetDialect、bindings[]、state。bindings[] 包含服务器生成的 bindingId、serviceId、assetRef、role、database、schema、sourceDialect。数据库沿用 ds_sql_metadata_context，无新增表或连接注册体系。当前 UI 保存服务器绑定并在组件存续期间保留；隐藏侧栏不丢失绑定，刷新整个页面后需重新选择数据源，不自动恢复旧的授权数据。

```json
POST /contexts/{discoveryId}/operations
{
  "bindingId":"server-binding-id",
  "arguments":{"operation":"SEARCH","page":1,"pageSize":50,"includeColumns":false}
}
```

应用层注入 contractVersion=sql-studio.metadata.v2、assetId 及被绑定的命名空间，经现有 McpRuntimeKernel 调用 sql_schema_context_query。返回 serviceId 和 metadata；metadata 包含 operation、status、snapshotId、items、page、pageSize、totalMatched、hasMore、provenance、doesNotExecuteSql、evidenceClass、verificationStatus。表对象的身份为 asset + database + schema + tableName，tableRef 为其哈希，不以裸表名作键。

DESCRIBE 必须提交 tableName；跨命名空间同名时补全拒绝猜测，用户可明确限定引用。includeColumns 默认兼容旧行为；false 仅压缩响应，快照仍覆盖可见字段，撤销字段授权或修改结构会改变版本。NEEDS_REFRESH 不会当成空库成功。403 清除授权缓存；502、快照变化或网络异常同样拒绝复用旧结果，用户刷新重试。不对错误请求自动换数据源或提升权限。

## 影响与限制

- 修改只涉及 SQL Studio 应用、元数据安全投影和前端提供器；Runtime 通用核心、Python 计划行为、现有模型入口与数据库驱动均未修改。
- InterpretationPlanValidator.validateAnalysisPlan 校验用户显式请求的代码分析计划；validate 等现有工具/DAG/执行策略校验仍服务其原有 Runtime 契约，未被删除或绕过。SqlStudioService 默认不再额外调用模型制定代码计划。
- Explorer 与文件树共用左栏；Monaco 继续使用原 Model 和版本、编辑事件、保存 API、只读 Diff 与用户确认应用。插入标识符使用 executeEdits 与 undo stop，保留撤销能力。
- 现有采集索引存在采集数量和刷新策略限制，本阶段不扩大采集范围；页面只展示已有授权快照，采集失败提示使用现有管理能力刷新。缓存命中前内容哈希校验依赖服务端遍历授权投影，大型目录后续可用既有权限修订号与索引版本优化，不能省略授权边界。
- Oracle/T-SQL 仅使用 GenericSQL 的有限语法及对应引用规则，不能声称具备完整方言语义。CTE/派生表的推导字段、复杂大小写折叠与业务关联推断不作为本轮已完成能力；未明确匹配的物理对象不提供猜测字段。
- 当前解析器无法完整提供 CTE 声明绑定，因此 WITH 语句中的未限定表引用保守地不加载物理表字段，防止 CTE 与实表同名时错误补全；明确限定的物理表引用仍可使用元数据。

## 后续接口预留及实施顺序

以下为设计预留，尚未新增这些生成/验证 API：

1. 模型自主发现：沿用 Context 和 Operation 的独立调用，Runtime 记录现有 invocation/event 证据；模型自行决定工具、表、字段和调用次序，不调用前端浏览脚本作为模型工作流。
2. 证据引用：引用 {serviceId, assetRef, database, schema, tableName, snapshotId, evidenceClass, collectedAt, sourceUpdatedAt, invocationId}。VERIFIED_SCHEMA 须来自独立成功核验及其证据，不能由解析成功或注册快照自动升级；推断关系和业务假设分别使用 INFERRED_RELATION、UNCONFIRMED_BUSINESS_RULE。
3. 生成任务：扩展既有 Generate 的可选 discoveryId/bindingIds/evidenceRefs，由服务器验证归属、实时授权和版本再装配上下文，复用现有 Agent/模型入口；不新增 Agent Runtime。
4. 静态校验、联机 Schema 校验、EXPLAIN、QUERY 各有独立 operation/task、输入摘要与授权结果；错误反馈携带 diagnostics/evidenceRefs，不触发自动业务执行。字段未确认与语法正确是两个状态。
5. 候选方案：沿用 Generation 的 files[]、baseVersions、Diff 与 apply；扩展 proposalId/evidenceRefs/assumptions，每个文件保留 path/content/expectedVersion。SQL/ETL 多文件输出经用户接受再原子保存，拒绝不写文件。

第一阶段先完成浏览与补全及本地回归；第二阶段接入模型自主发现和证据引用；第三阶段独立实现 Schema 校验与错误反馈，再分别实现有授权确认的 EXPLAIN/查询；第四阶段完善 ETL Mapping/案例、多文件与业务假设。每阶段保留生成和执行隔离，并用普通用户和拒绝用户进行真实环境验证。

## 本轮测试结果（2026-10-14）

| 范围 | 结果 | 证据边界 |
| --- | --- | --- |
| SqlStudioIntegrationTest | 29 通过 | Spring/H2：默认与可选计划、生成隔离、保存、版本冲突、只读解释、多文件应用、工程及元数据归属 |
| SqlStudioMetadataServiceTest / GatewayTest | 7 + 4 通过 | 持久化绑定、撤权、禁止扩大命名空间、轻量参数及安全服务目录；MCP kernel 为 mock |
| SqlMetadataDiscoveryWorkflowTest / AuthorizationProjectionTest | 11 + 4 通过 | 授权前置、分页、内容快照、轻量目录、同名表字段隔离、未联机核验证据；索引为夹具 |
| 前端组件与补全/缓存 | 15 + 7 + 5 通过 | 真实 dt-sql-parser、Vue/jsdom、请求去重、撤权、快照变更、身份切换、等价身份重渲染、CTE 同名保护 |
| 既有前端回归 | 39 通过 | SQL 状态、AI 操作、代码计划、共享智能编辑与 Python Studio 状态 |
| Chromium / Monaco | 通过 | scripts/test-sql-studio.mjs：实际编辑器的别名字段建议、目录字段按需读取、撤权缓存清除、编辑保存、Diff/多文件审查、取消任务与界面尺寸稳定；HTTP 夹具 |
| Vite 生产构建 | 通过 | 输出 target/schema-explorer-web，未覆盖部署静态目录；保留既有 chunk 体积提示 |

后端命令：Java 17，Maven -pl chatchat-api,chatchat-mcp-server -am test -Dfrontend.skip=true -Dfrontend.install.skip=true -Dtest=SqlStudioIntegrationTest,SqlStudioMetadataServiceTest,SqlStudioMetadataGatewayTest,SqlMetadataDiscoveryWorkflowTest,SqlMetadataAuthorizationProjectionTest -Dsurefire.failIfNoSpecifiedTests=false。前端新增测试纳入 npm run test:sql-studio，额外运行 codeIntelligence.test.js 和 DataScienceView.test.js。构建使用 npm run build -- --outDir ../../target/schema-explorer-web --emptyOutDir。

浏览器截图：target/sql-studio-ide-audit/sql-schema-explorer.png、sql-review.png。真实服务器发布、普通用户实库跨方言验证和 Schema DDL 后采集刷新尚未执行，不能把本轮 55 项后端、66 项前端及浏览器夹具结果等同于这些验收完成。未连接新数据库，也未运行用户业务 SQL。
