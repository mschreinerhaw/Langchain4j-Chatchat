# T01：Runtime OS 现有架构审查

审查日期：2026-10-12。代码基线：`c876b184`。范围为本地实际代码、配置、已有测试；本轮仅新增文档，不改业务代码、不改数据库、不部署。未连接生产服务器，因此以下不能代替线上数据、部署配置和权限矩阵的核验。T03～T10 尚未实施。

## 1. 总体判断

可以采用“一个 Runtime OS Core，多个 Workspace App”。现有认证、RBAC、菜单、Agent Runtime、Skill/MCP 授权、任务与会话存储都有可复用的实现。主要扩展应落在企业配置、API 入口和前端 Shell；无需新增 Agent Loop、模型调用框架或工具调度器。

当前系统尚无通用 Workspace Registry、空间路由或服务端空间上下文。现有页面缓存、根组件对话状态和部分浏览器存储键没有空间作用域。另有若干任务、会话、产物接口以租户为对象访问边界，不能仅凭统一 RBAC 就宣称已实现用户及空间级对象隔离。实施前必须明确这些接口的个人访问与管理员访问语义。

## 2. 前端结构、路由及组件复用

| 实际代码 | 观察 | 处理方向 |
| --- | --- | --- |
| `chatchat-api/web-app/package.json`、`src/main.js` | 单 Vue/Vite 工程；未引入 vue-router、Pinia，入口直接挂载 App | 保留单工程，首期不增加微前端或整体替换路由框架 |
| `src/js/App.js`：`views`、`viewFromHash`、`handleHashChange`、`navigateToView` | 静态页面注册表配合自建 Hash 路由；旧地址为 `#/chat` 等；`dataScience`、`system` 有别名；未登录跳转保存在 sessionStorage | 在已有路由上增加解析及兼容适配器；新空间路由不能直接交给旧的页面白名单解析 |
| `src/App.vue` | AssistantLayout 外壳包裹动态组件，使用无显式 key 的 KeepAlive；右侧面板和对话事件由 App 统一管理 | 增加空间选择器和切换入口；组件缓存按身份、空间、页面族区分 |
| `src/js/App.js`：`canAccessView`、`loadEnterpriseMenus` | 页面可达性由服务端菜单映射得到；页面组件仍由本地白名单决定 | Workspace 菜单与现有授权菜单取交集，不替换 RBAC |
| `src/js/views/DataScienceView.js` | 环境、开发、数据、脚本复用同一组件，通过 initialTab/导航切换；Monaco 模型、编辑页签、表单保存在组件中 | 复用现有工作台，四个页签保持同一个空间内的工作台实例；不为各页签重建编辑器 |
| 同上：`activated`、`beforeUnmount`、`workspaceLayoutStorageKey` | 激活时重新布局编辑器；卸载时 disposeEditor；布局键固定为 `chatchat.python-studio.layout.v1` | 切空间采用停用/缓存，区分切换与退出登录；新布局键加入租户、用户、空间作用域 |
| `src/services/api.js`：`workbenchRequests` | `/data/workbench` 请求短时复用，缓存 key 只有 path；不是 Python workbench 的缓存 | 增加认证身份和空间作用域，并在退出登录时清理；避免跨身份或空间复用响应 |

这里的文件路径除特别注明外，`src/` 均相对于 `chatchat-api/web-app/`。

Python Studio 内部已有名为 workspace 的布局变量，它表示 IDE 布局，不等于本次 Runtime OS Workspace，不能按变量名称认定机制已经存在。领域 Skills 页面在 App 中也已复用，可作为数据科学空间页面注册；AI 编程助手保留现有 PythonCodeAssistantService 和调用路径。

## 3. 认证与 Token 管理

实际依据：

- `chatchat-api/src/main/java/com/chatchat/api/security/ApiAuthenticationFilter.java`。
- `chatchat-enterprise/src/main/java/com/chatchat/enterprise/service/EnterpriseAdminService.java`：`login`、`resolveUserByToken`。
- `chatchat-api/web-app/src/services/api.js`：`getAuthSession`、`storeAuthSession`、`apiRequest`。

普通登录 Token 是随机种子编码生成的 opaque Token，并非 JWT；服务端普通登录映射保存在 `activeTokens` / `activeTokenUsers` 内存 Map。嵌入登录另有持久化 EmbedLoginToken。前端将会话存入 `chatchat.auth.session`，请求使用 Bearer Token；App 已有闲置退出、认证失败处理及登录后菜单恢复。

认证过滤器先验证 Token，写入当前用户、用户名、租户等 request attributes。Agent API Token 的可调用路径受到专门限制，不能借 Workspace 接口扩展为管理凭证。Workspace 切换复用同一登录身份及 Token，不触发再次登录或重建会话。

