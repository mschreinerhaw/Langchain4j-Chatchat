# MCP 工具发布工作空间：取消最近对话

2026-10-23，当前功能分支 `feature/unified-management-workspaces`。

工作空间注册配置此前仍是 `layoutType: assistant`，与专用管理工作空间的产品定位不一致。现通过统一工作空间管理接口将 `mcp-publication` 改为 `management`，配置版本从 1 升为 2，数据库行版本从 0 升为 1。现有页面隐藏逻辑继续生效，工作空间配置也正确声明管理布局。新安装使用的 PostgreSQL/H2 种子配置同步修正。

本次为配置发布，现有 API/MCP 镜像不变。环境为 `192.168.195.221`，入口为 `http://192.168.195.221:18100/#/w/mcp-publication/toolPublications`。使用已有权限控制、乐观锁和工作空间审计接口保存配置。没有变更角色或资源授权。

## 验证

- `UnifiedManagementMigrationTest` 通过：实际执行初始化脚本后工具发布工作空间为管理布局，重复执行仍保留授权和自定义配置。
- 远程浏览器 6 项检查通过：直接打开、刷新、从智能对话切换进入均无历史区域、历史管理入口、对话右栏或新建对话浮钮；智能对话仍显示历史记录；1366/1440/1920 三种宽度可用；无页面运行错误。
- [浏览器检查报告](evidence/history-layout/browser-report.json)、[配置变更前](evidence/history-layout/before.json)、[配置变更后](evidence/history-layout/after.json)。
- [1440px 页面截图](evidence/history-layout/publication-no-history-1440.png)。

## 执行和回滚

脚本：`scripts/configure-publication-management-layout.py`。以现有管理员私有会话调用工作空间 API，先保存原配置，再使用 `expectedVersion` 更新，仅改变布局和配置版本；重复运行时不会再次升版。输出目录必须是新目录，以保留此前备份。

服务端记录：`/opt/runtime-os-docker-stage/evidence/publication-history-layout/change/`。私有会话不提交到代码仓库。

如需回滚配置，重新读取 `/api/v1/enterprise/workspaces`，核对当前配置仍等于本次 `after.json`。将 `before.json` 的 manifest 作为回滚基础、`configVersion` 设置为当前值加一，以当前数据库行版本作为 `expectedVersion`，通过 `PUT /api/v1/enterprise/workspaces/mcp-publication` 保存；保留原有 status/accessPermissionCode。若存在后续修改，先合并这些修改，避免覆盖。源代码通过本次独立提交回滚；此前基线和发布 Tag 保持不变。
