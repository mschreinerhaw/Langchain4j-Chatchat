# SQL Studio 发布 MCP SQL 模板：P0 源码审查

审查日期：2026-10-28。审查对象为当前工作区源码，Git 基线 `d9f39979d4963c48e00c52af5709e3ba7b5c006c`，包含尚未提交的 SQL Studio 元数据助手改动。本文交付 P0 现状与最小改动边界；不表示 P1—P5 已实现或本功能已在生产验收。

## 1. 关键结论

`SqlTemplateService` 与 `TemplateCapabilityService` 已通过 `TemplateAssetCatalogService` 串联，但尚未形成任务书要求的完整作者发布生命周期。

- `SqlTemplateService` 是 SQL 模板定义、参数渲染和兼容性检查的既有入口，持久化到 `mcp_sql_template`。不是 MCP 能力发布服务。
- `TemplateCapabilityService` 从统一目录读取授权模板与绑定资产，生成 `TEMPLATE_CAPABILITY` 类型的 `mcp_tool_release` 记录，再由 `TemplateCapabilityPublisher` 发布为 MCP 工具。应复用这条能力发布链。
- `ToolReleaseService` 当前通过唯一的 `ControlledTemplateReleaseExecutor` 管理 `CONTROLLED_TEMPLATE`，发布的是按角色固定模板集合的查询能力。其测试委托 `TemplateQueryMcpToolPublisher.preview()`，仅验证模板检索，不能证明 SQL 已在数据库试运行。
- 两种发布共享版本表，但服务逻辑、工具名、授权粒度和执行语义不同。共享表不代表共享完整生命周期。不能让 SQL Studio 同时创建两种发布以拼凑流程。
- 能力发布的现行约定是“依赖变化则拒绝旧能力，重新确认发布”，不是“任意历史版本都能恢复原 SQL”。旧式发布保存的历史定义也是模板键集合，不是 SQL 正文快照。

## 2. 实际调用链

### 2.1 SQL 模板管理与通用查询

```text
MCP 管理接口 /api/v1/sql/templates
  → SqlAdminController
  → SqlTemplateService.save / update / delete
  → SqlTemplateConfigRepository → mcp_sql_template

data_query_query / 既有模板发现
  → CommandTemplateDiscoveryService → SqlTemplateService.listEnabled
  → 既有搜索、模板/资产授权和候选结果
  → 模型选择候选，传 templateId + parameters + 逻辑资产
  → sql_query_execute → SqlQueryExecutionWorkflow
  → TemplateParameterWorkflow / ExecutionTargetRouter
  → SqlQueryExecuteService（单条）或现有 SqlScriptExecuteService（多条）
  → SqlTemplateService.render → 参数编译 → SQL 安全检查
  → 原 JDBC 执行、超时/限行、脱敏、审计
```

`SqlTemplateConfig` 现有字段包括 `code/title/description/sqlTemplate/parameterSchemaJson/riskLevel/category/databaseType/datasourceId/routingLabelsJson/intentSignalsJson/evidencePolicyJson/enabled`。没有草稿、测试、发布状态、SQL 修订版本、作者租户或 Studio 来源关联字段。`code` 为全表唯一，不是租户内唯一。

### 2.2 模板能力发布与调用

```text
API /api/v1/tool-publications/capabilities/templates
  → 登录身份、工作空间及 mcp:publication:read
  → McpControlPlaneClient（固定 MCP 地址与内部凭据）
  → MCP /api/v1/template-capabilities/templates
  → TemplateCallerScope（刷新授权、恢复真实业务身份）
  → TemplateAssetCatalogService.businessTemplates(false)
      → SqlTemplateService.listEnabled
      → TEMPLATE 授权 ∩ 数据源操作授权 ∩ 方言/资产绑定/模板允许清单
      → BoundTemplate（key、Schema、executor、固定 binding、依赖 revision）

API POST /api/v1/tool-publications/capabilities/publications
  → mcp:publication:publish
  → MCP TemplateCapabilityService.publish(key, expectedRevision)
  → mcp_tool_release（kind=TEMPLATE_CAPABILITY，直接 RUNNING）
  → 事务提交后 TemplateCapabilityPublisher 刷新既有 MCP 注册
  → API 创建工具所有者授权、记录审计、刷新 Runtime 工具
  → 显式 Agent 工具绑定
  → 已签名 tenant/user/agent + 当前授权 + revision 复核
  → ToolRegistry.executeEnhancedTool(sql_query_execute)
  → 固定逻辑数据源、固定模板编码、Schema 校验后的参数
  → 现有 SQL 执行能力
```

