# Runtime OS 数据探索：源码审查（2026-10-30）

本次在 `feat/ai-data-exploration-workspace` 开发，基线 main，工作树初始干净。未重构 Runtime。

## 真实调用关系

以下路径均相对仓库根目录。

1. 通用任务：`chatchat-chat/src/main/java/com/chatchat/chat/task/core/AgentTaskService.java` 管理业务任务；`chatchat-api/src/main/java/com/chatchat/api/controller/agent/AgentRuntimeController.java` 提供 `/api/v1/agent/runtime/runs/{runId}`、events、steps、observations、evidence 等读取接口。Coding 的 submit/get/resume/cancel 由 `chatchat-agents/src/main/java/com/chatchat/agents/runtime/coding/DurableCodingTaskService.java` 实现，使用 `RuntimeExecutionCheckpointPort` 保存契约、操作、预算及 SDK checkpoint（`coding.tasks.v1`），恢复不会重新获得预算。
2. 已有文件分析入口：`chatchat-api/src/main/java/com/chatchat/api/assets/AssetAnalysisController.java` 的 POST `/api/v1/interactions/user-assets/analyses`（另有 data-science 别名）接收 requestId、fileIds、contextIds、instruction、modelName；从认证派生 owner，读取原始文件，携带 SHA256/分块校验，构造 `AnalysisCoverageCoordinator.Request` → `CodingAnalysisService.executePrimaryAnalysis()`。runId 由 owner/requestId 确定，支持稳定请求恢复。
3. 分析与 Cline：`chatchat-api/src/main/java/com/chatchat/api/runtime/coding/analysis/CodingAnalysisService.java` 实现 `AnalysisCodingPort`；executePrimaryAnalysis → executeObserved → 输入快照、验证契约 → `CodingAgentSPI`。`.../runtime/coding/cline/ClineCodingAdapter.java` 实现该 SPI，submit 调用既有 Node Cline，get/resume 复用 DurableCodingTaskService；未增加模型通道。`coding-agent-node` 为现有 SDK 执行进程。
4. Python：`.../runtime/coding/cline/ClinePythonCallbackController.java`（`/internal/coding/cline`）→ `.../runtime/coding/execution/CodingPythonExecutionBridge.java` 的 start/get/reconcile → `McpPythonControlPlanePort` → `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/python/PythonDockerRuntime.java`。rows.jsonl、图表文件、stdout/stderr、executionReceipt 通过既有桥接与归档路径交付。`CodingEvidenceCollector` 验证并注册结果；CodingAnalysisService 仅在已完成且验证非 FAILED 时提供 resultAvailable、datasetReference、evidenceRefs。
5. 图文报告：`chatchat-common/src/main/java/com/chatchat/common/runtime/coding/VisualReportMode.java` 已定义 python_visual_report.v1，含 metrics/tables/sections/charts/dataSources/validation；图表可为 plotly（JSON 原始数列）或 PNG/SVG。`.../runtime/coding/evidence/VisualReportArtifacts.java` 和 CodingEvidenceCollector 负责审查产物。`chatchat-api/web-app/src/components/PythonReportChart.vue` → `src/js/components/PythonReportChart.js` → `src/js/utils/pythonVisualReport.js`，已有鉴权文件读取、checkedPlotlyFigure、隔离 iframe、交互缩放、表格降级与离线 Plotly runtime。PNG/SVG 本身没有可切换的结构化数列。
6. SQL：`.../datascience/sql/SqlStudioService.java` 的 submitCoding/result/resumeCoding → `.../runtime/coding/workspace/CodingWorkspaceService.java`。SQL_WORKSPACE_V2 交付代码，不自动取得业务查询结果；`SqlOutputBundle` 校验源文件包，不执行查询。真实分析数据供给由 `.../runtime/coding/data/CodingDataSupplyService.java`、`CodingInputSnapshotService.java` 完成。严禁把 SQL 生成成功或元数据当作客户资产数据。第一版选择用户空间已有 DOCUMENT/DATASET 文件（包括已归档的数据产物），不新造 SQL 执行权限。
7. 会话与持久化：`.../assets/UserAssetService.java` 使用 runtime_user_workspace/runtime_workspace_file + AssetBlobStore，owner 隔离、内容哈希、不可变 task/run 绑定；`RuntimeAssetArchive.context()` 保存分析上下文，CodingAnalysisService 注册的结果和执行引用可保留。`.../workspace/WorkspaceService.java` 的 workspace_registry 是产品入口，不是分析工作空间实例；它的用户偏好也不保存业务探索节点。现有探索图 `web-app/src/js/utils/explorationGraph.js` 是运行事件投影，不是可编辑业务节点树。
8. Vue：App.js 动态组件/manifest 导航，AssistantLayout 外壳；PythonReportChart/PythonVisualReport/ReportMarkdown 可复用。依赖已有 Vue 3.5、Plotly、ECharts、Vue Flow。遵守 check-vue-structure：Vue 模板、JS、CSS 分离。

## 复用与缺口

| 能力 | 复用 | 最小缺口 |
|---|---|---|
| 文件选择/隔离/原始字节 | UserAssetService | 工作空间选择 UI、分页 |
| AI 分析/执行/恢复 | AssetAnalysisController + CodingAnalysisService | 显式 visual 请求与稳定节点请求绑定 |
| 结果与执行证据 | CodingEvidenceCollector + RuntimeAssetArchive | 节点保存可信回执 |
| 图表 | PythonReportChart + checkedPlotlyFigure | 仅展示层 bar/line 配置，不调用 AI |
| 多轮分析 | 既有原文件和历史 CONTEXT 输入 | 父子节点及当前分支上下文 |
| 刷新恢复 | 数据库 + 资产归档 | 分析实例、节点、图表配置持久化 |

## 最小架构及文件修改计划

新增 `api/exploration/ExplorationWorkspaceStore.java`：一张 owner 隔离 JSON 文档表，CAS 版本更新，保存节点和可信回执；`ExplorationWorkspaceController.java`：工作空间创建/列表/读取、节点准备/执行/保存、图表配置接口，复用已有文件分析入口。新增 migration 初始化表和产品入口（沿用用户空间菜单权限），不授予新数据访问。

小改 AssetAnalysisController：保留原接口和 Request，新增供工作空间调用的 visual 方法；开启既有 reportMode/taskType，并要求有真实 Python 计算及离线 Plotly。复用同一模型解析、SPI 和 Sandbox。

新增 `views/DataExplorationView.vue`、`js/views/DataExplorationView.js`、独立 CSS 和 service：三栏布局、数据选择、业务节点、问题输入、恢复待完成任务、交互图及证据。App.js 和 data-science manifest 注册入口。小改 PythonReportChart，新增受限 presentation 属性和纯数据转换函数。

新增后端持久化/鉴权/继承/冲突测试，前端转换和真实 API 编排测试；运行现有资产分析/图表回归和构建。另提供真实环境验收脚本：必须读取实际客户数据、调用新接口并核查 Python execution/evidence、切换图表、重读与继承；没有可用服务或认证时记录阻塞，不把测试 mock 或历史证据当成本次在线验收。
