# 实际文件清单

基线 `c876b184`；首张清单记录第二轮本地实施时的源码差异。该阶段后来已获生产部署授权；本次增量见文末清单，部署结果见 [生产报告](06-production-validation.md)。编译产物和本地日志不计入源码清单。

| 状态 | 文件 |
| --- | --- |
| 修改 | [chatchat-api/src/main/java/com/chatchat/api/agent/task/AgentTaskController.java](../../chatchat-api/src/main/java/com/chatchat/api/agent/task/AgentTaskController.java) |
| 修改 | [chatchat-api/src/main/java/com/chatchat/api/config/WebMvcConfig.java](../../chatchat-api/src/main/java/com/chatchat/api/config/WebMvcConfig.java) |
| 修改 | [chatchat-api/src/main/java/com/chatchat/api/controller/agent/AgentRuntimeController.java](../../chatchat-api/src/main/java/com/chatchat/api/controller/agent/AgentRuntimeController.java) |
| 修改 | [chatchat-api/src/main/java/com/chatchat/api/controller/chat/ConversationController.java](../../chatchat-api/src/main/java/com/chatchat/api/controller/chat/ConversationController.java) |
| 新增 | [chatchat-api/src/main/java/com/chatchat/api/security/ObjectAccessInterceptor.java](../../chatchat-api/src/main/java/com/chatchat/api/security/ObjectAccessInterceptor.java) |
| 新增 | [chatchat-api/src/main/java/com/chatchat/api/security/ObjectAccessService.java](../../chatchat-api/src/main/java/com/chatchat/api/security/ObjectAccessService.java) |
| 新增 | [chatchat-api/src/main/java/com/chatchat/api/security/ObjectRequestAdvice.java](../../chatchat-api/src/main/java/com/chatchat/api/security/ObjectRequestAdvice.java) |
| 新增 | [chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceCompatibilityMigration.java](../../chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceCompatibilityMigration.java) |
| 新增 | [chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceController.java](../../chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceController.java) |
| 新增 | [chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceManifest.java](../../chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceManifest.java) |
| 新增 | [chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceOriginService.java](../../chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceOriginService.java) |
| 新增 | [chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceService.java](../../chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceService.java) |
| 新增 | [chatchat-api/src/main/resources/workspace/data-science.json](../../chatchat-api/src/main/resources/workspace/data-science.json) |
| 新增 | [chatchat-api/src/main/resources/workspace/workspace-seed.sql](../../chatchat-api/src/main/resources/workspace/workspace-seed.sql) |
| 新增 | [chatchat-api/src/main/resources/workspace/zhice.json](../../chatchat-api/src/main/resources/workspace/zhice.json) |
| 修改 | [chatchat-api/src/test/java/com/chatchat/api/architecture/RuntimeOsArchitectureBoundaryTest.java](../../chatchat-api/src/test/java/com/chatchat/api/architecture/RuntimeOsArchitectureBoundaryTest.java) |
| 修改 | [chatchat-api/src/test/java/com/chatchat/api/controller/chat/ConversationControllerTest.java](../../chatchat-api/src/test/java/com/chatchat/api/controller/chat/ConversationControllerTest.java) |
| 修改 | [chatchat-api/src/test/java/com/chatchat/api/controller/system/UiArtifactControllerTest.java](../../chatchat-api/src/test/java/com/chatchat/api/controller/system/UiArtifactControllerTest.java) |
| 新增 | [chatchat-api/src/test/java/com/chatchat/api/security/ObjectAccessPolicyTest.java](../../chatchat-api/src/test/java/com/chatchat/api/security/ObjectAccessPolicyTest.java) |
| 新增 | [chatchat-api/src/test/java/com/chatchat/api/workspace/WorkspaceIntegrationTest.java](../../chatchat-api/src/test/java/com/chatchat/api/workspace/WorkspaceIntegrationTest.java) |
| 新增 | [chatchat-api/src/test/java/com/chatchat/api/workspace/WorkspaceMigrationTest.java](../../chatchat-api/src/test/java/com/chatchat/api/workspace/WorkspaceMigrationTest.java) |
| 修改 | [chatchat-api/web-app/package.json](../../chatchat-api/web-app/package.json) |
| 新增 | [chatchat-api/web-app/scripts/test-workspace-shell.mjs](../../chatchat-api/web-app/scripts/test-workspace-shell.mjs) |
| 修改 | [chatchat-api/web-app/src/App.vue](../../chatchat-api/web-app/src/App.vue) |
| 修改 | [chatchat-api/web-app/src/components/AssistantLayout.vue](../../chatchat-api/web-app/src/components/AssistantLayout.vue) |
| 修改 | [chatchat-api/web-app/src/components/AssistantSidebar.vue](../../chatchat-api/web-app/src/components/AssistantSidebar.vue) |
| 修改 | [chatchat-api/web-app/src/js/App.js](../../chatchat-api/web-app/src/js/App.js) |
| 修改 | [chatchat-api/web-app/src/js/App.test.js](../../chatchat-api/web-app/src/js/App.test.js) |
| 修改 | [chatchat-api/web-app/src/js/components/AssistantLayout.js](../../chatchat-api/web-app/src/js/components/AssistantLayout.js) |
| 修改 | [chatchat-api/web-app/src/js/utils/chatRuntimeState.js](../../chatchat-api/web-app/src/js/utils/chatRuntimeState.js) |
| 新增 | [chatchat-api/web-app/src/js/utils/workspace.test.js](../../chatchat-api/web-app/src/js/utils/workspace.test.js) |
| 新增 | [chatchat-api/web-app/src/js/utils/workspaceContext.js](../../chatchat-api/web-app/src/js/utils/workspaceContext.js) |
| 新增 | [chatchat-api/web-app/src/js/utils/workspaceDrafts.js](../../chatchat-api/web-app/src/js/utils/workspaceDrafts.js) |
| 修改 | [chatchat-api/web-app/src/js/views/ChatAssistantView.js](../../chatchat-api/web-app/src/js/views/ChatAssistantView.js) |
| 修改 | [chatchat-api/web-app/src/js/views/DataScienceView.js](../../chatchat-api/web-app/src/js/views/DataScienceView.js) |
| 修改 | [chatchat-api/web-app/src/services/api.js](../../chatchat-api/web-app/src/services/api.js) |
| 修改 | [chatchat-api/web-app/src/styles/layout.css](../../chatchat-api/web-app/src/styles/layout.css) |
| 修改 | [chatchat-chat/pom.xml](../../chatchat-chat/pom.xml) |
| 修改 | [chatchat-chat/src/main/java/com/chatchat/chat/task/core/AgentTaskLatestRepository.java](../../chatchat-chat/src/main/java/com/chatchat/chat/task/core/AgentTaskLatestRepository.java) |
| 修改 | [chatchat-chat/src/main/java/com/chatchat/chat/task/core/AgentTaskService.java](../../chatchat-chat/src/main/java/com/chatchat/chat/task/core/AgentTaskService.java) |
| 新增 | [chatchat-enterprise/src/main/java/com/chatchat/enterprise/entity/workspace/WorkspacePreference.java](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/entity/workspace/WorkspacePreference.java) |
| 新增 | [chatchat-enterprise/src/main/java/com/chatchat/enterprise/entity/workspace/WorkspaceRegistration.java](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/entity/workspace/WorkspaceRegistration.java) |
| 新增 | [chatchat-enterprise/src/main/java/com/chatchat/enterprise/entity/workspace/WorkspaceResourceOrigin.java](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/entity/workspace/WorkspaceResourceOrigin.java) |
| 新增 | [chatchat-enterprise/src/main/java/com/chatchat/enterprise/repository/workspace/WorkspacePreferenceRepository.java](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/repository/workspace/WorkspacePreferenceRepository.java) |
| 新增 | [chatchat-enterprise/src/main/java/com/chatchat/enterprise/repository/workspace/WorkspaceRegistrationRepository.java](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/repository/workspace/WorkspaceRegistrationRepository.java) |
| 新增 | [chatchat-enterprise/src/main/java/com/chatchat/enterprise/repository/workspace/WorkspaceResourceOriginRepository.java](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/repository/workspace/WorkspaceResourceOriginRepository.java) |
| 修改 | [database/init/h2/chatchat-api.sql](../../database/init/h2/chatchat-api.sql) |
| 修改 | [database/init/mysql/chatchat-api.sql](../../database/init/mysql/chatchat-api.sql) |
| 修改 | [database/init/postgresql/chatchat-api.sql](../../database/init/postgresql/chatchat-api.sql) |
| 新增 | [database/migration/README-workspace.md](../../database/migration/README-workspace.md) |
| 新增 | [database/migration/h2/V20261013_01__workspace_apps.sql](../../database/migration/h2/V20261013_01__workspace_apps.sql) |
| 新增 | [database/migration/mysql/V20261013_01__workspace_apps.sql](../../database/migration/mysql/V20261013_01__workspace_apps.sql) |
| 新增 | [database/migration/postgresql/V20261013_01__workspace_apps.sql](../../database/migration/postgresql/V20261013_01__workspace_apps.sql) |
| 新增 | [docs/workspace/01-current-architecture.md](../../docs/workspace/01-current-architecture.md) |
| 新增 | [docs/workspace/02-workspace-architecture.md](../../docs/workspace/02-workspace-architecture.md) |
| 新增 | [docs/workspace/03-test-report.md](../../docs/workspace/03-test-report.md) |
| 新增 | [docs/workspace/04-implementation-review.md](../../docs/workspace/04-implementation-review.md) |
| 新增 | [docs/workspace/05-changed-files.md](../../docs/workspace/05-changed-files.md) |

