# Workspace 附加迁移说明

生产 PostgreSQL 已依用户后续授权执行 `V20261013_01__workspace_apps.sql` 和 `V20261013_02__workspace_navigation.sql`，先事务演练，再正式执行。详细结果与备份位置见 [生产报告](../../docs/workspace/06-production-validation.md)。以下步骤保留为其他环境的部署说明；只选择实际数据库对应方言，不依次执行三个方言。

V2 仅调整两条默认 Manifest 与“领域技能”菜单父级，只有原始 V1 Manifest 完全匹配时才升级，以免覆盖管理员自定义配置。应用启动也会幂等执行同义导航配置脚本。MySQL/H2 V2 脚本已交付；本次未在对应生产库执行。

1. 先备份既有数据库及任务/会话详情文件，记录原版本和已发布静态包。只读检查 `sys_permission`、`sys_role`、`sys_role_permission`、`sys_menu`、`sys_menu_permission` 的既有结构与状态，排查固定种子 ID `ws-api/ws-zhice/ws-manage/ws-marker` 与现有配置冲突。
2. 审查三张附加表及一次性入口角色映射。脚本不授予 Skill、MCP、Python 环境、文件或任务管理权限。只有既有权限角色取得等效 Workspace 入口；新注册管理权限限定已具角色授权能力的 `SUPER_ADMIN`。
3. 经发布授权后，在备份可回退、应用暂停写入的维护窗口执行对应脚本。MySQL 连接须使用 UTF-8；MySQL DDL 的自动提交行为不能按 PostgreSQL 事务回退假定。事务失败时检查标记和实际角色绑定后再修复/重试。
4. 构建前端，再打包新 API；启动后核对配置、已撤销授权未复活、默认智策、旧路由/历史、跨主体拒绝、Python 与 Agent 执行。生产目前未完成这些验证。
5. 若回退应用，恢复原发布包，保留新增表与来源记录，不 DROP 表、不删除历史或已新增任务/文件。原版本不查询新增表。需要禁用入口时使用受控配置/权限操作，不清除迁移标记；单纯删除标记会触发一次性角色映射重做。

脚本使用 `CREATE TABLE IF NOT EXISTS` 和 `INSERT ... WHERE NOT EXISTS`。已有 Workspace 配置/版本/偏好不会被覆盖；权限撤销后重复执行不会自动重授。H2 实际迁移重复执行测试通过；MySQL/PostgreSQL 仅交付脚本，真实方言执行与已有库冲突检查是部署前待办。

`WorkspaceCompatibilityMigration` 在应用启动时添加缺失种子与一次性兼容绑定；依赖三张表已经存在。它不是新的迁移框架，不替代生产 DDL 执行。
