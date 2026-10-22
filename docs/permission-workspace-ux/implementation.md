# 权限管理工作空间整改交付说明

## 导航对比

| 原入口 | 新入口 |
| --- | --- |
| 平台管理 → 系统管理 → 用户管理 | 权限管理 → 用户管理 |
| 平台管理 → 系统管理 → 组织管理 | 权限管理 → 组织管理 |
| 平台管理 → 系统管理 → 角色管理 | 权限管理 → 角色权限 |
| 系统管理 → 资源授权、技能权限；工具与数据授权 | 权限管理 → 资源授权 |
| 系统管理 → 登录审计 | 权限管理 → 安全审计（登录行为、授权变更） |
| 工作空间设置菜单缺失 | 平台管理 → 工作空间设置 |

权限管理采用五个平级页面；旧技能权限、工具授权地址归一到资源授权。角色与资源页面分别保存选择状态，使用当前登录身份租户加载数据。工作空间切换器继续用于账号切换空间，工作空间设置使用现有版本控制管理接口。

## 页面与交互

- `PermissionConsoleView`：角色与资源两种视角共用目录与授权编辑能力；左侧搜索列表，右侧详情；分页、类型筛选、批量授权、明确的加载和保存反馈。
- `PermissionResourceEditor`：角色、用户、租户规则及继承关系，显示授权动作、来源、范围和启用状态；拒绝/撤销需确认。工具执行规则与资源访问约束并列呈现，避免隐藏另一层已有规则。
- `SystemManagementView`：保留用户、组织和登录审计能力，清除旧角色/Agent 授权编辑分支与统计卡，增加用户/组织搜索、状态筛选和分页。
- `PermissionAuditView`：保留登录审计，增加现有审计表中的授权成功及失败记录查询。
- `WorkspaceSettingsView`：恢复名称、状态、默认空间、入口页等已有配置能力；技术编码放入高级详情；通过 `expectedVersion` 防止覆盖并发修改。

角色权限按工作空间、功能、工具、数据源、知识库及其他资源分组。模型管理属于真实的功能权限；没有虚构独立的模型访问授权存储。后端没有角色父子层级模型，界面展示实际租户/角色/用户继承，不宣称存在角色层级继承。

批量角色权限变更以最新授权为基础合并增减项，保留列表未展示的权限、成员、组织范围和本地查询服务绑定。发现已有服务/表达式范围的资源时，批量操作阻止覆盖，提示进入资源详情编辑。部分失败保留未完成选择并显示成功数量。

## 删除清单

删除以下旧页面及对应 JS：`SystemRolesView`、`SystemResourceAuthorizationView`、`SystemSkillRoleAuthorizationView`、`SystemLoginAuditView`、`ResourceAuthorizationPanel`、`SkillRoleAuthorizationPanel`、`PermissionToolsView`。

删除专用样式 `resource-authorization.css`、`skill-role-authorization.css` 和旧面板测试；删除无使用方的 `fetchSkillRoleQuery`、`SkillRoleQueryController`、`SkillRoleQueryService` 及其测试。新角色/资源页承担原双向查询功能，安全审计承担原登录审计功能。

没有增加运行时依赖库，没有新增权限表或权限引擎。既有 HTTP、认证、模型调用、Runtime、MCP、Coding Agent、Python Sandbox 不受旧页面清理影响。

## 权限服务复用与边界

写入继续使用既有角色授权、`permission-center/tool-grants`、`enterprise/resource-grants` 接口。统一目录只组合既有权限、授权、资源描述及身份数据；读权限按已有管理能力开放，写权限不降低。增加角色管理的租户边界检查，保留既有平台管理例外，禁止将角色移动到其他租户。

成功变更写入现有 `sys_audit_log`；被拒绝/失败的授权请求记录操作者、租户、方法、路径、状态，不记录请求体、令牌或凭据。审计不可用时记录服务告警。

界面授权状态是显式规则投影。运行时继续执行原有身份、租户、工作空间、资源、工具及数据范围校验，拒绝规则与资源自身限制仍然生效。页面筛选和模型能力识别不能授予访问权。

## 数据迁移与回滚

- 只读盘点：`database/audit/permission_workspace_cleanup_inventory.sql`。
- 正向：`database/migration/postgresql/V20261023_01__permission_workspace_ux.sql`。
- 回滚：`database/rollback/postgresql/R20261023_01__permission_workspace_ux.sql`。
- 应用启动迁移：`PermissionWorkspaceUxMigration`，与 SQL 共用幂等标记。

迁移只调整菜单、菜单权限绑定和 workspace manifest；先将原值写入现有归档表，修复工作空间设置的既有管理权限绑定，删除废弃导航元数据。保留自定义 manifest 字段，不改用户、角色、业务授权或 MCP 模板绑定。旧菜单存在自定义子项时阻止迁移；回滚发现后续菜单或 manifest 修改时阻止覆盖。

部署脚本 `scripts/deploy-permission-workspace-ux.py` 在停服前构建镜像，确认无运行中分析任务，备份 API/MCP 数据库、运行 JAR、compose 和环境配置；检查运行 JAR 哈希及业务授权指纹。只替换 API，继续使用原 MCP 镜像；部署异常自动恢复旧镜像与导航元数据。恢复备份属于独立运维手段，不应在有后续业务写入时直接覆盖数据库。

## 保留项及原因

1. 3 条带本地查询服务限定的资源授权均存在有效角色绑定：`financial-news-agent`（技能）、`financial_document_analysis`（文档）、`acceptance_private_knowledge_20261018`（文档）。名称相似不能作为删除依据，继续保留。
2. 原生本地查询服务绑定和资源范围约束仍被 Runtime 使用，界面称“查询服务”，与已移除的外部专业 Agent 编排体系区分。
3. 旧专业 Agent 数据继续保留于前一版本的受限归档中，用于回滚，不恢复管理入口或执行链路。
4. 不可变历史迁移保留审计可追溯性；当前导航种子不再创建已删除入口。
5. 合成工具的已下线发布版本保留为发布审计历史；验收结束删除临时身份和授权，不删除发布审计。
6. 通用菜单表中的旧 `system` 目录元数据保留，不再作为工作空间页面或 Sidebar 入口；实际五个管理页面均属于新权限目录，避免误删管理员自定义菜单关系。

## 执行链路

自然语言问题 → Runtime 识别任务并校验身份/工作空间/资源 → 知识检索或受控 MCP 工具 → 数据分析任务交由 Coding Agent → Python Sandbox 执行与反馈 → Runtime 汇总结果/图文报告。没有专业 Agent 选择、发现、邀请或联合推理分支。本次仅重构管理信息架构与交互，继续复用上述执行链路。