共 25 个修改文件，33 个新增文件。新文档包括第一轮保留的审查/设计/基线与本轮更新；不代表第一轮修改过业务代码。

本地发布包：`chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT-release.zip`。生成 assets 被仓库忽略；源码发布需先 `npm.cmd run build`。此清单不包含账号、密码、Token 或服务器配置变更。

## 2026-10-13 登录入口、菜单与 License 生产迭代


以下为本次增量的实际源码与文档文件（39 项）；前端生成 assets 和本地验证日志位于忽略目录，不计入源码。生产发布包已由前端构建打入这些 assets。

| 状态 | 文件 |
| --- | --- |
| 修改 | [chatchat-api/src/main/java/com/chatchat/api/security/ApiAuthenticationFilter.java](../../chatchat-api/src/main/java/com/chatchat/api/security/ApiAuthenticationFilter.java) |
| 修改 | [chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceCompatibilityMigration.java](../../chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceCompatibilityMigration.java) |
| 修改 | [chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceController.java](../../chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceController.java) |
| 修改 | [chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceService.java](../../chatchat-api/src/main/java/com/chatchat/api/workspace/WorkspaceService.java) |
| 修改 | [chatchat-api/src/main/resources/workspace/data-science.json](../../chatchat-api/src/main/resources/workspace/data-science.json) |
| 新增 | [chatchat-api/src/main/resources/workspace/workspace-navigation.sql](../../chatchat-api/src/main/resources/workspace/workspace-navigation.sql) |
| 修改 | [chatchat-api/src/main/resources/workspace/zhice.json](../../chatchat-api/src/main/resources/workspace/zhice.json) |
| 修改 | [chatchat-api/src/test/java/com/chatchat/api/workspace/WorkspaceIntegrationTest.java](../../chatchat-api/src/test/java/com/chatchat/api/workspace/WorkspaceIntegrationTest.java) |
| 修改 | [chatchat-api/web-app/scripts/test-workspace-shell.mjs](../../chatchat-api/web-app/scripts/test-workspace-shell.mjs) |
| 修改 | [chatchat-api/web-app/src/App.vue](../../chatchat-api/web-app/src/App.vue) |
| 修改 | [chatchat-api/web-app/src/components/AssistantLayout.vue](../../chatchat-api/web-app/src/components/AssistantLayout.vue) |
| 修改 | [chatchat-api/web-app/src/components/AssistantSidebar.vue](../../chatchat-api/web-app/src/components/AssistantSidebar.vue) |
| 修改 | [chatchat-api/web-app/src/js/App.js](../../chatchat-api/web-app/src/js/App.js) |
| 修改 | [chatchat-api/web-app/src/js/App.test.js](../../chatchat-api/web-app/src/js/App.test.js) |
| 修改 | [chatchat-api/web-app/src/js/components/AssistantSidebar.js](../../chatchat-api/web-app/src/js/components/AssistantSidebar.js) |
| 修改 | [chatchat-api/web-app/src/js/utils/workspace.test.js](../../chatchat-api/web-app/src/js/utils/workspace.test.js) |
| 修改 | [chatchat-api/web-app/src/js/utils/workspaceContext.js](../../chatchat-api/web-app/src/js/utils/workspaceContext.js) |
| 修改 | [chatchat-api/web-app/src/js/views/LoginView.js](../../chatchat-api/web-app/src/js/views/LoginView.js) |
| 修改 | [chatchat-api/web-app/src/services/api.js](../../chatchat-api/web-app/src/services/api.js) |
| 修改 | [chatchat-api/web-app/src/styles/pages/login.css](../../chatchat-api/web-app/src/styles/pages/login.css) |
| 修改 | [chatchat-api/web-app/src/views/DataScienceView.vue](../../chatchat-api/web-app/src/views/DataScienceView.vue) |
| 修改 | [chatchat-api/web-app/src/views/DomainSkillsView.vue](../../chatchat-api/web-app/src/views/DomainSkillsView.vue) |
| 修改 | [chatchat-api/web-app/src/views/LoginView.vue](../../chatchat-api/web-app/src/views/LoginView.vue) |
| 修改 | [chatchat-common/src/main/java/com/chatchat/common/mcp/license/McpLicenseEntitlementPort.java](../../chatchat-common/src/main/java/com/chatchat/common/mcp/license/McpLicenseEntitlementPort.java) |
| 修改 | [chatchat-enterprise/src/main/java/com/chatchat/enterprise/service/EnterpriseAdminService.java](../../chatchat-enterprise/src/main/java/com/chatchat/enterprise/service/EnterpriseAdminService.java) |
| 修改 | [chatchat-integration/src/main/java/com/chatchat/integration/mcp/service/client/McpLicenseEntitlementClient.java](../../chatchat-integration/src/main/java/com/chatchat/integration/mcp/service/client/McpLicenseEntitlementClient.java) |
| 修改 | [chatchat-license/src/main/resources/license-module-catalog.json](../../chatchat-license/src/main/resources/license-module-catalog.json) |
| 修改 | [chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/license/LicenseInternalController.java](../../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/license/LicenseInternalController.java) |
| 修改 | [chatchat-mcp-server/src/main/resources/mcp-admin-menus.json](../../chatchat-mcp-server/src/main/resources/mcp-admin-menus.json) |
| 修改 | [chatchat-mcp-server/src/test/java/com/chatchat/mcpserver/license/LicenseInternalControllerTest.java](../../chatchat-mcp-server/src/test/java/com/chatchat/mcpserver/license/LicenseInternalControllerTest.java) |
| 修改 | [database/migration/README-workspace.md](../../database/migration/README-workspace.md) |
| 新增 | [database/migration/h2/V20261013_02__workspace_navigation.sql](../../database/migration/h2/V20261013_02__workspace_navigation.sql) |
| 新增 | [database/migration/mysql/V20261013_02__workspace_navigation.sql](../../database/migration/mysql/V20261013_02__workspace_navigation.sql) |
| 新增 | [database/migration/postgresql/V20261013_02__workspace_navigation.sql](../../database/migration/postgresql/V20261013_02__workspace_navigation.sql) |
| 修改 | [docs/workspace/02-workspace-architecture.md](../../docs/workspace/02-workspace-architecture.md) |
| 修改 | [docs/workspace/03-test-report.md](../../docs/workspace/03-test-report.md) |
| 修改 | [docs/workspace/04-implementation-review.md](../../docs/workspace/04-implementation-review.md) |
| 修改 | [docs/workspace/05-changed-files.md](../../docs/workspace/05-changed-files.md) |
| 新增 | [docs/workspace/06-production-validation.md](../../docs/workspace/06-production-validation.md) |