实际 `templateKey` 为 `sql_datasource:<code>`，绑定能力 key 为 `<templateKey>@<datasourceId>`。能力工具名 `tpl_<logicalId去掉连字符>`；logicalId 包含租户、发布者、绑定 key。同一供应商或同名模板不构成业务身份。

### 2.3 旧式受控查询发布

```text
ToolPublicationsView → API /api/v1/tool-publications
  → 管理员权限 + mcp:publication:read/edit/publish
  → ToolReleaseService
  → DRAFT → VALIDATED → TESTED → RUNNING / OFFLINE / rollback
  → ControlledTemplateReleaseExecutor
      validate → TemplateQueryBindingService.validateDraft
      test     → TemplateQueryMcpToolPublisher.preview（检索，不执行 SQL）
      activate → TemplateQueryBindingService.create/update
  → TemplateQueryMcpToolPublisher（父工具路由、受控模板候选）
```

现有 `ToolPublicationsView` 源码仍使用该管理员入口，测试也显式断言不调用 `/capabilities/`。不能依据较早的页面说明认定当前页面已经统一切换到能力发布。`ToolReleaseService.list` 明确排除 `TEMPLATE_CAPABILITY`；其 publish 扫描也跳过该类型。两者不是可以直接互换的 Controller。

## 3. 能力分类与缺口

| 项目 | 当前真实状态 | 复用或补齐方式 |
| --- | --- | --- |
| SQL 工程文件、历史、并发版本、Monaco、差异保存 | 已实现 | 复用 `SqlStudioService.file/save/versions`、`expectedVersion` 及现有编辑器 |
| SQL Studio 原有静态校验 | 已实现 | 复用 SQLGlot 方言、物理表字段检查及元数据上下文；不等于数据库执行成功 |
| 工程中显式 SQL 模板试执行 | 未形成作者流程 | 新增显式操作入口，进入既有授权 SQL 执行通道；禁止在打开窗口、识别参数时执行 |
| SQL 模板存取、数据源兼容检查 | 已实现 | 复用 `SqlTemplateService`；不能用 create 接口携带现有 id 隐式覆盖模板 |
| 参数 Schema、默认值、类型与缺参校验 | 已实现 | 复用 `TemplateParameterValidator`、`ToolArgumentCompiler`、`TemplateParameterWorkflow` |
| 原 SQL 模板参数语法 | 已实现为 `{{name}}` | 发布界面遵循现有语法；不要把 `:name` 直接当作已支持语法 |
| 未填可选参数自动删筛选条件 | 不支持 | 不增加字符串删条件逻辑；显式提供合法默认值或由作者写明筛选语义 |
| NULL 可选占位符 | 现有渲染不支持 | `safeSqlLiteral(null)` 直接报缺参；不能把 Schema 的 optional 等同于 SQL NULL 支持 |
| SQL 模板草稿/测试/发布生命周期 | 未实现 | `enabled` 不等于发布状态；要在既有 MCP 作者链补齐治理 |
| 模板能力注册、签名身份、模板/资产/Agent 授权 | 已实现 | 复用 `TemplateCapabilityService/Publisher` 与 API 既有授权入口 |
| 模板能力 SQL 实测门禁 | 未实现 | 现有 evidence 为 `TEMPLATE_CONTRACT_VALIDATION`，不含实际 SQL 成功回执 |
| 发布记录版本、幂等、下线 | 能力发布已实现 | 复用 `mcp_tool_release` 及现有工具注册设施 |
| 历史 SQL 内容恢复与回滚 | 未完整实现 | 不把旧式绑定回滚当成 SQL 正文回滚；需保存可验证的不可变执行修订 |
| Studio 文件/选区来源与模板版本关联 | 未实现 | 仅补来源关联、原文件版本、选区范围、SQL digest、发布引用及审计；不复制另一套模板主库 |
| 语义检索字段和索引 | 已实现 | 复用 title、description、category、intentSignalsJson、routingLabelsJson 及现有 Lucene 模板索引 |
| 发布后立即更新搜索索引 | 不能凭保存推定 | 当前 SQL create/update Controller 没有调用模板索引 upsert；需明确复用既有索引更新方法并验证可发现 |
| 数据源编辑/发布权限与工程所有权 | 分离的既有边界 | 拥有工程不能代替模板管理权、发布权及数据源执行权；API 到 MCP 不可只用服务账号绕过业务授权 |
| 本任务 UI→真实模型→真实数据库完整闭环 | 本次尚未验收 | 必须新建验收记录，既有模板发布或元数据问答验收不能代替 |

