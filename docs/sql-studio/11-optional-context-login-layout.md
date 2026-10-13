# SQL Studio 可选上下文与登录页自适应修复

2026-10-13，部署环境 `192.168.195.221:8080`，目录 `/opt`。

## 行为调整

- 登录页使用固定视口高度和内部滚动，移除导致溢出的最小高度、表单固定偏移和悬浮页脚。较矮的桌面收紧表单间距；平板限制插图尺寸，避免横向溢出。
- Mapping 文档和 SQL 参考样例均为可选项。前端发送按钮和后端生成接口取消 Mapping 必选检查；无参考资料时按用户需求及当前 SQL 生成，提示模型在 notes 中说明假设。
- 项目资源区移除文档 Mapping/SQL 案例选择入口。Mapping 选择位于 AI 助手，可勾选、取消、移除，作为工程专属的生成上下文草稿保存。
- 文档选择通过 `mappingDocumentIds` 传入生成接口。后端按当前租户、用户及角色重新校验访问权，读取并冻结文档正文到任务上下文，不创建 `ds_sql_file`，不打开编辑器标签。
- 旧工程 Mapping 文件及已有工程 SQL 参考仍兼容；旧导入 API 保留兼容，当前文档选择界面不调用它。
- 助手滚动区子内容不再压缩，上下文区超长时内部滚动，发送区保持可见。

任务仍经既有 Runtime 的 `llm_chat` / `agent_chat` 入口提交、查询及取消；未新增执行引擎、数据库迁移或 SQL 执行功能。

## 验证

| 检查 | 结果 |
| --- | --- |
| SQL 集成测试 | 19 项通过，包括无 Mapping/样例生成，文档权限撤销后重新准备拒绝，既有任务快照不变，项目文件数不增加 |
| 输出契约与 Runtime OS 架构边界 | 2 + 24 项通过 |
| SQL 前端状态测试 | 10 项通过 |
| Chromium HTTP fixture | 选择文档不导入文件、可选 Mapping、无文档 SQL 入口、快捷输入、运行/取消、文件与工程操作通过 |
| 登录页本地及线上 | 1920×1080、1366×768、1280×720 桌面表单完整显示；1024×768 与 390×844 内部滚动可访问登录按钮，无横向溢出 |
| 前端生产构建 | 通过，保留既有大 chunk 提示 |
| 生产页面 | SQL Studio、Python 开发、智策页面打开成功，0 浏览器异常 |
| 生产文档上下文 | 授权文档候选 100 个；选择实际文档准备任务，工程文件数保持 1 |
| 真实模型无参考生成 | 任务 `88b8b155-1b2a-3db4-9a1e-03af600409d5` 为 SUCCESS，生成并通过保存接口应用 `scripts/generated_customer.sql`，6 条事件，0 工具调用 |
| 清理及健康 | 临时工程已删除，原有 1 个工程保留；API UP，active/pending/waiting/queue 均 0 |

模型验证未执行生成 SQL，`validation=NOT_RUN`。截图、任务结果、发布清单位于 `target/sql-context-layout-20261013/`；验证脚本为 `chatchat-api/web-app/scripts/verify-sql-prototype-production.mjs`，凭证由环境变量提供。

## 发布

基于当前运行包制作补丁，仅替换 SQL Studio 服务及其嵌套类和静态资源；其余非静态条目逐项字节一致，ZIP 完整性检查通过。旧静态哈希资源保留，MCP 包保持现状。

- 运行包：`/opt/chatchat-1.0.0-SNAPSHOT/lib/app/chatchat.jar`
- SHA-256：`6a7cdd265b33a636a513ad5d8bdd5017912bbb3d98fe5ec5499376ecec7daf30`
- 发布前备份：`/opt/chatchat-deploy-backup/sql-context-layout-20261013/chatchat.jar`
- 备份 SHA-256：`625de04b9affd3221bd2f9c6605a3d477c6bf42301cdfc9a6e5bf11130d67019`
- 发布清单：`/opt/chatchat-deploy-staging/sql-context-layout-20261013/deployment.json`

回滚使用既有 stop/start 脚本及精确备份，恢复前先核对当前包哈希，避免覆盖后续发布。本次未进行回滚演练。

## 后续修复：统一提示、紧凑缩进和先建文件夹

同日后续发布将 SQL Studio 的页面提示、任务错误和底部反馈改为共用 `AppNotice` / `app-feedback.css`，删除确认采用 `AppConfirmDialog`，支持取消、Escape 和原生模态焦点管理。缩进选择框为 44×24 像素，共用编辑器偏好组件采用紧凑尺寸。

项目资源区支持先创建文件夹，再选择文件夹创建 SQL 文件；文件夹右键可创建子文件夹及文件。空工程的新建文件入口会先引导创建文件夹。空文件夹独立保存于新增的 `ds_sql_folder` 应用表，刷新后仍显示，已有文件路径形成的旧目录继续可用。文件夹接口复用工程所有权及工作区校验；校验父目录、重复名称、安全路径和文件/文件夹冲突。文件与文件夹写入采用工程行锁，项目删除事务同时清理文件夹。文件保存 API 保留旧客户端及生成结果的路径兼容性，交互界面按文件夹先于文件的顺序创建。

本轮 SQL 集成 21、输出契约 2、Runtime OS 架构 24，共 47 项后端测试通过；10 项前端状态测试、Chromium 交互回归及最终生产构建通过。线上实际验证空文件夹刷新保留、指定目录创建文件、删除弹窗取消/确认、缩进尺寸、统一提示及五种登录页视口。SQL Studio、Python 与智策页面无浏览器异常；无 Mapping/样例的真实任务 `ddcb4797-364f-3c53-94ef-94f34bf63309` 为 SUCCESS，产物通过保存接口应用，SQL 未执行。临时工程已清理，原有 1 个工程保留，API UP、MCP 认证 HTTP 200，任务活动及队列均为 0。

本轮只替换 SQL Studio 服务/控制器及嵌套类、三个数据库方言的增量建表资源和静态资源，其余非静态条目逐项字节一致。新增表回滚时保留，不删除数据。最新运行包 SHA-256 为 `77e6b8157900ddfccc373b190a08cefd4058a70ed5067f8b141a975a237e7725`；发布前备份位于 `/opt/chatchat-deploy-backup/sql-folders-feedback-20261013/chatchat.jar`，备份哈希为上一轮 `6a7cdd265b33a636a513ad5d8bdd5017912bbb3d98fe5ec5499376ecec7daf30`。发布清单位于 `/opt/chatchat-deploy-staging/sql-folders-feedback-20261013/deployment.json`，本地验证产物位于 `target/sql-folders-feedback-20261013/`。
