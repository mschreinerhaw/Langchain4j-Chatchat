# API 复用表

| 场景 | API | 策略 |
|---|---|---|
| 选择及恢复空间 | GET /api/v1/workspaces、GET /api/v1/workspaces/{id}/manifest、GET/PUT /api/v1/workspaces/preferences | 注册状态、有效 License、Workspace 角色权限和授权菜单 |
| 管理配置 | GET /api/v1/enterprise/workspaces、PUT /api/v1/enterprise/workspaces/{id} | 现有管理权限、版本检查与审计 |
| Agent 目录 | GET /api/v1/interactions/resources/agents、GET /api/v1/interactions/resources/agents/{id} | 发布状态及用户 Agent 授权 |
| Skills 目录 | GET /api/v1/interactions/resources/skills | 复用发布目录和资源 ACL。业务页请求 `summary=true`，依需用 `ids` 筛选已获授权的推荐技能 |
| 对话及任务 | POST /api/v1/interactions/chat、POST /api/v1/agent/tasks、任务详情、SSE、结果及取消 | 复用 Agent 权限、任务所有者策略及 Runtime |
| 会话 | /api/v1/conversations/**、POST /api/v1/data/history、PATCH /api/v1/data/history/{userId}/{historyId}/status、DELETE /api/v1/data/history/{userId}/{historyId} | 历史按 Workspace 查询。业务角色凭业务空间和会话权限使用精确的历史写入路由 |
| 知识 | GET /api/v1/search | 业务角色需要只读知识检索权限。由现有检索策略筛选租户、用户及文档 ACL。配置中的分类对应检索标签 |
| 成果 | GET /api/v1/agent/tasks/{id}/result、GET /api/v1/ui-artifacts/{id}/** | 复用任务和成果所有者校验及现有渲染 |

领域 Skill 卡片通过已发布且有权限的绑定 Agent 发起带 Skill 名称的提问草稿。一个 Agent 可以绑定多个领域 Skill。现有执行链负责选择实际使用的技能与工具，并输出真实执行证据。调用契约目前没有独立的用户请求多 Skill 选择字段。
