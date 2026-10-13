# SQL Studio 第一轮能力核查

日期：2026-10-13。基线：b88d8dd9。实施分支：feature/sql-studio-mvp。

## 代码证据与复用边界

| 能力 | 代码证据 | 决定 |
| --- | --- | --- |
| Monaco | web-app/src/js/views/DataScienceView.js 使用 editor.api、automaticLayout、Ctrl/Cmd+S、Python language contribution | 使用同一依赖和 worker；SQL 用独立 Monaco models 保存多标签内容、光标和滚动状态，避免影响 Python 单编辑器实现 |
| Python 文件/版本 | PythonDataScienceService.saveScript、PythonScriptEntity、PythonScriptVersionEntity | 仅支持安全 .py 文件名，必须绑定 READY Asset；现有保存不接收 expectedVersion。不能将 SQL 伪装为 Python 文件，增加应用层工程存储及原子版本检查 |
| AI 助手 | PythonCodeAssistantService 直接调用 ChatModel，并注入 DomainSkillRuntimePort | SQL 使用已存在 /agent/tasks，避免复制直接模型调用，更不建立 Agent Loop |
| Agent API | AgentTaskController、AgentTaskService、AgentEventStore | 提交、幂等、事件序号、持久状态、终态、取消均保持原协议；SQL 只准备已授权工程快照并关联 taskId |
| 产物 | UiArtifactService、FilesystemArtifactBlobStore | 保持原 Agent/UI Artifact；SQL 工程文件是可编辑源码，不能把可视化 manifest 当作源码仓库。导入须从服务端任务结果提取并记录来源 |
| 权限 | ApiAuthenticationFilter、ApiPermissionAuthorizationFilter、ObjectAccessService、ObjectRequestAdvice、ObjectAccessInterceptor、WorkspaceService | 完整复用身份及 DB RBAC；工程/文件授权调用中央 requireOwner，任务授权调用 requireTask。workspaceId/Header 不能提供对象权限 |
| Mapping 解析 | knowledgebase/.../DocumentTextExtractor 使用 Tika，已有 txt/md/csv/sql/pdf/doc/docx/xls/xlsx 支持 | 复用文档文本提取，不上传到知识库或生成新的共享检索资产；保留原始文档及提取文本 |
| Python SQL 依赖 | deploy/docker/python-runtime/requirements.lock、Dockerfile | 声明 sqlalchemy、数据库驱动、pyhive；未声明 sqlglot/sqlparse。文件虽然叫 lock 但未固定版本。本地 Docker context 为 desktop-linux，命名管道 dockerDesktopLinuxEngine 不存在，无法查询容器或 pip。未连接服务器，不能声称实际安装状态已验证 |

## 架构和数据影响

新增 SQL 应用的 Project、File、FileVersion、GenerationContext 四张表；不改旧表、不迁移 Python 文件、不修改 Core、Agent planner、工具发现与授权。生成上下文在提交前固定输入版本；模型输出先预览，用户明确应用后原子保存。冲突返回 409，整个批次回滚。

Workspace Manifest 增加 SQL Studio 页面；仅对标准 V2 manifest 做兼容更新，自定义 manifest 不覆盖。复用数据科学菜单/API 权限，新菜单不授予额外资产或工具权限。

本轮不连接生产数据库、不自动部署、不合并 main。模型和 Mapping 真实端到端测试需要独立、安全的模型配置及测试文档；模拟任务结果只能证明数据契约与持久化闭环。

核查完成后才增加应用代码。既有 Python/Workspace 定向基线命令运行通过；后续将原对象授权、会话和架构边界测试纳入最终回归，实际数量与范围见 [测试报告](03-test-report.md)。
