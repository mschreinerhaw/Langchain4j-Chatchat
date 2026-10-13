# SQL Studio 代码交付清单

## 使用入口及组件结构

已获数据科学 Workspace 授权的身份进入 `#/w/data-science/sqlStudio`。创建工程 → 上传/关联 Mapping → 选择历史 SQL 案例与方言 → 保存编辑 → 提交生成任务 → 预览候选文件 → 确认保存 → 多标签编辑/下载。只有确认应用才落为 SQL 工程文件；后台任务沿用原 Agent Task。

```text
SqlStudioView.vue                 工程加载、上下文、保存、任务观察与恢复
  SqlProjectTree.vue             上传、建文件、刷新及工程树
    SqlFileTreeNode.vue          目录、文件与 Mapping 勾选
  SqlEditorPane.vue              Monaco SQL models、标签、视图状态、预览
  SqlAssistantPanel.vue          案例/Agent/模型选择、任务轨迹、审核应用
sqlStudioState.js                已授权草稿恢复、版本合并、并发保护
sqlStudioApi.js                  复用 API transport 和原 Agent 提交
sqlStudioContracts.d.ts          API/Mapping/产物/Validation 数据声明
```

## 新增源码

相对仓库根目录：

- `chatchat-api/src/main/java/com/chatchat/api/controller/datascience/SqlStudioController.java`
- `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioService.java`
- `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlOutputBundle.java`
- `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioMigration.java`
- `chatchat-api/src/main/resources/sql-studio/schema-h2.sql`
- `chatchat-api/src/main/resources/sql-studio/schema-mysql.sql`
- `chatchat-api/src/main/resources/sql-studio/schema-postgresql.sql`
- `chatchat-api/src/main/resources/sql-studio/workspace-upgrade.sql`
- `database/migration/h2/V20261013_03__sql_studio.sql`
- `database/migration/mysql/V20261013_03__sql_studio.sql`
- `database/migration/postgresql/V20261013_03__sql_studio.sql`
- `chatchat-api/web-app/src/views/SqlStudioView.vue`
- `chatchat-api/web-app/src/components/sql-studio/SqlProjectTree.vue`
- `chatchat-api/web-app/src/components/sql-studio/SqlFileTreeNode.vue`
- `chatchat-api/web-app/src/components/sql-studio/SqlEditorPane.vue`
- `chatchat-api/web-app/src/components/sql-studio/SqlAssistantPanel.vue`
- `chatchat-api/web-app/src/js/utils/sqlStudioState.js`
- `chatchat-api/web-app/src/services/sqlStudioApi.js`
- `chatchat-api/web-app/src/services/sqlStudioContracts.d.ts`
- `chatchat-api/web-app/src/styles/pages/sql-studio.css`

## 扩展既有文件

| 文件 | 改动 |
| --- | --- |
| `chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceOriginService.java` | 通用 createForWorkspace，服务端应用来源和原身份政策；不改变既有 create/prepare |
| `chatchat-api/src/main/resources/workspace/data-science.json` | V3 SQL Studio 页面，替代 reserved 扩展声明 |
| `chatchat-api/web-app/src/js/App.js` | SQL 页面异步组件注册与 bare SQL 路由兼容 |
| `chatchat-api/web-app/src/js/utils/workspaceContext.js` | bare sqlStudio 指向数据科学空间 |
| `chatchat-api/web-app/src/js/views/LoginView.js` | 未登录访问 bare SQL 路由的空间选择兼容 |
| `chatchat-api/web-app/src/services/api.js` | FormData 不强加 JSON Content-Type，保留 Token/Workspace headers |
| `chatchat-api/web-app/package.json` | 纳入 SQL 单元测试与浏览器测试命令 |

## 新增测试与文档

- `chatchat-api/src/test/java/com/chatchat/api/datascience/sql/SqlStudioIntegrationTest.java`
- `chatchat-api/src/test/java/com/chatchat/api/datascience/sql/SqlOutputBundleTest.java`
- `chatchat-api/web-app/src/js/utils/sqlStudioState.test.js`
- `chatchat-api/web-app/scripts/test-sql-studio.mjs`
- `docs/sql-studio/01-capability-audit.md`
- `docs/sql-studio/02-design-and-contracts.md`
- `docs/sql-studio/03-test-report.md`
- `docs/sql-studio/04-code-changes.md`

## 直接复用而未改动

AgentTaskController/AgentTaskService/AgentEventStore 及其 finalAnswer、幂等、取消、执行身份/状态、授权工具发现；ApiAuthenticationFilter、ApiPermissionAuthorizationFilter、ObjectAccessService、ObjectRequestAdvice；WorkspaceService 与 MCP License API；DocumentTextExtractor；Monaco/editor worker；workspaceDrafts。原 Python Studio、Python Runtime、Skills/MCP 业务服务及 Runtime planner/loop 未重写或迁移。

新增应用持久表不创建第二份 Runtime。SQL 源码记录来源 taskId，原任务事件和 UI Artifact 继续使用已有存储。公开接口、对象权限、Mapping 数据及兼容方案见 [设计契约](02-design-and-contracts.md)；实际结果与未实现项见 [测试报告](03-test-report.md)。