## 4. 必须先解决的兼容性问题

1. **发布状态与发现状态。** `listEnabled` 同时服务通用模板发现和能力目录。草稿保存为 enabled 可能在正式发布前进入既有候选；设为 disabled 又会被 render 拒绝。不能仅在前端隐藏发布按钮解决。
2. **SQL 版本与绑定版本。** 能力发布保存 Schema、模板编码、固定资产和依赖 hash；调用前比对当前 revision，变化时拒绝。正常顺序修改不会静默调用新 SQL，但旧版本也不能直接恢复。校验 revision 与按编码读取模板不是同一个原子 SQL 快照，仍需验证并发修改窗口；本次测试不宣称已证明该窗口安全。
3. **测试证据种类。** 能力发布的 contract-validation 和旧式发布的 discovery-preview 都不能代替 SQL 查询回执。不能拿 TESTED 字样判定数据库测试通过。
4. **来源版本与幂等。** draft/test/publish 各步重新检查源文件版本、digest、调用者及数据源授权；跨 API/MCP 失败重试使用稳定操作标识对账，不以重试创建多份模板或重复发布。
5. **SQL 内容不可变。** 新草稿不能 update 当前运行模板的正文以实现“发布新版本”，否则已发布工具会失效；历史正文、参数 Schema、源文件版本、测试证据必须指向同一修订。数据源停用或撤权仍应立即拒绝历史执行。
6. **参数安全。** 保留现有禁止 raw SQL/SQL 片段、Schema 编译、安全 literal、只读 SQL 与敏感字段脱敏约束；本次发布功能不改变现有参数语言，也不声称现有实现使用 PreparedStatement 参数绑定（当前是受控 literal 渲染后执行）。
7. **生成与执行边界。** 当前 `SqlStudioGenerationToolPolicy` 依据持久化生成 session 限制为元数据检索。试执行必须是用户显式发起的独立授权操作，不能放宽生成 session 的执行权限。
8. **时间语义。** `McpControlPlaneClient` 当前请求等待 45 秒；不应直接用它包住任意长 SQL 试执行并误判失败。试执行遵守原查询超时和用户取消机制，管理通信与 SQL/模型超时分别处理。

## 5. 最小必要改动边界

正确入口为 **SQL Studio 作者界面 → API 身份、权限与来源检查 → 既有 MCP 模板作者接口 → 统一能力发布入口**。

- 模板正文、Schema 与搜索字段继续由 MCP SQL 模板管理维护；Studio 只保存文件到模板修订的来源关联。
- 在既有能力发布链补作者草稿、修订固定、显式试执行及 SQL 实测发布门禁；继续复用 `mcp_tool_release`、原注册器、现有 SQL executor 与索引。不新增第二个发布中心、Agent、连接池、SQL 引擎或搜索索引。
- `ToolReleaseService` 的状态与乐观锁约束可以复用其已有持久化模式；不得让 SQL Studio 为同一个 SQL 再额外发布 `CONTROLLED_TEMPLATE`。其受控检索绑定继续服务旧 Agent。
- 继续保留 `data_query_query → sql_query_execute` 的原有 Agent 检索与执行入口。发布成 `tpl_*` 不自动证明原入口能检索新模板；两种实际使用入口均按原权限分别验收，发布也不自动给所有 Agent 增加执行权。
- 精确 API、增量字段和修订执行契约留到 P1 实施时落地；本 P0 不新增暂不生效的接口、状态字段或配置键。

