# 业务工作空间源码审查

开发基线为 `7d4a6b721`，来源分支为 `feature/native-cline-template-invocation-20261121`。原型为工作区中的《数字员工工作空间.html》。

| 功能 | 实际源码 | 现有 API | 复用与缺口 |
|---|---|---|---|
| 空间注册 | chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceService.java、WorkspaceCompatibilityMigration.java | GET /api/v1/workspaces、GET /api/v1/workspaces/{id}/manifest、GET/PUT /api/v1/workspaces/preferences | 可复用。新增注册记录、菜单及独立访问权限 |
| 会话 | chatchat-api/src/main/java/com/chatchat/api/controller/chat/ConversationController.java、workspace/WorkspaceOriginService.java | /api/v1/conversations/**、/api/v1/data/history/** | 可复用会话归属、历史和偏好 |
| Agent 执行 | chatchat-api/src/main/java/com/chatchat/api/agent/task/AgentTaskController.java、controller/chat/InteractionController.java | /api/v1/agent/tasks/**、/api/v1/interactions/chat | 可复用。请求的 skillId 实际表示发布 Agent |
| Skills | chatchat-api/src/main/java/com/chatchat/api/controller/agent/AuthorizedResourceCatalogController.java、chatchat-chat/src/main/java/com/chatchat/chat/skills/domain/application/DomainSkillService.java | GET /api/v1/interactions/resources/skills、GET /api/v1/interactions/resources/agents | 可复用用户目录。领域技能通过已发布 Agent 的 boundDomainSkillIds 进入现有运行链 |
| 知识库 | chatchat-api/src/main/java/com/chatchat/api/controller/search/SearchController.java | GET /api/v1/search、GET /api/v1/search/library | 可复用检索和来源 |
| 运行结果 | chatchat-api/web-app/src/views/ChatAssistantView.vue、src/js/views/ChatAssistantView.js、src/components/ChatMessageList.vue | 任务状态、结果、事件及 /api/v1/ui-artifacts/** | 可复用状态恢复、图表、报告和证据 |
| 用户权限 | chatchat-api/src/main/java/com/chatchat/api/security/ApiPermissionAuthorizationFilter.java、ObjectAccessInterceptor.java、ObjectRequestAdvice.java | 统一 API 策略、身份及对象权限 | 可复用。业务会话及任务需追加来源授权校验 |
