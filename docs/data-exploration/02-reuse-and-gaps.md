# 代码复用与缺口清单

审查依据与调用链见 [源码审查](01-source-review.md)。第一阶段代码严格复用现有模型解析、Coding SPI、任务预算、输入快照、证据审查、资产归档与 Python 控制面。

| 已有能力 | 实际复用点 | 本阶段补齐 |
|---|---|---|
| 认证、用户文件、内容哈希与存储 | ObjectAccessService、UserAssetService、RuntimeAssetArchive | 文件选择、数据引用与节点绑定 |
| 文件分析 | AssetAnalysisController.analyzeVisual → 原有 analyze 实现 → CodingAnalysisService.executePrimaryAnalysis | 请求级 reportMode=visual、taskType=DATA_ANALYSIS；要求计算走既有 Sandbox |
| Coding 接收、恢复、预算、模型回调 | CodingAgentSPI、ClineCodingAdapter、DurableCodingTaskService | 稳定业务节点 UUID 作为原分析 requestId；异常后继续同一次请求 |
| Python 计算、结果与证明 | CodingPythonExecutionBridge、McpPythonControlPlanePort、CodingEvidenceCollector | 接收可信服务端回执；客户端不能提交伪造结果 |
| 离线交互图 | PythonReportChart、checkedPlotlyFigure、plotlyFrameDocument | bar/line 展示转换，保留实际 X/Y；不支持的图形保留原图选择 |
| 原分析结果上下文 | 原 user-analysis-result CONTEXT 归档 | 保存父节点后，继承原文件及最近四份上下文 |
| 产品导航、菜单授权 | workspace_registry、sys_menu、sys_menu_permission | 增量页面注册，复用用户空间菜单权限，不新增角色授权 |
| 运行探索图 | explorationGraph.js | 保留其运行事件投影职责；另加轻量业务节点，记录父节点/问题/结果/配置 |

明确保留的边界：当前目录选择用户空间文件；不将 SQL Studio 源文件交付冒充业务查询结果。没有新建 SQL 数据访问权限、AI Agent、模型直连通道或 Python 执行器。

实际 SQL 供数进一步核对：`chatchat-api/src/main/java/com/chatchat/api/runtime/coding/data/CodingDataSupplyService.java` 的 request() → `chatchat-api/src/main/java/com/chatchat/api/runtime/analysis/PreauthorizedStructuredDataOperator.java` 的 execute() → RegisteredToolAnalysisOperator.execute() → 已授权 `sql_template_analysis_execute`；返回需有真实 rows/rowCount/complete。CodingDataSupplyService 再调用 CodingInputSnapshotService.captureForTask()，bindInputs() 将输入版本冻结到具体 operation。它与 SqlStudioService 的 SQL_WORKSPACE_V2 代码交付是不同能力。本阶段直接复用已交付且有归档证据的数据文件，避免另造交付通道。
