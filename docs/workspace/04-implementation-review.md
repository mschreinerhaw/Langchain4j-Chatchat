# 第二轮实施审查：T03～T08

日期：2026-10-13。基线：`c876b184`。用户授权本地实施，**没有生产部署授权**；未连接生产进行变更、执行生产迁移、上传版本或重启服务。

## 交付范围与架构边界

T03 注册/查询/启停/版本控制/默认配置通过既有 Enterprise RBAC 管理接口实现。T04 选择页、左上切换器、动态路由/菜单、无权限拦截、默认/上次空间恢复已接入现有 App。T05 统一认证以及会话、任务和产物对象权限由服务端公共授权层实现。T06 智策默认 Manifest 引用原页面，没有迁移旧业务数据。T07 数据科学空间复用原 Python Studio 与领域 Skills 页面；AI 编程助手仍使用原服务。T08 缓存、草稿和新资源创建来源分别隔离，并验证后台任务继续完成。

工作空间的应用配置位于 `chatchat-api/src/main/resources/workspace/`；没有改动 `chatchat-agents` 中的 Loop/Planner、模型调度、Runtime Skill 工具适配、MCP 资产发现或资源授权代码。`chatchat-chat` 仅追加所有者任务列表查询以及声明原本缺失的 Spring 平台依赖，没有业务工作流分支。

T09 不开发 SQL Generation。SQL Studio 仅声明为预留能力；后续 SQL 案例、Mapping、方言、校验工具和可下载文件应通过授权 Skill/MCP 工具实现，不能进入 Core。

## 接口与路由

| 接口 | 用途与服务端校验 |
| --- | --- |
| `GET /api/v1/workspaces` | 当前主体可访问且已启用的空间；检查真实 tenant、启用身份与现有权限 |
| `GET /api/v1/workspaces/{id}/manifest` | 过滤后的菜单/页面/默认 Skill/能力声明；没有资源授权效果 |
| `GET/PUT /api/v1/workspaces/preferences` | 当前主体默认/上次空间；新值必须当前可访问 |
| `GET /api/v1/workspaces/context/{type}/{id}` | `CONVERSATION/TASK/UI_ARTIFACT` 创建来源；先检查对象所属关系，旧资源可为空 |
| `GET /api/v1/enterprise/workspaces` | 管理员查看全部注册空间 |
| `PUT /api/v1/enterprise/workspaces/{id}` | 注册/更新/启停；现有 `system:workspaces:manage` 权限校验，`expectedVersion` 乐观锁和递增 `configVersion` |
| `PUT /api/v1/enterprise/workspaces/default` | 设全局系统默认；同一管理权限，目标必须启用且授权 |

新入口 `#/workspaces`，空间页面 `#/w/{workspaceId}/{pageId}`。保留 `#/chat`、`#/library` 等旧哈希路由，以及原 `dataScience`、`system` 别名。页面注册引用 Shell 已编译的组件，不加载远程代码。直接访问未授权空间/页面会拦截；最终 API 权限不依赖此拦截。

原认证、业务接口路径保留。`X-Workspace-Id` 是已授权创建来源声明，服务端不使用它扩大对象访问范围。普通用户任务列表现在仅显示其稳定 ID 或受校验的旧 username 对应任务；已有 `platform:tasks:all` 可查看同租户任务。会话、历史记录、产物以及运行记录的路径和请求体受统一 owner/tenant 检查。租户汇总（任务审计、效果/经验、Runtime snapshot/quality）必须有该既有任务管理权限，避免从汇总接口读取其他主体内容。

## 数据库与兼容方案

新增三张表：`workspace_registry`、`workspace_user_preference`、`workspace_resource_origin`。均为附加表；没有修改/删除原会话、任务、文件、产物、菜单或授权表结构，没有旧数据批量搬迁。

迁移脚本：

- `database/migration/h2/V20261013_01__workspace_apps.sql`
- `database/migration/mysql/V20261013_01__workspace_apps.sql`
- `database/migration/postgresql/V20261013_01__workspace_apps.sql`

新建数据库的对应 `database/init/{dialect}/chatchat-api.sql` 增加相同建表语句。应用启动执行 `WorkspaceCompatibilityMigration`，插入缺失的配置和权限并进行一次性等效角色入口绑定。迁移保留既有配置，重复运行不重设默认/启停状态；内部标记 `system:workspace-compatibility:v1` 防止撤销授权后被重启重新授予。

