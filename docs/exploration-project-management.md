# 分析项目修改与删除

分支：`feature/exploration-project-management`。功能代码提交：`0076af658aef0797dd4e5bd4fce8b861650a4f73`。

“选择分析项目”中每个条目右侧提供“…”按钮，展开“修改名称”和“删除项目”。名称支持 1–120 字，修改后同步更新列表与当前项目标题，不改动节点或重新启动分析。

两种操作均使用系统 `AppConfirmDialog` 和 `app-feedback.css`。删除提示明确说明不可恢复、会移除项目及思维链，同时保留原始文件和独立执行记录；默认焦点在取消按钮。请求期间禁止重复提交，错误留在弹窗内，允许取消或重试。

删除当前项目后切换到下一个项目；最后一个项目删除后清空视图、附件草稿和地址栏中的项目/节点参数。删除其他项目不会改动当前分析结果与草稿。仍有未终结 Agent 分析任务的项目拒绝删除，并显示提示。

后端 PUT / DELETE `/api/v1/data-science/explorations/{id}` 沿用数据探索空间访问校验；项目限定当前租户与用户，修改、删除都带版本校验。无数据库结构迁移，不级联删除文件或 Runtime 执行证据。

验证：

- `ExplorationWorkspaceTest` 13 项通过，覆盖修改保留节点、租户/用户隔离、过期版本拒绝、空白名称拒绝及运行任务删除拒绝。
- 前端相关 22 项通过，覆盖逐项菜单、修改持久化调用、统一确认弹窗、取消不删除、失败保留与重试、删除最后项目及删除其他项目保留草稿。
- 正式前端构建及 API Maven 打包成功。
- 真实浏览器复验脚本：`chatchat-api/web-app/scripts/test-exploration-project-management-live.mjs`。使用独立临时项目，不删除既有业务项目；需要 `TPCDS_AUTH_FILE` 和 `TPCDS_ORIGIN`，可通过 `PROJECT_SCREENSHOTS` 指定证据目录。

已于 2026-11-01 00:42（Asia/Shanghai）部署至 `192.168.195.221:18100`，镜像 `runtime-os-api:exploration-project-management-20261101-r1`，JAR SHA256 `1830df3f73048fe63bb581dc9d50ed20146ec94a04a4a3bdc88dd3399d89d85e`。数据库备份保存在服务器 `/opt/chatchat-releases/exploration-project-management-20261101-r1/backup`，部署校验确认原会话内容、消息索引数量及无关容器未变。

浏览器实测全部通过：创建独立项目 → 展开逐项菜单 → 修改名称并从服务端读取验证 → 取消删除后仍可读取 → 确认删除后列表移除 → 刷新后仍不存在。测试项目 `3d276f64-79bc-4ea0-98ba-89c6e17575ae` 已通过正常确认删除清理。实际结果和截图位于 `docs/exploration-project-management/`：`result.json`、`project-actions.png`、`project-rename.png`、`project-delete-confirmation.png`；全程未 mock 浏览器接口。
