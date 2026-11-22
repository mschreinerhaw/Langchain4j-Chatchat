# 实施说明

- `chatchat-api/src/main/resources/workspace/business.json` 提供业务 Manifest 初始版本。
- `chatchat-api/src/main/java/com/chatchat/api/workspace/BusinessWorkspaceMigration.java` 注册工作空间并添加专属菜单权限。
- `BusinessWorkspaceProfiles.java` 与 `WorkspaceService.java` 依据服务端租户、组织标识投影调用者可见的配置。
- `WorkspaceOriginService.java`、`ObjectAccessInterceptor.java` 和 `ObjectRequestAdvice.java` 保护业务来源对象与会话执行。
- `chatchat-api/web-app/src/views/BusinessWorkspaceView.vue`、`src/js/views/BusinessWorkspaceView.js` 和 `src/styles/pages/business-workspace.css` 实现业务 UI，复用现有聊天和会话生命周期。

## 部门配置

在智策的工作空间管理中打开业务工作空间配置，编辑部门配置 JSON。键为真实租户 ID 和组织 ID，中间用斜线分隔。以斜线结尾的租户配置作为租户默认值，同名字段由部门配置覆盖。多值字段每行一个值。

```json
{
  "tenant-id/": {
    "title": "业务智能助手",
    "welcome": "从一个业务问题开始"
  },
  "tenant-id/org-id": {
    "title": "运营助手",
    "defaultAgentId": "published-agent-id",
    "recommendedSkillIds": "skill-id-1\nskill-id-2",
    "recommendedQuestions": "帮我核对今日运营指标",
    "knowledgeCategories": "运营手册",
    "workGuides": "每日指标核对\n每周异常复盘"
  }
}
```

推荐 Skill ID 只从服务端授权目录匹配。岗位指引随配置展示。岗位任务完成状态持久化属于后续 P2。

## 数据变化

复用 `workspace_registry`、`sys_menu`、`sys_permission`、`sys_menu_permission` 与 `sys_role_permission`。无新增表和列。会话及产物继续使用既有存储。浏览器保存现有会话认证及导航状态，业务对话草稿和运行快照不写入本地存储。