兼容风险：普通 Token 在后端重启后失效是当前实现的属性；不能承诺部署过程中保留登录。此次不改变认证存储或引入新的 Token 格式。App 的 userId 优先取 username，而后端认证属性为用户实体 ID；新增偏好与资源关联必须使用服务端 canonical user ID，不能直接复用前端历史显示标识建外键。闲置退出也不能被空间切换绕过。

## 4. 用户、角色、组织、功能权限和菜单

实际实体位于 `chatchat-enterprise/.../entity/identity/`：SysTenant、SysUser、SysOrg、SysRole、SysUserRole、SysPermission、SysRolePermission、SysRoleOrgScope、SysMenu、SysMenuPermission。

`EnterpriseAdminService` 负责角色权限和组织范围、授权菜单树及菜单管理；`EnterpriseAdminController` 已有登录、当前身份、菜单与菜单配置等接口。`listAuthorizedMenus` 根据有效权限和菜单权限关联生成菜单，并补齐树祖先。补齐祖先只用于展示，不等于获得所有子功能权限。

`ApiPermissionAuthorizationFilter` 位于认证过滤器之后：依据数据库中启用的权限资源路径、HTTP 方法和路径匹配确定接口权限；未匹配启用权限策略时返回 403。权限匹配采用路径具体程度排序，新增端点必须核对现有宽路径策略和方法覆盖，不能仅新增一个前端菜单。

SysMenu 已有唯一 menuCode、routePath、父子关系、图标、排序和状态，无 workspace_id。建议 Manifest 引用现有 menuCode/pageKey 并声明空间内编排，不复制整套菜单/角色，不给 SysMenu 强行增加一对一空间归属，保证同一功能能在多个授权空间复用。

## 5. 配置管理及注册能力

实际检查了企业实体、UI 配置、Skill 和 MCP 配置实现：

- `UiDisplayConfigController` / `TrendSemanticConfigEntity` 是租户级趋势颜色等专用配置，字段固定，不是任意 JSON 配置中心。
- SkillConfigEntity、McpServiceConfig 等都是特定领域配置。
- `CapabilityRegistryEntry` / `CapabilityManifestVersion` 已保存能力及其版本，但 Registry 必需 serviceId、toolName，并参与 MCP 能力治理，不是 Workspace App 注册表。
- 本次审查未发现适合直接承载 Workspace Manifest 的通用配置实体或 sys_config 实现。

可复用现有 JPA Repository、企业管理控制器风格、乐观锁、审计与 RBAC。建议增加轻量 Workspace 配置实体，而不是将 UI App 伪装成 MCP capability 或扩大 Runtime 语义策略的职责。具体简化方案见 T02。

## 6. Agent Runtime、Skill、MCP 与页面耦合

| 实际代码 | 系统职责 | Workspace 边界 |
| --- | --- | --- |
| `chatchat-chat/.../task/core/AgentTaskService.java` | 提交、队列协作、状态、确认、取消、恢复，调用共享 AgentRuntime 与 InteractionOrchestrationService | 保持现有执行器、队列和状态机；空间只记录来源和控制入口访问 |
| `chatchat-agents/.../runtime/AgentRuntime.java` | Agent 执行 | 不为每个 Workspace 注册新的 Runtime Bean 或线程池 |
| `chatchat-chat/.../skills/runtime/DatabaseRuntimeSkillPolicy.java`、`DatabaseSkillExecutionScopeService.java` | Skill 及其资源范围与授权交集 | defaultSkills 是偏好提示，不能直接赋权 |
| `chatchat-integration/.../mcp/service/routing/McpToolRegistryBridge.java` | 在现有身份及 Agent 范围内检查 MCP 工具授权 | 不以 Manifest 工具列表替代发现、授权或工具合同治理 |
| `chatchat-enterprise/.../service/ResourceAuthorizationService.java` | 从数据库身份、有效角色、资源 grants 计算允许集；DENY 优先；不信任调用方传入角色 | 保留该服务及领域 ACL 的交集规则 |
| `src/js/views/ChatAssistantView.js` | 维护运行上下文、任务事件订阅、会话恢复和 MCP 确认 UI | 这是前端展示耦合，不应搬迁 Agent 执行到 Workspace Shell |

ChatAssistantView 的 beforeUnmount 会 abort 任务事件流；这里未调用服务端 cancel。前端关闭订阅与后台任务取消应继续区别。现有 App 根状态包含 selectedConversation、pendingChatDraft、activeHistoryId、历史列表等，缺少空间区分；只改页面 key 不足以完成上下文隔离。

Workspace 所需能力用于入口和功能可用性判断，不应变成固定业务执行流程，也不能限制模型只能调用 Manifest 枚举的工具。模型仍通过原有机制自主发现并使用其已获授权的资源。

