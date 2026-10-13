# SQL Studio 工程交互与生产验证（2026-10-13）

本轮针对 SQL Studio 工程操作和 IDE 顶栏完成增量改造，沿用现有 Runtime OS Core、Agent Task API、文档库权限和 SQL 工程表。没有修改 Agent 决策或规划机制，也没有新增数据库结构。

## 实际交付

- Explorer、AI 助手、底部面板的开关移至顶部工具栏；原左侧深色 Activity Bar 不再占用编辑器宽度。
- 工程可删除；正在运行的 Agent 任务会阻止删除。删除工程时，其 SQL 文件、文件版本和 SQL 生成记录在同一数据库事务中清理。Runtime 会话与任务本身仍由 Runtime Core 管理，不随 SQL 工程删除。
- Explorer 右键可输入路径新建 SQL 文件，并可对文件执行受版本校验的删除。文件删除同时清理该文件的版本记录；过期版本返回 HTTP 409。
- Mapping 和 SQL 案例通过当前身份有权访问的文档库列表多选。服务端再次按用户、租户和角色读取每一份文档；导入一批文件在同一事务中创建工程快照，任一项无权读取、无内容、类型不符或同名冲突时整批回滚。原文档库文件保持不变，原件小于等于 2 MiB 时随快照保存，供 Excel/CSV 预览使用。旧上传接口保留用于已有调用兼容，但页面入口改为文档库选择。
- AI 执行设置改为 Agent 和模型二选一。前端只提交所选类型，后端拒绝同时设置两者。
- 修复工程切换或删除时 Monaco 延迟诊断读取已释放模型的异常。

## API 与数据边界

| 接口 | 行为 |
| --- | --- |
| `GET /api/v1/data-science/sql/library-files?kind=MAPPING\|REFERENCE` | 使用服务端身份权限上下文列出文档库候选文件 |
| `POST /api/v1/data-science/sql/projects/{projectId}/library-files` | 请求 `{kind,documentIds}`，事务性导入授权文档快照 |
| `DELETE /api/v1/data-science/sql/projects/{projectId}/files/{fileId}?expectedVersion=N` | 工程归属校验、版本比较、文件及版本记录事务删除 |
| `DELETE /api/v1/data-science/sql/projects/{projectId}` | 工程归属校验，阻止运行中任务，事务删除工程记录 |

浏览器传入的 Workspace ID 不参与对象授权。SQL 工程和文件由服务端按持久化的租户及所有者校验；文档内容由本地或 MCP 文档库读取端按同一身份权限上下文重新校验。现有 SQL 文件写入仍使用路径校验和版本 CAS。事务保证 SQL 工程数据库内的写入原子性；跨 MCP 文档库与 SQL 工程数据库没有分布式事务，导入结果是授权时刻的版本快照，源文档后续变更不会自动同步。

## 变更文件

- 后端：`chatchat-api/src/main/java/com/chatchat/api/controller/agent/{DocumentLibraryReadPort,LocalDocumentLibraryReadPort,McpDocumentLibraryReadPort}.java`，`controller/datascience/SqlStudioController.java`，`datascience/sql/SqlStudioService.java`。
- 前端：`chatchat-api/web-app/src/views/SqlStudioView.vue`，`src/js/views/SqlStudioView.js`，`src/components/sql-studio/{SqlProjectTree,SqlFileTreeNode,SqlAssistantPanel}.vue`，`src/js/components/sql-studio/{SqlProjectTree,SqlAssistantPanel,SqlEditorPane}.js`，`src/services/sqlStudioApi.js`，`src/styles/pages/sql-studio-ide.css`。
- 验证：`chatchat-api/src/test/java/com/chatchat/api/datascience/sql/SqlStudioIntegrationTest.java`，`chatchat-api/web-app/scripts/{test-sql-studio,verify-sql-ide-production}.mjs`。

## 实际测试与发布

| 检查 | 结果 |
| --- | --- |
| `mvn.cmd -q -Pbackend-only -pl chatchat-api -am '-Dtest=SqlStudioIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 16 项通过、0 失败、0 错误；涵盖跨用户工程/文件拒绝、过期版本删除、整工程删除、文档库导入回滚和 Agent/模型互斥 |
| `npm.cmd run verify` | 前端单元/组件测试及 Vite 生产构建通过；保留原有大 chunk 提示 |
| `npm.cmd run test:sql-studio:browser` | 真实 Chromium + HTTP fixture 通过：顶栏、右键建删文件、文档库勾选、Agent/模型切换、工程删除，无页面异常；fixture 不代表真实模型生成 |
| 生产包 | 最终本地 JAR SHA-256 `7904ed3cda22d5d0d1ef350647d267aa119ce99bffa65ac9fe1bab851cca4cfa`；追加上一版已有的旧静态资源后，服务器最终运行包 SHA-256 `e05d040a8577d00ddeaf2029109e1ede4518e3bd31c3e8e32100a9892dd883bd`；ZIP 完整性检查通过 |
| 生产回滚包 | 本轮开始前的精确运行包备份在 `/opt/chatchat-1.0.0-SNAPSHOT/data/rollback-before-sql-interactions-20261013.jar`，SHA-256 `178f9874c48634e0e5766bb734c3bf8f6b789a0b6d4512cf462e4688b11df971`；最终补丁发布前的精确运行包备份在 `data/rollback-before-sql-originals-cap-20261013.jar`，SHA-256 `3805dd798922e8e1ed947817e3743875603c27189255336645dd9b7ddaaedeb8` |
| 生产冒烟 | `http://192.168.195.221:8080/` 返回 200，未认证 SQL 工程接口返回 401，MCP `:8090/` 返回原有 302。真实管理员身份下文档库列表 200、授权 Mapping 快照导入成功、临时工程及文件建删成功、过期版本删除返回 409；真实浏览器打开 SQL Studio、Python Studio、智策页面且无 JavaScript 异常。测试创建的临时工程已清理 |

线上截图：[SQL Studio 生产页面](../../target/sql-studio-ide-deploy-audit/sql-studio-real.png)。

## 尚未完成与风险

- 线上授权文档库当前返回 100 个可作为 Mapping 的文件，未返回 `.sql` 案例；SQL 案例选择界面的空状态已验证，真实案例导入尚无样本可验。
- 未发起真实模型 SQL 生成，也未执行数据库 SQL；不能据此宣称端到端生成质量或生产 SQL 校验已验证。
- 文档库列表端口当前最多读取前 500 条，尚无分页选择。超过该范围的候选文档需要后续增加分页或搜索。
- 删除工程仅删除 SQL Studio 自身记录；已存在的 Runtime 会话和任务历史继续由 Runtime Core 管理。跨服务的数据不具备分布式 ACID，应按现有审计与保留策略管理。
