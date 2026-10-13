# T01：现有架构审查

审查日期：2026-10-13（Asia/Shanghai）。代码基线：`c876b184`，分支 `main`。开始审查时 `git status --short` 为空。

本文件保留第一轮只读审查结论。用户已确认第二轮 T03～T08 实施，实际实现及影响见 [架构实施补充](02-workspace-architecture.md) 和 [实施审查清单](04-implementation-review.md)，回归结果见 [测试报告](03-test-report.md)。未授权生产部署。

本轮遵循任务第十三节：先完成只读审查和设计，业务实现及部署留待方案确认。没有发现已提交的多产品 Workspace Registry、Shell、Manifest 或本目录的既有交付文档；不能从“早上剩余”推断这些任务已完成。线上 `/api/v1/workspaces` 返回 404，也不能视为已有注册接口。部署凭据不写入文档。

## 1. 模块与架构边界

根 [pom.xml](../../pom.xml) 使用 Java 17、Spring Boot 3.5.3，包含 API、enterprise、chat、agents、common、platform-spring、runtime-skill、runtime-mcp、runtime-temporal、integration 等模块。

| 部分 | 代码证据 | 职责及本次边界 |
| --- | --- | --- |
| 协议与端口 | `chatchat-common/src/main/java/com/chatchat/common/runtime/` | 通用协议、事件、证据端口；不得放 Python/SQL 业务或 Workspace ID 分支 |
| Agent 执行 | `chatchat-agents/src/main/java/com/chatchat/agents/runtime/`、`orchestration/analysis/graph/ModelNativeAnalysisHarness.java` | 模型分析、计划、执行与工具治理；继续复用，不重建 Loop |
| Runtime 调度 | `chatchat-agents/.../runtime/config/AgentRuntimeExecutorConfig.java` | 注册共享执行器和条件装配的 `WorkflowRuntime`；Workspace 切换不得重新装配 |
| 会话与任务 | `chatchat-chat/.../conversation/`、`task/` | 持久化会话、任务快照、事件及交付；不可建立 Workspace 专属任务状态机 |
| 身份与权限 | `chatchat-enterprise/.../entity/identity/`、`service/EnterpriseAdminService.java` | 用户、租户、组织、角色、权限、菜单；作为统一身份与 RBAC 来源 |
| Web 适配 | `chatchat-api/src/main/java/com/chatchat/api/` | HTTP 认证、API 权限、Python Studio 和 Runtime 适配；Workspace 服务应位于应用层 |
| 前端 | `chatchat-api/web-app/` | 单 Vue/Vite 工程；复用现有布局和异步组件 |

现有 [runtime-single-brain-data-workspace.md](../runtime-single-brain-data-workspace.md) 的 Data Workspace 指数据集、SpillStore、批量推理等分析执行上下文。本次 Workspace App 是用户产品界面，两者不能合并或互相替换。

## 2. 前端结构与路由

[App.js](../../chatchat-api/web-app/src/js/App.js) 在本文件中维护 `views` 映射，以 `window.location.hash`、`hashchange` 和导航方法切换组件；没有 `src/router/index.js`，依赖中没有 Vue Router。各页面使用 `defineAsyncComponent` 懒加载。

[App.vue](../../chatchat-api/web-app/src/App.vue) 包含登录页、`AssistantLayout`、`KeepAlive` 页面容器、浮动新对话按钮、`RightPanel`。布局同时接收菜单、会话历史、收藏、用户和任务相关事件。

可直接复用：组件映射、旧 hash 地址、异步加载状态、菜单权限判断、现有布局。需要扩展：统一解析 Workspace 路由、选择页、切换器、Workspace 菜单投影、按用户与 Workspace 保存临时上下文。初期无需引入路由框架或微前端。

耦合点：`App.js` 集中维护导航、会话选择和待提交草稿；`RightPanel` 与新对话按钮默认面向现有智策界面。不能仅替换菜单文字来声称完成不同 Workspace 的布局和能力隔离。

## 3. 登录与 Token