## 7. 持久化、任务、会话、文件和产物

| 对象 | 实际实现 | 复用与限制 |
| --- | --- | --- |
| 会话 | ChatSessionEntity / ConversationService：关系库 chat_session，有 tenant_id、user_id、source_channel；消息详情另有 ChatMessageDetailStore，默认 RocksDB 实现及文本存储协作 | 保持 session ID、消息键和旧历史；未发现 workspace 字段 |
| 任务 | AgentTaskLatestEntity：agent_task_latest，有租户、用户、会话、执行/尝试 ID、请求 JSON、队列租约等 | 继续使用原表及状态机；幂等约束为 tenant_id + idempotency_key，不能静默改变 |
| 任务事件 | DatabaseAgentEventStore 默认启用；另有显式配置的 RocksDbAgentEventStore / InMemory 实现 | 不能误称全部任务只存 RocksDB；Workspace 不另建事件流或重写序列 |
| Python 资源 | PythonAsset/Script/Folder/Version/DataFile/Execution/Template 实体；PythonDataScienceService 使用 tenant + owner 查询/校验；实际执行经 McpPythonControlPlanePort | 不复制环境、脚本或执行历史；空间不会扩大 owner 权限 |
| 文件与产物 | Python 上传/下载有现有所有权检查；UiArtifactController 经租户读取 UiArtifactService manifest/resources | 不迁移文件目录、内容或产物 ID；不同资源类型需分别验证对象访问范围 |
| 浏览器临时状态 | KeepAlive、编辑器表单、全局根组件状态、localStorage/sessionStorage | 增加空间作用域；缓存不作为权限证据，不存新的明文 Token 副本 |

需要特别审查的授权差异：AgentTaskController 的 get/list/events/stream/cancel 等主要传递服务端确定的 tenantId；todos 另有用户范围和 `platform:tasks:all` 判断。ConversationController 部分列表接受 URL userId，详情按租户及 session ID 获取。UiArtifactController 按租户及 artifact ID 获取。统一功能权限存在，但这些路径没有统一的当前用户对象归属判断。

这些观察不等于已证明线上存在越权漏洞：部署时角色权限可能将某些路径限制为租户管理员。它们意味着 T05 不可仅加空间 header 就宣布跨空间隔离完成。必须核对实际权限种子与角色矩阵，保留合法的管理员查看能力，并为个人空间对象设计独立服务端判定；若无法区分管理员与个人接口，暂停相关入口上线，采用空间聚合查询/适配层方案，不直接全局收紧历史接口。

## 8. 数据库与迁移风险

实际配置 `datasource-{postgresql,mysql,h2}.yml` 均使用 `ddl-auto: update`；仓库已有三个方言的 database/init 与 database/migration，migration/README 要求先执行对应脚本。不能因为脚本名以 V 开头就认为存在已验证的自动 Flyway 升级链。

新实体必须提供显式、可重复执行的增量脚本以及新安装种子。不得删除旧列、改主键、重建任务/会话/资源表或回填所有历史数据后才允许启动。上线前需核验生产数据库方言、已有权限、DDL 锁影响、备份与脚本执行方式；本轮未执行迁移。

## 9. 复用、扩展及影响清单

| 分类 | 结论 | 可能影响 |
| --- | --- | --- |
| 直接复用 | 登录/Token、用户角色组织、菜单管理、资源 grants、Agent Runtime、Skill/MCP 治理、任务队列、Python 工作台和持久化 | 通过既有契约调用，保留旧 ID 与数据 |
| 小范围扩展 | Workspace 注册/偏好、服务端空间策略、Manifest 校验、Hash 路由适配、Shell 切换器 | 新接口需要权限种子；注册状态与默认空间需一致性控制 |
| 耦合需处理 | App 根状态、未带空间 key 的 KeepAlive、IDE 布局键、工作台缓存、导航/AI 助手事件 | 状态错误串入其他空间；同一 IDE 内的页签可能被错误重建 |
| 上线阻断条件 | 对象权限语义无法确认、注册表跨租户约束未验证、迁移无法兼容 | 停止相关变更，提交替代方案；不进行破坏性重构 |
| 本轮没有实施 | Registry/Shell/Workspace Policy/关联表、SQL Generation | 不能将基线测试写为这些新功能已通过 |

## 10. 本轮验证

环境：Windows x86_64、Node v24.18.0、npm 11.16.0、Corretto Java 17.0.20.1；实际前端构建使用已安装的 Vite 7.3.5。构建产物和日志写入忽略目录 `target/workspace-audit/`，不覆盖 Vite 默认的 `src/main/resources/static`。命令均在仓库根目录运行，Java 使用 `C:/jdk17.0.20_12`。

