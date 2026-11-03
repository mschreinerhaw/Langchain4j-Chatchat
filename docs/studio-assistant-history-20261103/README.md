# SQL / Python Studio 助手历史会话

2026-11-03。分支：`feature/studio-assistant-history-20261103`。基于主分支 `743a01304`。

## 已实现

- AI 数据库助手的每条历史增加“…”操作菜单，通过“删除会话”触发系统已有 `AppConfirmDialog`。提示不可从历史列表恢复、已保存工程文件不受影响；取消不发送请求，提交期间防止重复点击，失败保留原记录并展示错误。
- Python Studio 的 AI 工程助手增加历史入口，复用同一个 `StudioConversationHistory` 组件，支持分页加载、恢复对话、当前项标识和删除。
- Python 代码生成历史复用已有 `PYTHON_STUDIO` 会话投影，接入已有的任务结果和原始交付内容。普通问答 / 代码解释成功后也保存到现有会话存储。标题使用原始用户问题，而非拼接了脚本和前几轮对话的模型上下文。
- 恢复 Python 历史展示原始对话和结果，不自动写入当前脚本。需新修改时继续通过既有生成与代码审查流程。

## 服务端边界

SQL 新增 `DELETE /api/v1/data-science/sql/projects/{projectId}/generations/{generationId}`。校验当前工程所有权与 generation 归属。Coding 任务根据 Runtime 状态判断是否结束；Agent 任务检查最新授权重试链，运行中返回 409。仅删除指定 generation，保留工程文件、其他历史及 Runtime 审计；SQL 工程共享会话不会整段删除。

Python 新增 `/api/v1/data-science/python/assist/history` 列表接口、`/{id}` 详情和删除接口。列表只返回当前所有者别名范围内的 `PYTHON_STUDIO` 记录；详情与删除先执行现有会话所有权校验，再核对渠道。删除 Coding 历史还会读取 Runtime 当前状态；运行中拒绝删除。底层复用 `ConversationService` 的持久化和删除能力，不增加历史存储表。

主要实现：`PythonAssistantHistoryController`、`PythonAssistantHistory`、`SqlStudioService.deleteGeneration()`；前端共享组件位于 `web-app/src/components/editor/StudioConversationHistory.vue`，脚本和样式按项目规则独立存放。

## 验证

- 前端 41 项测试通过：共享历史菜单、统一确认、取消、重复提交、服务器拒绝后的记录保留、SQL 当前历史删除后的清理，以及现有 Python / SQL 助手回归。
- `SqlStudioIntegrationTest` 44 项通过，含真实 HTTP / H2 下的指定历史删除、运行中拒绝、错误工程归属、文件与其他历史保留、Python 渠道隔离、持久化读取和删除。
- Python 助手与历史单元测试 14 项通过，含既有代码助手兼容性、普通文本结果落库和删除前 Runtime 状态检查。
- 前端结构检查和生产构建通过。

在服务器 `192.168.195.221:18100` 使用真实登录、Chrome、真实 API 完成浏览器验收：SQL / Python 均验证展开菜单、系统删除确认、取消不发请求、确认发出真实 DELETE、删除当前历史、其他记录保留、刷新后仍已删除。Python 历史成功恢复了保存的问答内容。两次删除均 HTTP 200，已删 Python 详情 HTTP 404，页面 JavaScript 异常为 0。

浏览器操作只删除本次创建的 SQL / Python 验收记录。临时 SQL 工程与会话已清理，已有用户历史未删除。本次现场验收未调用模型或执行生成代码；模型输出仅在后端单元测试中使用夹具，不能视为真实模型生成验收。

证据：`verification.json`、`cleanup.json` 和五张浏览器截图。可通过 `scripts/verify-studio-assistant-history-live.mjs` 对预先创建的专用验收记录重测，登录密码使用环境变量，不写入脚本。

## 部署

镜像：`runtime-os-api:studio-history-20261103-r1`。

JAR SHA-256：`fb198abdde9c6ad1c45d667f298b7ad680e85595f7e5f429fb0b868554d3eae9`。

从当前线上库目录修复版本增量装配，逐项校验无关后端内容保持一致。未新增数据库迁移；MCP、Coding Worker 保留当前版本，39 个工具注册健康。部署记录、精确备份位置和回滚材料见 `deployment.json`、`manifest.json`、`artifact-verification.json`。