## 6. 本轮变更与验证

仅新增本报告，并在既有测试类补三项职责边界测试，没有修改生产业务行为或部署容器：

- `TemplateCapabilityServiceTest.capabilityPublicationRecordsContractValidationWithoutExecutingSql`：能力发布保存的是绑定、Schema 和 contract-validation；没有 SQL 试运行。
- `TemplateCapabilityServiceTest.changedTemplateRevisionInvalidatesOldCapabilityUntilRepublished`：依赖 revision 改变后，数据库记录仍为 RUNNING，但实际可用性/执行会拒绝；重新发布产生第二版本、旧记录下线，工具名稳定。
- `ControlledTemplateReleaseExecutorTest.contractTestUsesTemplateDiscoveryPreviewRatherThanActivatingAnExecutor`：旧式 contract test 调用检索 preview，不创建执行绑定。

定向回归：8 个测试类，63 项，失败 0、错误 0、跳过 0；Maven 日志为 `BUILD SUCCESS`。其中 `ToolReleaseLifecycleTest` 使用本地 H2 实测版本存储与状态转换，其 executor 是替身。其他目录、授权和执行边界测试也使用测试替身，不能作为真实 MCP、真实模型或业务数据库验收证明。

执行命令：

```powershell
mvn.cmd -o -pl chatchat-mcp-server -am test `
  '-Dtest=TemplateCapabilityServiceTest,ControlledTemplateReleaseExecutorTest,ToolReleaseLifecycleTest,TemplateAssetCatalogServiceTest,TemplateQueryMcpToolPublisherTest,TemplateQueryAdminControllerTest,SqlTemplateServiceTest,SqlQueryExecutionWorkflowTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```

本地忽略目录日志：`target/sql-template-publication-audit-tests.log`。本任务的 P1—P5、真实端到端验收、发布部署与回退演练尚未完成，不引用既往功能验收替代。

## 7. 源码依据

所有路径均相对仓库根目录：

- `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioService.java`
- `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioGenerationToolPolicy.java`
- `chatchat-api/src/main/java/com/chatchat/api/controller/datascience/SqlStudioController.java`
- `chatchat-api/src/main/java/com/chatchat/api/controller/mcp/{TemplateCapabilityController,ToolPublicationController,McpControlPlaneClient}.java`
- `chatchat-api/web-app/src/services/sqlStudioApi.js`
- `chatchat-api/web-app/src/js/views/ToolPublicationsView.js`
- `chatchat-api/web-app/src/views/ToolPublicationsView.test.js`
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/admin/SqlAdminController.java`
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/template/{SqlTemplateConfig,SqlTemplateConfigRepository,SqlTemplateService}.java`
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/template/{TemplateParameterValidator,workflow/TemplateParameterWorkflow}.java`
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/templatepublication/catalog/{TemplateAssetCatalogService,TemplateCallerScope}.java`
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/templatepublication/admin/{TemplateCapabilityService,ToolReleaseService,ControlledTemplateReleaseExecutor,ToolRelease}.java`
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/templatepublication/publisher/{TemplateCapabilityPublisher,TemplateQueryMcpToolPublisher}.java`
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/{tool/SqlMcpToolPublisher,execution/workflow/SqlQueryExecutionWorkflow,execution/SqlQueryExecuteService}.java`
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/{ops/discovery/CommandTemplateDiscoveryService,search/index/McpTemplateLuceneIndexService}.java`

既有 `docs/mcp-template-centric/implementation.md` 的“依赖变更失效”说明与当前能力服务一致；其页面描述与当前前端实际入口有差异，以上以前端和后端源码为准。该目录的历史生产记录仅表示先前模板能力功能曾记录过验收，不是本任务当前服务器状态证明。