- 前端 [api.js](../../chatchat-api/web-app/src/services/api.js) 用 `chatchat.auth.session` 保存 localStorage 会话，HTTP 请求带 `Authorization: Bearer`，通过统一认证失效事件返回登录页；流式、上传与下载存在独立请求路径，扩展 Workspace 上下文时需分别检查。
- `EnterpriseAdminService.login()` 校验用户状态和密码，创建随机种子生成的 Token；`activeTokens`、`activeTokenUsers` 是进程内 `ConcurrentHashMap`，不是已持久化 JWT 会话。
- [ApiAuthenticationFilter](../../chatchat-api/src/main/java/com/chatchat/api/security/ApiAuthenticationFilter.java) 默认启用，在请求上设置用户 ID、用户名、租户和身份快照。普通 Session 与 Agent API Token 区别处理，后者仅允许已发布 Agent 的指定问答接口。
- `App.js` 具有 30 分钟空闲退出逻辑和登录后 `/enterprise/auth/me` 刷新。

可复用同一个登录会话访问多个 Workspace，切换不能调用登录或退出逻辑。部署重启导致进程内普通 Token 失效属于已有兼容风险，不能承诺升级期间所有用户免登录，也不能借本次任务重写认证。

## 4. 用户、角色、组织与 API 权限

身份模型包括 `SysUser`、`SysTenant`、`SysOrg`、`SysRole`、`SysUserRole`、`SysPermission`、`SysRolePermission`、`SysRoleOrgScope`；`EnterpriseAdminService` 根据数据库角色关系计算有效权限，并提供现有角色授权管理接口。

[ApiPermissionAuthorizationFilter](../../chatchat-api/src/main/java/com/chatchat/api/security/ApiPermissionAuthorizationFilter.java) 在认证后匹配启用的数据库权限策略，按 HTTP 方法及路径具体程度选取策略，再检查有效权限码。**无匹配策略直接 403**；普通前端可见性不能替代它。

影响：每个新增 Workspace HTTP 入口均要有数据库 API policy；用户可访问列表和管理员完整注册列表必须分开；`/enterprise` 的既有宽路径不能被误用为向普通用户开放 Workspace 管理接口。Workspace 访问许可必须由服务端独立维护，不能相信 Manifest 的能力数组。

## 5. 菜单配置与权限

`SysMenu` 已持久化 `menuCode/menuName/menuType/routePath/icon/sortOrder/status/parentId`。`SysMenuPermission` 关联权限，`EnterpriseAdminService.listAuthorizedMenus()` 根据有效权限过滤菜单并补目录祖先。`EnterpriseAdminController` 已有 `/enterprise/menus`、`/menu-configurations` 的查询和增删改接口。

前端将服务端菜单树转换为导航项，并由 `canAccessView()` 判断页面可访问性。菜单请求失败时清空导航，不自动授予默认菜单权限。

建议复用菜单定义及管理界面，让 Manifest 引用已存在的菜单标识；Workspace 查询结果再与现有授权菜单取交集。无需复制菜单表、角色表或实现第二套菜单 RBAC。

## 6. Python Studio 复用

[DataScienceView.js](../../chatchat-api/web-app/src/js/views/DataScienceView.js) 将 environment、develop、data、scripts 四个 section 放在同一页面；旧的 `dataScienceEnvironment/dataScienceDevelop/dataScienceData/dataScienceScripts` 路由复用同一组件，`initialTab` 选择子页面。Monaco 编辑器、AI 助手、脚本版本、执行、数据上传下载和布局已经存在；领域 Skills 用单独 `DomainSkillsView`。

后端 `PythonDataScienceController` 从认证请求取租户及用户，`PythonDataScienceService` 按 tenant/owner 查询，读写脚本与文件调用 `ownedScript/ownedDataFile/ownedReadyAsset` 等方法。Python 环境、预览执行、文件及模板发布通过 `McpPythonControlPlanePort` 访问现有 MCP 能力。

`PythonCodeAssistantService` 使用现有模型工厂和 `DomainSkillRuntimePort` 生成代码。它是现有专用编程服务，不是需要重新实现的 Agent Loop。保留行为与调用方式。

扩展注意：Python 当前 owner 优先使用认证用户名，而角色/资源授权使用用户 ID；新增 Workspace preference/origin 应使用稳定用户 ID，不能改写已有 Python owner 或批量迁移历史。布局键 `chatchat.python-studio.layout.v1` 是全局键，需要按租户、用户、Workspace 隔离。

## 7. Agent、Skills、MCP 与资源授权