| 实际命令 | 实际结果 | 证据 |
| --- | --- | --- |
| `npm.cmd run test --prefix chatchat-api/web-app` | 退出码 0；27 个测试文件、271 项测试全部通过，27.75 秒 | [前端测试日志](../../target/workspace-audit/frontend-test.log) |
| `npm.cmd run build --prefix chatchat-api/web-app -- --outDir ../../target/workspace-audit/frontend-dist --emptyOutDir` | 退出码 0；51.03 秒；有既存大 chunk 及静态/动态导入混用警告 | [前端构建日志](../../target/workspace-audit/frontend-build.log) |
| `mvn.cmd -pl chatchat-api -am -Pbackend-only '-DskipTests' compile` | 退出码 0；依赖 reactor 编译成功，25.680 秒；不是完整发行包或部署测试 | [后端编译日志](../../target/workspace-audit/backend-build.log) |
| `mvn.cmd -pl chatchat-api -am -Pbackend-only '-Dtest=ApiPermissionAuthorizationFilterTest,AgentAuthorizationIsolationTest,ResourceAuthorizationServiceTest,PythonDataScienceScriptDeletionTest,PythonDataScienceFileTypeTest,PythonEnvironmentStatusTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 退出码 1；共执行 15 项，12 项通过、3 项 error，4:04 分钟 | [后端测试日志](../../target/workspace-audit/backend-test.log) |

通过的后端测试：ResourceAuthorizationServiceTest 3 项、ApiPermissionAuthorizationFilterTest 3 项、PythonDataScienceScriptDeletionTest 3 项、PythonDataScienceFileTypeTest 2 项、PythonEnvironmentStatusTest 1 项。

失败的 3 项均属于 AgentAuthorizationIsolationTest，Spring 上下文在 ApplicationReadyEvent 阶段失败，未执行到其业务断言。根因证据：`DatabaseRuntimeSemanticPolicySource.verifyPublishedPolicy()` 调用 `snapshot()`，数据库没有 `default` 策略记录，抛出 `Runtime semantic policy is not published`。测试使用 application-test.yml 中的 H2 内存库和 create-drop；日志另有 localhost:9091 MCP 连接拒绝记录，不能把它混同为这次上下文失败的已确认根因。

本轮不通过关闭安全校验或修改测试种子掩盖失败。第二轮回归前需要对该集成测试准备与现有发布机制一致的有效语义策略 fixture，并明确本地 MCP 的测试替身/环境依赖；若需要修改测试配置，必须作为可审查的独立改动记录。当前不能宣称 Agent 授权集成回归通过。

Git 核验：业务代码的 tracked diff 为空；仅新增本目录两份文档，保留任务开始前已有的 `docs/market-analysis-method-evaluation-20261012.md` 和 `scripts/evaluation/README.md` 未跟踪文件。日志与构建输出均在忽略目录，不纳入交付文件。上述测试仅覆盖现有实现，不是 T10 多空间回归报告；未运行生产环境、完整后端测试套件、视觉或浏览器端到端测试。

## 11. 关键代码导航

- [前端路由和根状态](../../chatchat-api/web-app/src/js/App.js)、[页面缓存及布局](../../chatchat-api/web-app/src/App.vue)、[请求与登录存储](../../chatchat-api/web-app/src/services/api.js)。
- [Python Studio 组件状态](../../chatchat-api/web-app/src/js/views/DataScienceView.js)、[Agent UI 事件订阅](../../chatchat-api/web-app/src/js/views/ChatAssistantView.js)。
- [认证过滤器](../../chatchat-api/src/main/java/com/chatchat/api/security/ApiAuthenticationFilter.java)、[接口权限过滤器](../../chatchat-api/src/main/java/com/chatchat/api/security/ApiPermissionAuthorizationFilter.java)、[企业认证/菜单/RBAC](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/service/EnterpriseAdminService.java)。
- [资源授权](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/service/ResourceAuthorizationService.java)、[任务入口](../../chatchat-api/src/main/java/com/chatchat/api/agent/task/AgentTaskController.java)、[共享任务服务](../../chatchat-chat/src/main/java/com/chatchat/chat/task/core/AgentTaskService.java)。
- [会话入口](../../chatchat-api/src/main/java/com/chatchat/api/controller/chat/ConversationController.java)、[产物入口](../../chatchat-api/src/main/java/com/chatchat/api/controller/system/UiArtifactController.java)、[Python 资源所有权](../../chatchat-api/src/main/java/com/chatchat/api/datascience/python/PythonDataScienceService.java)。
- [现有能力注册实体](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/entity/mcp/CapabilityRegistryEntry.java)、[迁移说明](../../database/migration/README.md)、[启动语义策略校验](../../chatchat-chat/src/main/java/com/chatchat/chat/interaction/service/DatabaseRuntimeSemanticPolicySource.java)。
