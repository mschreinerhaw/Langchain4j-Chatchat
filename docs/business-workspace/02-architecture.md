# 业务工作空间架构

`business` 作为独立 Workspace Registry 记录注册，入口为 `businessHome`。Workspace Service 按启用状态、License、角色权限和菜单授权投影 Manifest。受信任的前端组件注册在 `chatchat-api/web-app/src/js/App.js`。业务页面使用现有应用导航和会话侧栏，并在主区域提供智能对话、Skills、岗位工作、知识中心及成果入口。

业务页面包装 `ChatAssistantView`，沿用同一套会话、任务提交、事件订阅、取消、失败处理、结果恢复与图表报告组件。已有 Runtime 根据已发布 Agent 的领域 Skill 绑定和实时权限决定可交付及可执行的技能。业务的默认 Agent 只从调用者的已授权 Agent 目录中选择。

`BusinessWorkspaceMigration` 在应用初始化时向现有注册、权限、菜单和绑定表写入增量种子。普通角色通过现有权限中心显式授权。已有租户超管的一次性初始化只绑定业务空间入口。License 延用智策的有效许可，业务空间仍有独立 RBAC 门槛。

普通业务角色使用 `workspace:business:access`、`workspace:chat:interact` 和 `workspace:chat:conversation`。账号、菜单和 Workspace API 权限通过现有角色授权流程补齐。按需显式授予只读文档检索权限 `workspace:business:knowledge`、允许使用的 Agent、Skill 及知识资源。业务角色权限可独立于智策菜单及数据车间能力。

业务对象归属由持久化的会话来源确定。后续会话、任务和成果请求检查对象权限及业务空间权限。客户端 Workspace 标识只用于声明当前应用，权限仍取自服务端身份和持久化授权。
