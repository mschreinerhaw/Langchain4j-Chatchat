# Runtime OS 专业 Agent 与联合分析下线

本版本在 `feature/unified-management-workspaces` 继续精简产品架构。集团或第三方专业 Agent 不再是分析算力，智能对话是统一入口。历史基线 Tag 和既有统一管理平台发布 Tag 均保留。

## 前端删除清单

- 删除 `DomainIntelligenceView.vue`、对应 JS、CSS 和专用测试；取消联合分析导航、组件注册、选择状态及 API 封装。
- 从 `AgentWorkshopView` 删除远程专业 Agent 卡片、接入弹窗、名称/来源/地址/能力/高级配置表单、连接测试、Agent Card 发现、注册、编辑、删除及启用入口。
- 删除旧流程专用样式、资源选择交互、帮助文案。旧路由即使出现在历史浏览器状态中，也不能加载未注册组件。
- 保留本地查询服务、已发布查询 API、知识库与 Skill 绑定、MCP 工具、工作流编辑和智能对话。

## 后端删除清单与依赖

完整文件清单见 [deleted-files.txt](deleted-files.txt)。主要删除：

- `common.runtime.agent`：专业 Agent 描述、注册、发现、凭据、执行、证据投影与协作契约。
- `agents.runtime.federation`：计算节点路由、专业 Agent 规划分发、健康状态、本地专业算力适配器。
- `integration.agent`：A2A SDK 网关、Agent Card 发现、注册实体/仓库、凭据和任务关联实现。
- `AgentAnalysisController`、`DomainIntelligenceAnalysisWorkflow`、`FederatedAgentAnalysisWorkflow`、旧通用模型选择工作流、`IntelligenceProviderRegistry`、`JpaAgentRegistry`。
- `SkillCatalogAgentComputeAdapter`、`ExternalEngineAgentRuntimeAdapter`、`ExternalAgentEngine`、专业分析专用证据模型及测试。
- Enterprise 管理控制器中的专业 Agent 注册、发现、健康检查、删除接口，以及编排引擎中的专业算力旁路。
- Maven 移除 A2A client、REST transport 和仅用于 Card 的 JSON canonicalization 依赖。

旧 `/api/v1/agent/analysis/**` 和 `/api/v1/enterprise/agent-registry/**` 不再有控制器实现；未添加空实现或兼容转发。共享认证、HTTP 客户端、模型调用、权限与任务接口继续使用原实现。

## 数据库、配置及回滚

部署前 `pg_dump -Fc` 备份 API/MCP 数据库，同时备份应用 JAR、compose 和部署环境文件。备份含敏感配置，仅保存在服务器受限目录，不入库。

1. 停止 API 后运行 [V20261022_04](../../database/migration/postgresql/V20261022_04__retire_professional_agents.sql)。先检查外部外键、视图依赖与归档名称冲突，发现引用则整批中止。
2. 将独占表 `agent_compute_definition`、`agent_provider_health`、`agent_a2a_task_link` 原表移入受限 `runtime_os_archive` schema。部署前盘点分别为 24、0、0 条，未发现外部外键。不清空记录或凭据。
3. `RetiredAnalysisMigration` 在应用启动完成时执行一次：逐行归档旧菜单、专用权限及绑定、定制 workspace 原始 JSON；仅移除 `domainAnalysis` 页面和 `workspace:analysis:api` 权限。菜单有自定义子项时中止，要求先重新挂载。
4. 共享运行配置权限 `system:agent-registry:manage` 保留原 ID/角色绑定，更新展示名称和资源路径。其原始值也归档。
5. 回滚先停止新 API，运行 [R20261022_04](../../database/rollback/postgresql/R20261022_04__restore_professional_agents.sql)，再启动备份版本。脚本恢复表、菜单、权限及 workspace，保留归档历史；若 workspace 在下线后又被修改则中止，避免覆盖管理员配置。

历史版本 SQL 保留，当前种子不再产生旧页面或专用权限。部署脚本 [deploy-retired-agents.py](../../scripts/deploy-retired-agents.py) 在替换前完成镜像构建、活动任务检查和备份；比较授权/固定模板指纹，并比较归档表全行摘要。

## 标准执行链路

用户问题 → Java Runtime 判断任务、规划并校验用户/工作空间/资源权限 → 必要的受控 MCP 固定模板/数据获取或上传文件引用解析 → 结构化分析目标、文件引用、Schema 和业务约束 → Node/Cline 生成代码 → Python Sandbox 隔离执行 → 错误反馈修复 → Runtime 校验、记录证据并汇总回答或图文报告。

知识问答继续使用既有通用模型与知识检索；不强制启动 Coding Agent。多个附件仍可在普通智能对话中比较分析，不再有独立联合分析页面或专业 Agent 邀请/协作流程。

## 保留的共享模块及原因

| 模块或名称 | 保留原因 |
| --- | --- |
| `GOOGLE_ADK_NATIVE`、`AgentRuntimeDispatcher`、普通 Skill Intelligence | 本地通用模型/方法调用，被现有 Skill 分析上下文与发布功能使用，不是外部专业算力注册 |
| MCP Skill federation、身份 federation | 分别是受控知识包同步与组织/身份接入，不参与专业 Agent 执行 |
| `AgentWorkshopController.AgentCard` | 本地查询服务列表 DTO，不含远程 Agent Card 获取逻辑 |
| `CompositeAnalysisWorkflow` | 通用 MCP/知识/计算工作流组合，已拆除专业分析元数据 |
| `domain_analysis_profile` | 标准分析提示词配置仍引用 |
| `agent_task_latest`、`analysis_evidence_archive`、`ds_skill_analysis_run`、`image_analysis_result` | 通用任务、证据、Skill 与图像分析业务仍引用 |
| `system:agent-registry:manage` | 原授权 ID 仍保护本地 Runtime 运行预算/配置；名称与路径已改为 Runtime 配置，不授予旧管理接口 |
| 历史迁移、历史设计/验收文档与归档表 | 支持版本追溯及回滚；旧设计文档标注废弃，不进入运行链路 |

这些是共享能力或历史审计材料，没有保留专业 Agent 运行适配器、注册表 ORM、A2A 网关或专业 Agent 编排层。

## 验证记录

详见 [tests.md](tests.md)。原始日志和含任务/用户数据的服务证据留在本地 target 或服务器私有 evidence 目录；仓库只记录脱敏结论。