- 模型自主分析由现有 Agent Runtime 与 `ModelNativeAnalysisHarness` 承担，页面只是提交、观察和呈现入口。
- `EnterpriseToolRuntimePolicyProvider` 从用户、角色、工具资产、工具权限和资源授予记录判断执行资格。
- `ResourceAuthorizationService` 校验租户、有效用户与角色，对候选资源进行 ALLOW/DENY 计算；没有有效 ALLOW 的资源不会自动开放，DENY 优先。
- `DomainSkillService` 使用 `ResourceAuthorizationPort` 的显式授权查询，包含 Agent 场景的授权；Workspace 默认 Skills 仍须走该机制。
- 当前 Runtime semantic policy 与 MCP argument binding policy 是专用的已发布运行契约。它们不是通用产品配置库，不能塞入 Workspace 菜单或 SQL 工作流。

可直接复用资源发现、授权、模板绑定、Skill/MCP 发布与调用链。新增能力声明只能限制或建议当前已授权能力，不能注入新的执行资格、任意工具入口或绕过资产绑定。

## 8. 持久化与生命周期

| 状态 | 现有机制 | Workspace 影响 |
| --- | --- | --- |
| 会话与消息 | `ChatSessionEntity`、`ChatMessageIndexRepository`、`ConversationSummaryEntity`，tenant/user/sourceChannel | 没有产品 workspaceId；旧数据保持原 ID 与来源通道 |
| Agent 任务 | `AgentTaskLatestEntity`，tenant/user/session/execution/attempt、状态、租约及幂等键 | 增加来源标签不应改变任务身份、重试与调度 |
| 产物 | `UiArtifactEntity/UiArtifactService`、证据存储端口与现有适配 | 不移动文件，不重建产物仓库 |
| Python | assets/scripts/folders/versions/executions/data files 的 JPA 实体，MCP 文件存储 | 原记录和真实执行服务复用 |
| 浏览器运行恢复 | `chatRuntimeState.js`，单一 localStorage 键，30 分钟 TTL，非终态条目 | 键和条目缺少 Workspace/用户维度，需要兼容升级 |
| 编辑状态 | Vue `KeepAlive`，Python Monaco 实例与表单状态 | 要验证切换、失活、缓存淘汰及刷新；仅 KeepAlive 不保证刷新后草稿恢复 |

`ChatAssistantView.beforeUnmount()` 关闭浏览器任务事件流，没有在该钩子直接取消后台 Agent 任务。此源码事实不等于已实测所有后台执行在切换或断网后都继续；需真实长任务回归。Python 执行为请求/响应式调用，不能把 Agent 的持久任务保证套用到所有 Python 操作。

## 9. 实施前必须处理的问题

1. **对象权限边界缺口**：`ConversationController` 根据认证租户查询，但创建及用户历史入口仍接受请求 userId，按会话 ID 读取、改名、删除调用只有 tenant 参数；`ConversationService.ensureConversationId()` 对已有会话仅检查租户。所审查入口没有完整的当前用户所有权判断。`UiArtifactController` 也仅向服务传 tenant。不能宣称完整用户级对象隔离，不能以新增 Workspace header 自动解决。应先梳理私有与合法共享对象的服务端访问契约，补统一对象访问检查及负例测试。未经方案确认不修改共享策略。
2. **没有已验证的通用 Registry 配置存储**：已审查的菜单、模型、趋势、semantic policy 配置均有领域模型。推荐沿用 JPA/config CRUD 方式建立很薄的产品注册数据，不能污染运行策略表。
3. **认证兼容**：进程内 Session 在重启后失效；Workspace 切换应免重新登录，但发布重启的现有限制必须如实说明。
4. **测试基线未全绿**：架构测试引用旧路径；会话测试上下文缺 semantic policy。先修测试夹具/路径，真正架构依赖断言需独立核对，不能放宽或删除测试来获得绿灯。

## 10. 实际验证与影响范围

结果详见 [03-test-report.md](03-test-report.md)：前端 62 项定向测试通过；前端 production build 成功；后端定向 49 项，34 通过、1 failure、14 errors。后端打包结果单独记录，测试失败不因打包通过而抹去。

线上认证检查使用提供的 API/MCP 管理员账号：两个登录接口与各自 me 接口 HTTP/业务码均为 200；API `/api/v1/health` 为 UP；授权菜单返回 3 个组。SSH 只检查容量、端口和部署文件，没有替换文件、改数据库、重启服务或触发业务任务。登录会产生正常 Session 和登录审计记录。

影响矩阵：低风险是文档、选择器和已有菜单投影；中风险是 hash 兼容、KeepAlive/草稿隔离、注册/偏好数据迁移；高风险是新增 API 策略、对象权限、会话来源记录和发布重启。T03–T08 必须分步验证，高风险部分不能用前端隐藏或业务硬编码代替系统能力。
