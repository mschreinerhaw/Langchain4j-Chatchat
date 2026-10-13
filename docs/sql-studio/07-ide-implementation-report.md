# SQL Studio IDE 化实施与回归记录

日期：2026-10-13。分支：`feature/sql-studio-mvp`。本轮实施基于 [复用审查](06-ide-reuse-review.md)，未修改 Agent Runtime Core、认证或数据库结构，未连接生产数据库。本轮未发布到服务器；截图由本地真实 Chromium + HTTP fixture 生成，不能作为线上真实模型生成证明。

## 已实现的交互

- IDE Shell：Activity Bar、统一 Explorer、Monaco 编辑区、连续对话式工程助手、Problems/Output/Tasks/Validation 底部面板及状态栏。Explorer、助手和底部面板均可折叠、拖拽调整、双击分隔条复位；布局通过现有 Workspace 草稿机制按身份保存并恢复。
- Explorer：Mapping、工程 SQL、历史案例共用一棵工程资源导航；支持展开、选择或拖放上传、右键打开/复制路径/版本/下载/重命名。重命名限定同一目录与扩展名，服务端验证对象权限和文件版本。
- Editor：沿用现有 Monaco 模型、多标签、草稿、语言适配、补全、格式化、诊断与 Ctrl/Cmd+S 保存；新增 Welcome、选中代码交给助手、已有文件生成提案的只读 Diff。生成文件在用户确认前只预览，应用后按原有版本检查写入。
- Mapping：CSV/XLS/XLSX 从已授权下载接口读取原文件，使用已有 SheetJS 在浏览器显示最多 500 行、100 列，可切换 Sheet、定位行列，并把所选字段/单元格引用到 AI 请求；其他格式沿用抽取文本预览。
- AI 助手：按工程保存多轮消息草稿，支持 `@` 文件选择、选中代码、紧凑的 Agent/模型/任务设置，展示生成提案与 Agent 轨迹。每轮继续调用原有 prepare → Agent Task → bind → poll → apply 接口；任务轮询不会锁定编辑器。

## 接口与权限

原有 `GET /api/v1/data-science/sql/projects/{projectId}/files/{fileId}/download` 用于 Mapping 原文件表格预览，仍由 Workspace 门禁和 SQL 工程对象权限保护。新增 `PUT /api/v1/data-science/sql/projects/{projectId}/files/{fileId}/path`，请求 `{ "path": "sql/new-name.sql", "expectedVersion": 1 }`，返回原 `File` 契约。服务端按身份解析项目/文件、校验路径、相同目录和扩展名，以 SQL 条件更新实施版本比较，并记录新版本；冲突返回 409。浏览器传入的 Workspace ID 不作为对象授权依据。无需数据库迁移。

## 修改文件

| 类别 | 文件 |
| --- | --- |
| IDE 页面与样式 | `chatchat-api/web-app/src/views/SqlStudioView.vue`、`src/js/views/SqlStudioView.js`、`src/styles/pages/sql-studio-ide.css` |
| Explorer | `src/components/sql-studio/SqlProjectTree.vue`、`SqlFileTreeNode.vue` 及对应 `src/js/components/sql-studio/*.js` |
| Editor 与预览 | `src/components/sql-studio/SqlEditorPane.vue`、`SqlMappingPreview.vue` 及对应 JS |
| AI 与底部面板 | `src/components/sql-studio/SqlAssistantPanel.vue`、`SqlBottomPanel.vue` 及对应 JS |
| API 与状态 | `src/services/sqlStudioApi.js`、`src/js/utils/sqlIdeState.js` |
| 后端重命名 | `chatchat-api/src/main/java/com/chatchat/api/controller/datascience/SqlStudioController.java`、`datascience/sql/SqlStudioService.java` |
| 测试 | `chatchat-api/src/test/java/com/chatchat/api/datascience/sql/SqlStudioIntegrationTest.java`、`chatchat-api/web-app/scripts/test-sql-studio.mjs`、`src/js/utils/sqlIdeState.test.js`、`web-app/package.json` |

Vue 单文件组件仅保留模板、外部脚本引用；新增样式集中在 `sql-studio-ide.css`，已用 Prettier 3.6.2 格式化。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| `npm.cmd run verify` | 既有 UI 280、SQL Studio 10、Code Intelligence 10 项通过；Vite 生产构建通过。仍有既有大 chunk 提示。日志：`target/sql-studio-ide-frontend.log`。 |
| `npm.cmd run test:sql-studio:browser` | 真实 Chromium + fixture 通过：IDE 布局、面板尺寸保存、多标签与草稿恢复、CSV/XLSX 表格及 Sheet 切换、字段引用、历史案例、选中代码、两文件提案、Diff、人工应用、切 Workspace 后恢复。无浏览器运行时错误。 |
| `mvn.cmd -q -pl chatchat-api -am '-Dtest=SqlStudioIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | H2 集成测试 13 项，0 failure、0 error、0 skipped；包含文件重命名版本冲突和跨用户拒绝。日志：`target/sql-studio-ide-maven.log`。 |
| `git diff --check` | 通过。 |

页面截图：[SQL Studio IDE 本地浏览器截图](../../target/sql-studio-ide-audit/sql-studio-ide.png)。

## 尚未完成与风险

- 浏览器测试使用 fixture，没有调用真实模型、真实 Mapping、真实数据库或生产环境；真实 SQL 生成质量与生产 Agent 工具链仍需另行验收。
- 多轮对话草稿存在浏览器端，原有任务和生成记录由服务端保存；跨设备不会自动同步完整对话文本。历史摘要受 4000 字符任务输入上限约束。当前 SQL 工程生成 API 要求至少一个 Mapping，未增加通用无 Mapping 聊天接口。
- 表格预览仅解析 CSV/XLS/XLSX，最多 500 行、100 列；复杂公式、合并单元格、结构化字段映射与 PDF/DOCX 表格语义仍使用现有文本抽取结果。行列定位与字段引用已实现，跨表字段关系编辑尚未实现。
- 底部 Validation 继续呈现既有 `NOT_RUN` 状态；本轮未增加 SQL 运行或校验服务。文件夹由路径树呈现，未实现跨目录移动、删除或空文件夹创建。
- SQL/Monaco 资源仍存在构建大 chunk 提示；本轮未重构全站打包策略。Python Studio 和 Runtime Core 未改动，完整仓库后端测试本轮未执行。
