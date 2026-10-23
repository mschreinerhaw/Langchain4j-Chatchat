# 工具发布默认状态、按钮交互与成功提示修复

2026-10-23（北京时间）完成修复、部署与线上验收。分支：`fix/publication-permission-feedback`，功能源码提交：`55df55080`。

工具发布页首次进入默认筛选 `RUNNING`，详情选择匹配的运行版本，不再优先选择最新草稿。没有运行版本时显示空列表；用户可切换筛选查看草稿。新建保存草稿后切到草稿筛选并显示刚保存的版本。

修复通用按钮悬停规则覆盖主按钮蓝色背景的问题。工具发布、角色权限、资源授权、角色弹窗及工作空间设置共享按钮样式，主按钮悬停和键盘聚焦保持蓝底白字，按下时使用更深蓝色；禁用按钮不响应悬停背景变化。

权限控制台、资源授权编辑器及工具发布页的成功消息在 3.5 秒后自动清理。新消息重新计时，错误消息保留，组件卸载时清理计时器。

## 验证

- 定向 Vitest：23 项通过，覆盖运行版本选择、没有运行版本时的空状态、草稿保存可见性、成功提示消失、新消息计时重置、错误保留和计时器释放，以及角色资源分类回归。
- Vue 结构检查（62 个组件）及生产构建通过，仍存在既有的大文件体积提示。
- 本地及线上浏览器均通过。线上首次展示 5 个运行中的工具，当前版本匹配运行状态。
- 实际检查新建工具、新增角色、角色弹窗保存、保存授权的悬停计算样式为蓝底 `rgb(36, 85, 199)`、白字 `rgb(255, 255, 255)`；禁用按钮悬停背景保持一致。
- 从工具发布切回资源授权后，实际出现“授权目录已刷新”并自动消失；刷新页面后工具默认筛选仍为运行中；浏览器无脚本异常。
- 浏览器只打开及取消新建表单，没有创建工具、角色或修改业务授权。

[本地验收](local-browser.json)、[线上验收](live-browser.json)、[新建工具悬停截图](tool-hover.png)、[提示消失后的资源授权](authorization-after-dismissal.png)。可复用脚本：`scripts/test-publication-permission-feedback-browser.mjs`，凭据通过环境变量注入。

首次本地浏览器脚本使用了错误的工作空间路由，等待页面超时；修正为 `mcp-publication` 后本地及线上完整检查通过。未因此修改业务数据。

## 部署

入口：`http://192.168.195.221:18100/#/w/mcp-publication/toolPublications`。

API 镜像：`runtime-os-api:2.2.3-permission-feedback-20261023`。

运行 JAR SHA-256：`e62aa611bf57b0f5fa4a3d6af6b25e137e84c42b23343cf0f47989ec6e6debdc`。

仅替换 API 静态前端资源；所有非静态 ZIP 条目与原 JAR 逐项一致。使用干净运行时基础镜像重新封装，网关读取的 89 个前端资源与产物一致。角色权限、工具权限、用户角色、资源授权及 MCP 模板绑定部署前后指纹一致，无数据库迁移。

MCP 保持 `runtime-os-mcp:2.2.2-account-card-20261023`，JAR SHA-256：`c5eeda66f3aba1c5e9f104a4069d0346dcbcff10ae9d0f2ddc720e2c4af6ff09`。

服务器部署记录：`/opt/runtime-os-docker-stage/evidence/publication-ui-2.2.3-permission-feedback-20261023/deployment.json`。

精确回滚备份：`/opt/runtime-os-docker-stage/evidence/publication-ui-2.2.3-permission-feedback-20261023/backup-1792718920`。原镜像 `runtime-os-api:2.2.3-role-agent-tabs-20261023-r2` 保留。

前端资源与制品完整校验记录：`/opt/runtime-os-docker-stage/evidence/publication-permission-feedback-20261023/verification.json`。
