# 模板驱动能力发布：现状分析与改造映射

基线日期：2026-10-23。原分支 `fix/mcp-sidebar-scrollbar`，原工作区干净，基线提交 `62a88a519f1b2fbe92f9bdcf4ece79f03a296015`。功能分支 `feature/mcp-template-centric-permissions`，基线 Tag `baseline/mcp-template-centric-permissions-20261023`；未覆盖既有分支或 Tag。已同步远端引用，本地与原跟踪分支一致。

## 当前调用与数据关系

API 的 `ToolPublicationController` 以登录用户确定租户和 actor，通过内部凭据转发 MCP 控制面。MCP 的 `ToolReleaseService` 在 `mcp_tool_release` 保存版本、定义、Schema、合约测试与发布者；`ControlledTemplateReleaseExecutor` 复用 `TemplateQueryBindingService` 激活 `mcp_template_query_binding`，`TemplateQueryMcpToolPublisher` 发布业务子工具。

`TemplateAssetCatalogService` 汇聚 SSH 命令模板、SQL 模板、HTTP 配置、数据库查询、API 服务及 Python 模板。SSH 的 allowedCommands、SQL 的 allowedTemplates 等既有关系负责资产边界；执行器继续走原模板工作流和参数绑定。

`McpAuthorizationService` 从 API 权限快照获取用户、角色和工具授权，执行前读取 fresh 快照。资源授权与数据范围保持用户端权威。MCP 本地账号及菜单授权只覆盖运维控制台；不能把它当作业务模板授权。

Agent 配置保存在现有 SkillCatalog/SkillToolConfig。当前发布目录主要按角色与父执行器选择；发布列表只按租户隔离，同租户发布者边界尚不足。传输上下文目前携带 user/tenant/scope，尚未明确承载 Agent 身份。必须同时完善主体、Agent 绑定、发现和执行校验，不能只改菜单。

## 实施映射

| 领域 | 保留 | 调整/补充 | 兼容处理 |
| --- | --- | --- | --- |
| 资产与执行 | 既有资产表、模板表、API/SQL/Python/SSH/HTTP 工作流 | 面向业务的统一模板描述及依赖摘要，执行前复核实际资产 | 不删除有效执行器和固定参数绑定插件 |
| 模板目录 | 现有 Catalog、统一权限快照 | 根据真实用户与租户返回模板子集，自动定位父执行器 | 管理端保留完整维护与诊断能力 |
| 发布 | ToolRelease、不可变版本、合约测试、运行/下线/回滚 | 单模板发布、稳定身份、请求幂等、模板快照与所有者检查 | 原发布版本及工具标识保持可用，增量迁移 |
| Agent 分发 | 现有 Agent 目录及工具配置 | 按 Agent 校验可管理范围与运行发布，绑定/解绑、有效工具查询 | 旧 Agent 配置在明确兼容规则下继续工作 |
| 权限与身份 | API 用户/角色/资源授权、MCP 执行 fresh 快照 | 请求身份携带 Agent，发现与执行使用同一有效策略，拒绝参数伪造 | 服务凭据仅认证传输，不代替业务用户 |
| 界面 | 现有权限、工具发布、Agent 工作空间 | 可用模板/我的发布/Agent 工具绑定，以模板选择代替执行器配置 | 内部技术工具只留在运维/诊断入口 |
| 清理 | 仍有调用方的框架与兼容入口 | 删除确认无引用的旧前端依赖配置，收拢重复流程 | 数据库不做破坏性删表/删列 |

## 验证边界

测试须覆盖同租户不同用户、跨租户、模板和资产撤销、Agent 解绑、直接构造工具名称、版本失效、重复发布，以及已有各执行类型的回归。发布不是授权：模板使用权、发布权、Agent 管理权与调用时的数据范围分别校验。发布版本不得静默跟随模板更新。

生产部署前应留存应用与数据库精确备份；先通过增量迁移、后端集成及前端浏览器检查，再验证实际 MCP tools/list 与 tools/call。未完成的验收必须标明，不以界面隐藏代替后端拒绝。
