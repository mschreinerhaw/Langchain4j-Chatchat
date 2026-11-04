# 历史分支处理记录

核对基线：`35e6264c4`，对应已经部署验证的整合版本。

## 处理结果

| 分支 | 处理方式 |
| --- | --- |
| `feature/runtime-workspaces` | 旧版实现由现有 Workspace 注册表、来源记录和对象权限机制接替；保留当前实现，用历史合并记录关闭该分支。 |
| `release/1.2-20261022` | 174 个文件中 84 个与当前主干完全一致，89 个已有后续实现，旧 `RELEASE_VERSION` 标记不再使用。保留当前实现，用历史合并记录关闭该分支。 |
| `agents/log-analysis-fix-runtime-errors` | Git patch-id 比对无独有补丁，记录已包含的修复历史。 |
| `copilot/review-existing-architecture` | 直接合入 4 份数据分析工作空间评审与设计文档。 |
| `copilot/runtime-os-architecture-evaluation` | 直接合入 4 份 Runtime 架构评估文档。 |
| `copilot/web-search-multi-source-review` | 直接合入 4 份检索及授权边界评审文档。 |
| `fix/report-rendering-studio-dialogs-20261030` | 直接合入报告与 Studio 的部署、浏览器验证记录。 |

对已替代的代码分支使用 `ours` 合并策略，记录其处理结果并保留当前主干实现。
原始提交保留在 Git 历史中，文档分支使用普通合并。
`branches.json` 记录分支 tip、逐条提交及处理方式；`file-coverage.json` 记录文件级 blob 比对。
旧 Workspace 文档的独立快照位于 `archive/runtime-workspaces-20261013`，原路径及哈希见
`historical-documents.json`。这些文档反映当时方案，当前行为以现有源码和测试为准。

## 现有实现与旧功能的对应关系

| 旧功能 | 当前实现与验证入口 |
| --- | --- |
| Workspace 目录、许可、权限、选择与恢复 | `api/workspace/WorkspaceService`、`WorkspaceCompatibilityMigration`、`WorkspaceIntegrationTest`、`WorkspaceMigrationTest` |
| 会话、任务、成果的归属和访问 | `api/security/ObjectAccessService`、`api/workspace/WorkspaceOriginService`、`WorkspaceIntegrationTest` 的跨用户/伪造请求检查 |
| 浏览器按租户、用户、空间隔离草稿与异步状态 | `web-app/src/js/utils/workspaceContext.js`、`workspaceDrafts.js`、`chatRuntimeState.js`、`workspace.test.js` |
| Python 环境及脚本版本隔离 | `PythonCapabilityService`、`PythonOperationWorkspace`、`PythonScriptIsolationIntegrationTest` |
| 持久化执行反馈、失败修复预算和诊断 | `ExecutionFeedback`、`CodingExecutionFeedbackService`、`ExecutionFeedbackContractTest`、`PythonExecutionLogBoundaryTest` |
| 报告验证、历史恢复和单次展示 | `VisualReportArtifacts`、`PythonVisualReport` 及其测试 |
| 完整 SDK、离线发布、中文字体 | `coding-agent-node/src/sdk-packages.js`、`scripts/build-docker-release.py`、`scripts/prepare-integrated-runtime.py`、`deploy/docker/python-runtime` |
| 收藏切换和防止重复提交 | `App.favoriteConversation`，本次增加成功切换、重复点击和服务拒绝的回归用例 |
| 基于模型窗口的上下文预算与结构化聚合 | `AgentOrchestrationEngine`、`SynthesisContextBudget`、`ContextEvidenceAggregator`、`ContextCompressionEnvelope` 及其测试 |

## 本地备份归档

原 `backup/pre-sync-20260729-1152` 已重命名为
`archive/local-pre-sync-20260729-1152`，保留原始两个提交。
已额外生成并通过 `git bundle verify` 验证的恢复包：
`../branch-archives/pre-sync-20260729-1152.bundle`。
恢复所需基线与 bundle SHA-256 记录于 `local-backup.json`。

这两个提交中的收藏切换、上下文预算功能已经由当前实现承接。
原备份还包含运行数据库、历史环境配置和旧静态构建产物，因此保留为本地归档。
恢复包没有提交到远端；产品代码不需要从这些旧运行数据恢复。

## 对已部署版本的影响

本次产品运行源码、数据库结构、依赖和部署配置保持与核对基线相同。
新增内容为历史文档、合并关系、审计记录，以及回归测试和测试初始化配置修正。
因此无需重新部署当前运行版本。

## 验证结果

后端 10 个相关测试套件、72 个用例全部通过；前端 5 个测试文件、67 个用例全部通过。
覆盖 Workspace 权限与迁移、浏览器空间隔离、收藏切换、上下文预算、执行反馈、Python 隔离和报告展示。
Workspace 集成测试同步了当前要求的管理员初始化密码长度，以及三个已注册工作空间的预期。
与核对基线相比无产品运行文件变化；所有远端分支均已成为本次整合提交的祖先。
详细结果见 `verification.json`。