本项目未引入新的 Flyway 自动迁移框架。现有库发布前需要先按实际数据库执行对应脚本；应用启动种子不代替生产 DDL 审查。操作步骤和非破坏性回退见 [迁移说明](../../database/migration/README-workspace.md)。本轮 H2 脚本已真实执行并验证幂等性、撤销权限和历史保留；MySQL/PostgreSQL 尚未在真实服务上执行。

## 主要代码变更分组

| 区域 | 文件/目录 | 目的 |
| --- | --- | --- |
| 公共对象授权 | API `security/ObjectAccessService.java`、`ObjectAccessInterceptor.java`、`ObjectRequestAdvice.java`、`config/WebMvcConfig.java` | 所属关系裁决、SSE/修改保护、执行前会话复用与恢复任务检查 |
| 注册与来源 | API `workspace/`、`resources/workspace/` | Manifest、Registry、偏好、菜单投影、创建来源、一次性兼容入口 |
| 持久化 | Enterprise `entity/workspace/`、`repository/workspace/` | 三张附加表及仓储 |
| 现有接口适配 | API `AgentTaskController.java`、`AgentRuntimeController.java`、`ConversationController.java` | 普通用户列表范围、新会话来源、运行列表身份范围 |
| 既有任务查询 | Chat `AgentTaskLatestRepository.java`、`AgentTaskService.java` | 沿用现有任务快照、状态与执行器，增加所有者查询 |
| Shell | 前端 `App.vue`、`js/App.js`、`AssistantLayout.vue/js`、`AssistantSidebar.vue`、`styles/layout.css` | 选择/切换、路由与菜单、KeepAlive、布局 |
| 前端上下文 | 前端 `services/api.js`、`workspaceContext.js`、`workspaceDrafts.js`、`chatRuntimeState.js`、`ChatAssistantView.js`、`DataScienceView.js` | 来源请求头、捕获运行上下文、身份/空间草稿隔离、旧编辑器复用 |
| 测试与构建 | 新对象权限/Registry/迁移/前端隔离测试、浏览器冒烟脚本、现有测试夹具、`package.json`、Chat `pom.xml` | 越权/兼容性验证；修复已记录的基线失败 |
| 数据库/文档 | 三种迁移、三个初始化脚本、Workspace 文档 | 发布可审查性与非破坏性兼容 |

完整逐文件清单见 [实际文件清单](05-changed-files.md)。前端生成 assets 被仓库忽略；本地构建和 release ZIP 包含新前端，打包后跟踪的静态入口已恢复以避免仅提交入口却遗漏 assets。源码交付需执行前端构建后再打包，不能只使用仓库中的旧静态入口。日志/截图位于本地 `target/workspace-audit/`；交付文档不包含服务器密码或 Token，原始测试日志不对外发布。

## 风险、未完成事项与审查建议

1. **生产部署未执行。** 真实 MySQL/PostgreSQL 迁移、服务器重启/回退、真实模型和 MCP 调用、真实 Python 执行需在审查后另行授权验证。Java 集成测试使用真实认证、RBAC、JPA 与任务执行器，模型/编排结果是测试替身；浏览器使用 API 夹具。
2. 原认证 Token 仍是既有进程内会话；切换 Workspace 不重新登录，但服务器重启后的会话行为没有在本轮改造。没有新增身份系统。
3. 旧无法关联任务的产物保留存储但拒绝公开读取（404）。发布前应只读清点此类记录，并设计受控归属修复；不得为了兼容放宽权限或删除产物。
4. 原组织/资源共享机制未扩展为会话共享。当前会话属于创建主体；组织相同、空间相同都不构成授权。若业务以后要求共享会话，需要在统一 Runtime 授权层显式设计授权对象与审计。
5. 系统默认空间目前全局；个人偏好按租户/主体独立。租户独立系统默认、多前端独立部署、远程插件和管理图形编辑器未实施；本期管理通过已有受控接口。
6. 重载恢复保存编辑器草稿，不复制后台任务状态机。重载后正在运行的任务仍从既有持久化任务和 SSE 恢复；页面缓存保留不保证浏览器关闭后所有内存 UI 状态都可恢复。过期、无身份归属的旧浏览器缓存不自动读取。
7. 前端 Python/Monaco 等大 chunk、Mockito `MockBean` 弃用、本地 MCP gRPC 未配置的告警仍存在；实际执行的回归中没有失败。未宣称整个仓库所有测试或线上全链路均通过。

建议审查顺序：统一 owner/tenant 策略与任务管理特权 → 迁移一次性入口绑定及数据库方言 → 旧链接/资源可读性 → 环境中模型/MCP/Python 回归 → 用户决定是否授权部署。此次不请求或执行生产发布。
