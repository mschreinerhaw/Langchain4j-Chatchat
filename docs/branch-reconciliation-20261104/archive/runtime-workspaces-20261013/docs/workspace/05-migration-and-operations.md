# Workspace 迁移与运行说明

日期：2026-10-13；开发分支：`feature/runtime-workspaces`。

## 数据库变更

仅新增 `sys_workspace`、`sys_workspace_user_preference`、`sys_workspace_resource_binding` 三张表，没有迁移或删除既有任务、会话、文件、Python 资产及执行历史。

按实际数据库选择 `database/migration/h2`、`mysql` 或 `postgresql`，依次执行 `V20261013_01__workspace_registry.sql` 和 `V20261013_02__workspace_resource_binding.sql`。首次建库的 `database/init/<dialect>/chatchat-api.sql` 已包含同样结构。项目当前没有可依赖的自动 Flyway 执行链路，文件存在不代表已应用；需纳入现有发布流程。不要混用不同数据库脚本。

第一份脚本添加 `account:workspaces:use` 和 `system:workspaces:manage` API 权限，只分别继承已具有 `account:menus:read` 或 `system:admin:operate` 的角色。未添加 Skill、MCP、资源或 Python 功能授权。应用初始化也进行相同的幂等兼容补齐。

内置空间按租户首次访问时在租户行锁下注册，保留已有配置：`zhice` 是系统默认空间；`data-science` 使用现有 `capability:data-science` 功能权限和 Python 菜单。未绑定的旧对象保留在智策空间并继续使用既有领域权限；不对存量对象进行批量来源回填。

H2 的两份迁移已实际重复执行并验证唯一约束及权限继承。MySQL/PostgreSQL 提供了对应方言脚本，但本机没有原生服务或 Docker，未完成原生引擎迁移验收。上线前须在备份恢复的预发库执行并检查版本、字符集、索引长度、时间类型与现有表定义。生产 SQL 尚未执行。

## 接口及配置

`chatchat.workspace.enabled` 默认 `true`。个人接口要求既有认证和新增个人 API 权限：

| 方法 | 路径（前缀 `/api/v1`） | 用途 |
|---|---|---|
| GET | `/workspaces` | 当前用户可访问的空间；Manifest 已按菜单及授权过滤 |
| GET | `/workspaces/{id}/manifest` | 当前用户可访问的指定 Manifest |
| GET / PUT | `/workspaces/preferences` | 个人默认与上次空间；PUT 提交 `expectedRevision` |
| GET / POST | `/enterprise/workspaces` | 管理端查询或注册 |
| PUT | `/enterprise/workspaces/{id}` | 更新 Manifest、状态、系统默认、访问权限及版本 |

管理接口还由服务层独立检查 `system:admin:operate`。停用系统默认前，必须先设置一个启用的替代默认；同租户更新串行化并检查 revision。权限或状态变化后，个人默认及上次空间只在仍可访问时生效。管理 UI 暂沿用 API 管理方式，没有新建注册中心管理页面。

请求通过 `X-Workspace-Id` 传递空间，缺省视为 `zhice`；身份和租户来自既有认证过滤器，不能使用请求体身份替代。新绑定的会话、任务及产物是租户内的用户私有对象，普通用户需同时匹配所有者及来源空间；保留既有具有 `system:admin:operate` 与 `platform:tasks:all` 的运维人员跨空间查看能力。该来源关联不授予资产权限。

## 前端兼容

空间选择地址为 `#/workspaces`；新页面地址为 `#/w/{workspaceId}/{pageRoute}`。`#/chat`、`#/dataScienceDevelop` 等旧链接继续指向智策空间的原页面。单前端工程使用已注册组件加载页面，没有微前端或任意远程代码加载。开发新的页面模块时需要同时注册受信任的前端视图、服务端页面白名单和既有菜单权限，再注册 Manifest。

切换不重新登录，不取消任务；消息流及后续请求携带任务原空间。组件缓存、会话选择和草稿按身份与空间隔离。Python 编辑草稿存储在当前浏览器标签的 sessionStorage，有效期 24 小时，恢复时重新检查当前可访问的资产与脚本。已有持久化文件和脚本不改存储位置。浏览器缓存最多保留 12 个页面实例；缓存淘汰后依靠既有持久化任务、会话及草稿恢复，不保证任意页面的一切临时控件状态。

## 发布、降级与风险

先备份并演练数据库迁移，再发布后端、前端，检查旧角色继承、空间列表、文件下载、任务 SSE 与 Python 工作台。现有认证 Token 在服务端内存维护，后端重启可能要求重新登录；这是原机制，空间切换自身不改变 Token。

设置 `chatchat.workspace.enabled=false` 可关闭空间入口，前端在 Registry API 返回 404 时兼容旧导航。对象来源过滤器仍保留，避免降级绕过已创建对象的权限。不要直接回滚到完全没有来源检查的旧后端，也不要删除来源表；这种回滚可能重新暴露既有租户级读取接口。

任务来源在进入既有执行队列前建立，后台产物继承该来源。来源写入和原有任务提交并非全链路单事务：提交失败可能留下不引用有效任务的关联记录，但不应让未绑定任务进入执行。需要真实服务上补充失败注入和清理策略验收。现有运维聚合统计沿用原租户范围，含任务内容的列表和低分反馈条目增加来源过滤；本次没有改造全部历史统计为个人统计。

本轮只进行了本地开发、构建、自动化及模拟 HTTP 的真实浏览器测试，没有部署生产，也没有宣称真实模型/MCP/Python 远程执行已完成验收。
