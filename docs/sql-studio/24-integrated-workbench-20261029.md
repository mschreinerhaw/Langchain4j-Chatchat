# SQL Studio 一体化工作台交互整改

## 源码审查（实施前）

审查分支 `fix/runtime-analysis-boundaries-20261028`，基线 `139ba9f29`。依据当前源码及 P1 实现核查，P0 文档中的“尚未实现”不能代表当前代码。

| 能力 | 当前实现及本轮边界 |
| --- | --- |
| 工程文件、Monaco、文件版本、差异审查 | 已实现，复用 SqlStudioView / SqlProjectTree / SqlEditorPane / CodeChangeReview |
| 授权数据源目录 | 已实现：SqlStudioMetadataController → SqlStudioMetadataService → 原 MCP 元数据接口；providers、分页 assets，携带身份和目录快照 |
| 数据库/Schema、表、字段 | 已实现：contexts + operations；LIST_NAMESPACES、SEARCH、DESCRIBE；SqlMetadataSession 负责快照及授权复核，非前端自行连接数据库 |
| 自主探索 | 已实现：prepare generation → SqlStudioMetadataAssistant → SQL_METADATA_DISCOVERY → sql_schema_context_query；只提供授权绑定，不要求用户选表，不进入工程代码生成流程 |
| 工程代码生成、解释、修复、优化 | 已实现，保留原 prepare/submit、Coding Agent 工程权限和审查回写；可选表证据仍须后端重新授权 |
| 模板草稿、修改、SQL 实测 | 已实现：SqlStudioPublicationController → TemplateCapabilityController.sqlAuthoring → SqlTemplateAuthoringService → SqlTemplateService / 原 SQL 执行网关；真实只读 SQL 测试最长 10 秒、最多 50 行 |
| 正式发布 | 已实现：同一个作者服务 → TemplateCapabilityService → 原 TEMPLATE_CAPABILITY 发布与 tpl_* MCP 注册；草稿不进入正式目录；未通过实测不得发布 |
| 版本、回滚 | 已实现，保存不可变 SQL、参数、数据源快照；回滚重新检查权限并实测，不只是恢复发布记录 |
| 测试结果正文 | 当前接口只有成功、行数、耗时、摘要与诊断，不返回原始结果行。本轮展示真实回执，不复制查询结果或伪造数据表 |
| UI 集成缺口 | 独立数据库 Tab、深藏的上下文选择、内部实现名称；当前文件未恢复独立库选择；发布前门禁缺少清晰汇总 |

## 复用的接口

- `GET /api/v1/data-science/sql/projects/{projectId}/metadata/providers`
- `GET .../metadata/assets?serviceId=...&page=...&expectedSnapshotId=...`
- `POST .../metadata/contexts`、`GET .../metadata/contexts/{discoveryId}`
- `POST .../metadata/contexts/{discoveryId}/operations`（LIST_NAMESPACES / SEARCH / DESCRIBE）
- 原工程 generation prepare / submit / 查询 / cancel / diff apply 接口。
- `GET /api/v1/data-science/sql/template-publications/datasources`
- `GET/POST /api/v1/data-science/sql/projects/{projectId}/files/{fileId}/template-publications`
- `PUT .../template-publications/{id}`；`POST .../{id}/test|publish|rollback`。

不新增接口、数据库表、连接池、元数据缓存或发布服务。元数据资产引用与模板测试数据源 ID 属于不同后端契约，不能仅凭显示名自动互相映射；模板面板保留明确的实际测试绑定选择。

## 本轮调整

项目资源同时显示工程文件及授权数据源导航。数据库上下文的唯一组件仍为 SqlSchemaExplorer，其控件移到助手显著位置；关闭助手时控件回到导航，不复制选择状态。编辑器补全、插入和助手仍使用同一个组件与授权绑定。

数据源、数据库/Schema 提供搜索，保留现有服务分页与刷新。按租户、用户、工作空间、项目和文件保存选择标识；恢复时重新读取授权目录、创建上下文，不把 localStorage 当作权限证据。切换文件/身份会废弃迟到响应和旧的显式表选择。探索提交在上下文加载/失败时阻止提交，避免退回旧一轮模型绑定。

高级设置默认折叠；Agent、模型、文档、历史仍在其中。工程代码生成使用业务名称。助手“模板管理”和工具栏“发布为 MCP 模板”打开同一个原发布面板。面板增加真实行数和发布前校验汇总，权限、参数、数据源检查明确留给服务端。

## 验证及现场限制

本轮在本地构建的 Vue 页面连接服务器隔离 API/MCP 完成联调，未部署生产。没有浏览器接口 Mock。认证使用既有隔离操作员正常登录；发布使用原有模板权限，新增受控修订仅通过原数据源治理入口批准其模板编码，不放开整个数据源白名单。

