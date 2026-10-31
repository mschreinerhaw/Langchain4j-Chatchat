# 角色资源授权修复与验收

## 问题与处理

普通角色进入聊天后显示“无权访问该资源”，并非角色没有绑定 Agent：页面加载列表、发送前刷新配置使用了 Agent 管理接口；聊天任务提交、结果和事件读取也缺少普通聊天功能的接口权限映射。另有资源授权页维护的 `AGENT_SKILL` 规则未进入原有角色 Agent 绑定判断，以及登录令牌保存旧权限快照的问题。

- 新增普通用户只读目录 `/api/v1/interactions/resources/agents`、`/agents/{id}` 和 `/skills`。只返回已发布、当前身份已授权的资源；管理接口保留原权限。Skills 先按资源授权过滤，再计算分类、数量和分页。
- 聊天与调度选择 Agent 使用只读目录。发送前刷新使用同一授权边界，读取实际执行配置。
- 聊天权限允许提交任务及访问明确列出的 UUID 任务生命周期接口；原有 `ObjectAccessInterceptor`、`ObjectRequestAdvice` 继续校验任务、会话、租户和用户归属。租户级监控、任务批量列表、管理操作没有因此开放。
- 普通聊天用户可以读取自己的待办，以及只读的金融图表显示口径。本人生成报告的清单和资源读取纳入聊天功能权限；报告内容仍通过原有持久化任务归属校验，其他用户、其他租户及无归属报告继续拒绝。报告删除和显示口径修改不随读取权限开放。
- 普通用户提交任务必须选择已发布且已授权的 Agent，禁止空 Agent 或未知 ID 回退到未授权默认 Agent。
- Agent 授权合并旧角色绑定和资源规则的允许项；当前用户/角色/租户、有效期、启用状态及显式拒绝继续生效。保留原有显式“全部 Agent”权限语义。
- 运行时共享资源校验也读取有效的旧角色 Agent 绑定与明确的“全部 Agent”权限，作为持久化的显式允许来源；显式拒绝依旧优先。`SkillExecutionScopePort` 的真实数据库集成断言验证可以进入 Agent 执行，同时不会因此获得未绑定文档。
- 登录令牌仅保存身份，会话解析读取当前用户与角色权限；授权调整和用户禁用不再依赖旧的登录快照。
- MCP 工具授权保存时，在同一事务内维护共享资源校验所需的内部伴随规则；修改、禁用和删除同步生效。已有规则由带持久化标记的一次性迁移补齐，重启不会反复恢复已撤销规则。数据范围仍由原工具执行策略校验，范围内的拒绝不会被转换成全工具拒绝。权限页面不把内部伴随规则显示为可单独编辑的重复授权。
- Skills 与 MCP Skill 来源管理按数据库角色权限 `capability:data-science` 判断，取消只识别 `admin` 用户名的限制。读取单个授权 Skill 不等于获得管理、导入或编译权限。

工作空间仍按注册的空间访问权限、必需能力和许可证判断；文档仍使用服务端可信身份和共享文档授权。没有改动原生 Skills 格式、现有编译器、Java 需求分析、Agent 资源绑定或 Coding Worker 隔离策略。

## 自动化检查

专项测试共 **75 项 Java 测试、95 项前端测试通过**，前端生产构建及完整应用 JAR 打包成功。

Java 范围：`EnterpriseAdminServiceIntegrationTest`（18）、`ResourceAuthorizationServiceTest`（10）、`McpRoleGrantMigrationTest`（2）、`AgentAuthorizationIsolationTest`（3）、`ApiUserRoleScheduleMcpAuthorizationIntegrationTest`（2）、`AuthorizedResourceCatalogControllerTest`（1）、`ApiPermissionAuthorizationFilterTest`（14）、`AgentTaskSubmissionAuthorizationTest`（4）、`ObjectAccessPolicyTest`（14）、`PermissionCenterControllerTest`（4）、`EnterpriseAdminProtectedUserTest`（3）。同一测试在不同轮次执行只计一次。

真实身份域的 API 集成测试覆盖：没有管理权限的普通用户读取授权 Agent、刷新指定 Agent、读取自己的任务、拒绝其他用户任务与租户监控、撤权后原令牌立即失去访问权限，以及非 admin 账号通过明确管理权限管理 Skills 分类。

额外尝试的既有 `AgentTaskControllerTest`（14 项）未能启动测试上下文：该夹具模拟了 `EnterpriseAdminService`，启动迁移缺少真实权限/菜单种子，先后报 `CapabilityPermissionMigration` 缺少权限、`Workspace settings menu missing from platform seed`。没有将这些项目计为通过，也未为迁就该夹具放宽生产迁移逻辑；原测试文件保持原样。本次新增提交权限单测和使用真实身份表的 API 集成测试覆盖本次变更。

## 远程部署与验证

目标：`http://192.168.195.221:18100`。

发布镜像：`runtime-os-api:role-authorization-20261031-r3`。

应用 JAR SHA-256：`57a31c9f102ddcf71085f467c8b4a3c2cf3cdfe639980d6159f854de1ae91d16`。

发布目录：`/opt/chatchat-releases/role-authorization-20261031-r3`。部署沿用完整 JAR 校验、数据库与容器配置备份、空闲任务检查和回滚机制；Coding Worker 与 MCP 镜像保持原版本。已验证 JAR 内首页与最新前端生产构建的 SHA-256 一致（`56669af6c7e2ef2449ed3908b8c8c567a67401fa00687915be6ff01f2ea8e5ab`）。

远程使用独立临时角色/账号进行验证，不修改现有 `test` 用户的密码、角色授权或资源范围。原角色绑定中的已回收 Agent 不应出现在普通用户可用列表中。

最终浏览器使用与现有 `test` 账号相同的业务角色：实际任务 `d84e9420-510e-47c4-92c8-64f46b724daf` 成功，回答“角色授权功能已生效”并正常显示报告。报告清单及内容读取均为 200，浏览器没有页面异常、失败 API 或权限错误提示；另行验证本人报告 200、其他用户报告 403。验收问题为不调用工具的提示语，MCP 另用只读、预期无命中的合成查询验证，不代表执行了客户金融数据分析。

空间撤权、Agent/文档/Skills 显式拒绝、MCP 工具撤权及恢复均通过；空间权限变更在原令牌上即时生效。临时角色、用户及授权配置已删除，原令牌随后返回 401；既有 `test` 用户角色和业务角色授权与改造前一致。验收任务及审计历史保留。

远程结果及清理状态以同目录 `evidence.json` 为准。原始响应、登录凭据和截图仅留在忽略的本地验收目录，不进入 Git。