| 验证 | 结果 |
| --- | --- |
| Vue 自动化回归 | 16 个文件、102 项通过，包括工程文件、编辑器补全、AI 对话、授权元数据会话及模板发布。新增无预选表提交、共享绑定、控件迁移、文件选择恢复、授权撤销、命名空间消失不扩大范围和禁止跨元数据服务恢复同名资产引用测试 |
| Java 自动化回归 | 120 项通过、无失败/错误/跳过；MCP 80 项 + API 40 项，覆盖模板服务、真实 JDBC 测试网关、发布生命周期、元数据助手及权限边界 |
| 构建 | Vue 结构检查与 Vite 生产构建通过；仍有既有大包体积提示 |
| 真实元数据问答 | 任务 `80bd91bd-8e9a-3d15-8aeb-f03f224e18a3`，qwen3.8-flash，7 次模型调用、12 次工具选择，耗时约 108 秒；未预选表，返回真实候选与字段证据；`sqlExecuted=false` |
| 真实页面闭环 | 20 项检查通过：单一上下文、不预选表、刷新恢复、草稿保存、无效参数失败、失败禁止发布、SQL_DATABASE_EXECUTION 成功/1 行、发布、原能力目录查询、历史恢复、无 pageerror |
| 回滚及 Agent | 6 项检查通过：审核确认门禁、回滚重新实测、SQL/参数/数据源快照匹配、既有 Agent 授权继续适用，原 Runtime MCP 入口真实调用返回 acceptance_value=8 |
| 最终构建页面 | 9 项真实后端检查通过；1280×800 下对话滚动区高 368px，保存按钮在可视范围内；控件迁移后保持同一选择，恢复已发布 SQL 测试回执，无 pageerror |

证据：[页面与模型检查](24-integrated-workbench-evidence/browser-verification.json)、[回滚与 Agent 调用](24-integrated-workbench-evidence/rollback-agent-verification.json)、[真实模型页面](24-integrated-workbench-evidence/real-metadata-assistant.png)、[真实模板发布](24-integrated-workbench-evidence/real-template-publication.png)、[真实回滚](24-integrated-workbench-evidence/real-rollback.png)。受保护会话、完整数据库回执和原始模型输出保留在忽略的验收目录，未提交凭据。

最终页面：[1280×800](24-integrated-workbench-evidence/workbench-final-1280.png)、[1600×1000](24-integrated-workbench-evidence/workbench-final.png)。[最终构建联调结果](24-integrated-workbench-evidence/final-build-verification.json)、[自动化测试汇总](24-integrated-workbench-evidence/automated-tests.json)、[修改文件及散列](24-integrated-workbench-evidence/changed-files.json)。上下文与当前文件置于同一对话滚动区，保留需求输入框，避免较矮窗口中对话被固定上下文挤压；窄桌面顶部操作栏换行，避免保存按钮被裁切。

SQL 测试使用已存在 MySQL 上的受控常量查询 `SELECT {{value}} + 1 AS acceptance_value`，不创建或改写业务表。本轮页面回滚验证对应历史修订的完整快照恢复；跨不同 SQL、参数类型及数据源的既有验证参见 P1 交付记录，不将旧记录计入本轮 26 项浏览器检查。

尚未覆盖所有数据库类型的现场 UI 联调；本轮现场使用 MySQL，其他类型继续使用原接口提供的 database/schema，而非前端猜测结构。模板测试不展示原始结果行，只展示既有后端实际返回的执行回执。生产现有模板服务身份缺少模板主体授权时应继续返回拒绝，不以赋予服务账号全权或绕过模板治理通过测试。

## 修改文件

- `src/views/SqlStudioView.vue`、`src/js/views/SqlStudioView.js`、对应 `SqlStudioView.test.js`：资源统一、同一上下文控件承载、无绑定时阻止探索提交。
- `src/components/sql-studio/SqlSchemaExplorer.vue`、`src/js/components/sql-studio/SqlSchemaExplorer.js`、对应测试和 `sql-schema-explorer.css`：搜索、共享选择控件、按文件重新授权恢复。
- `src/components/sql-studio/SqlAssistantPanel.vue`、对应 JS：简洁业务名称、折叠高级设置、同一发布面板入口与自适应对话区域。
- `src/components/sql-studio/SqlTemplatePublicationPanel.vue`、对应 JS：测试行数、实测与来源一致性门禁展示。
- `src/styles/pages/sql-studio-ide.css`：统一资源与助手工作区样式。

以上路径均相对 `chatchat-api/web-app`。后端服务和接口、Coding Agent 调用链、MCP 授权策略、数据库结构均未改动。

`scripts/verify-sql-workbench-browser.mjs` 为可复用真实后端页面验收脚本，不拦截接口，不运行模型或业务 SQL。复验需设置 `SQL_PUBLICATION_BASE_URL`、`SQL_PUBLICATION_SESSION_FILE`（受保护的正常登录响应文件）、`SQL_PUBLICATION_PROJECT_ID`、`SQL_PUBLICATION_REVISION_ID`、`SQL_PUBLICATION_OUTPUT_DIR`、`SQL_WORKBENCH_ASSET_REF`、`SQL_WORKBENCH_NAMESPACE`（接口实际 database/schema 二元组的 JSON 字符串）；可选 `SQL_PUBLICATION_FILE_NAME`、`BROWSER_EXECUTABLE`。账号必须已获相应项目、元数据和发布读取权限。

自动化验证：`npm.cmd --prefix chatchat-api/web-app run test:sql-studio`；生产构建：`npm.cmd --prefix chatchat-api/web-app run build -- --outDir ../../target/sql-workbench/frontend`。Java 使用 P1 记录的原回归命令，并加入 `SqlStudioMetadataServiceTest,SqlStudioMetadataAssistantTest,SqlStudioMetadataGatewayTest,SqlStudioGenerationToolPolicyTest`；实际执行类和计数见自动化汇总。

## 回退

本轮仅前端变更，回退本轮源码并重新构建静态资源即可。没有数据库迁移、业务 SQL 数据变更或模板历史格式变更。受控验收新增的草稿和发布记录保留追溯，不能通过恢复数据库备份覆盖原有业务数据。
