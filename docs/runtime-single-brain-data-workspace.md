# Single Brain + Data Workspace

Model decides. Runtime executes. User judges.

Driver–Worker 已从 Agent 分析执行架构删除，没有配置回退。删除范围包括独立数据集 Agent、Worker 分派/心跳/汇总协议、强制 DATASET_SYNTHESIS、Driver 审核与修复模型、专属提示词，以及对应的 Temporal 分析 Activity/Workflow。通用工具批处理、Temporal 工具/DAG/Agent 任务调度、状态、取消、重试、DatasetHandle 与 Evidence Bundle 保留。

`ModelNativeAnalysisHarness` 是数据分析的唯一全局决策主体。`DataWorkspaceOperations` 动态注入当前可执行的数据操作。Runtime 不按数据量自动分配分析 Agent，也不自行选择业务筛选、公式或分析阶段。最终正文由模型生成，Artifact 节点只校验引用、字段和授权并绑定真实数据；无需第二次总结调用。

## 1 万条结构化记录

原始数据保留在运行隔离的分页句柄中。模型先看到 Schema、统计、导航样本，再自行选择查询。`QUERY_DATASET` 对句柄全量扫描，不只计算提示词里的样本。

```json
{
  "operation": "QUERY_DATASET",
  "datasetReference": "已有的数据集引用",
  "groupBy": ["category"],
  "aggregates": [
    {"function": "COUNT", "as": "records"},
    {"function": "SUM", "field": "amount", "as": "total"}
  ],
  "filters": []
}
```

支持 COUNT/SUM/AVG/MIN/MAX，以及模型明确提供的 EQ/NE/GT/GE/LT/LE 筛选。COUNT 统计匹配行，数值聚合忽略空值；非数值输入拒绝执行。对象支持点路径（例如 `output.label`），同名原始字段优先。没有隐含业务筛选、自动去重、排名或补齐公式。

返回真实扫描数、匹配数、完整结果句柄和有界预览。SQL/Python 仍通过当前已注册且授权的外部工具或 DatasetHandle 的 provider operation 执行；此次未新增任意代码沙箱或本地 SQL 引擎。

## 1 万条必须逐条理解的文本

主模型主动调用 `BATCH_MODEL_INFERENCE`，明确范围、指令、结果 Schema、批次大小与并发。底层是无自主工具循环的模型推理执行池，不是 Worker Agent。

```json
{
  "operation": "BATCH_MODEL_INFERENCE",
  "datasetReference": "已有的数据集引用",
  "scope": {"mode": "all"},
  "batchSize": 100,
  "concurrency": 3,
  "instruction": "按本次分析目标逐条提取文本中明确出现的事件标签",
  "fields": ["text"],
  "outputSchema": {
    "type": "object",
    "properties": {"label": {"type": "string"}},
    "required": ["label"],
    "additionalProperties": false
  },
  "retryFailed": false
}
```

每条记录保留运行时 ID、原始 sourceRef、PENDING/PROCESSING/COMPLETED/FAILED 状态、尝试次数、类型校验后的输出或失败原因。缺失、重复、未知 ID 或 Schema 不兼容不会变成成功；失败记录不会从结果中消失。原始文本不截断；若单批超过当前模型上下文预算，返回明确失败，由主模型决定减少 batchSize、选择字段或改用其他操作。

请求必须使用持久化工作区，暂支持精确行数、全范围批量推理。每次请求最多 100,000 条、2,000 批，batchSize 1–100、并发 1–8；这些是执行容量边界。Schema 支持 type/properties/required/items/enum/additionalProperties/description/title，其他关键字拒绝，不能冒充完整 JSON Schema 实现。

例如 9,963 成功、37 失败时，回执如实返回这两个数字。主模型可用相同来源、指令、Schema 与批次参数设置 `retryFailed=true`，只执行失败记录；成功记录复用检查点。租户/运行隔离及源内容指纹阻止跨运行或修改输入后的错误复用。取消保留已写检查点。

`processedRecords` 只证明执行结果通过类型校验，不证明语义判断正确。回执明确携带 `semanticQualityCertified=false`，最终价值由用户判断。

## 结果与图文报告

所有计算/批量结果登记为 `workspace:<fingerprint>` 数据集。完整结果保存在 SpillStore，模型回执只携带预览、真实总数、存储描述与来源。`truncated=false` 表示完整结果已保留；`previewTruncated`/`previewOmitted` 表示上下文视图有界，不能据此宣称所有结果已被主模型阅读。

模型可继续 READ_RECORDS 分页、READ_CONTEXT 查看来源与操作描述，对批量输出调用 QUERY_DATASET 聚合。CATALOG 提供游标发现源数据和衍生结果。衍生结果同步登记到可视化数据目录，模型在同一总结输出中引用真实 datasetRef 和字段建议 ReportBlock。图表仍受前端注册类型、权限、字段和数据完整性约束。

## 验证与联机记录

新增验证覆盖：10,000 条全量聚合得到 COUNT=10,000、SUM=50,005,000、AVG=5,000.5；100 批文本推理保留 37 个失败记录，重试只提交这 37 条；成功检查点复用；失败结果完整分页；批量输出的嵌套字段再次聚合；Schema/范围/权限隔离/取消；主模型两轮选择计算并收到可视化目录。

1 万条语义推理使用受控测试模型验证执行与数据完整性，没有在生产发起 100 次付费模型调用；不以该测试证明真实模型语义正确率。

扩展检查中保留了仓库既有失败：AgentOrchestratorArchitectureTest 的行数上限（Engine 4602、Planner 1750、Finalizer 1700）在本次改动前已分别被 5998、1830、1825 行超出；本次 Engine 降至约 5792 行，未放宽上限。API 旧架构测试还有已迁移文件路径与 chatchat-chat Spring 依赖断言不匹配，相关模块未被本次改动改变。新架构行为与修改过的协议边界单独验证。

2026-10-09 最终相关回归 107 项通过，API 全模块编译与打包成功。最终包中检查不到退役的分析类，也没有 TemplateWorkerAnalysisContext；未将构建目录里残留的旧 class 带入部署。

部署主机 `192.168.195.221`，API 新进程 `1224616`，健康检查 UP。安装包 SHA-256 为 `1a4a1432e51338f5475829b8ddbd30da1181cd56f633b98f94623d3ee3ec37a5`；原包与原配置备份在 `/opt/chatchat-deploy-backup/single-brain-20261009/`。安装配置中的旧 Worker、统一分析回退开关已移除。

联机复测任务 `d5c8b103-92f2-4dd9-b0f6-97ce4851e09c`，原三只股票比较问题，qwen3.7-plus，SUCCESS，58 条事件、21 条实际返回记录。Skills 为 APPLIED，报告分析模型调用 1 次，无批量模型调用、无二次总结模型调用；运行元数据没有 Driver/Worker 字段。模型正文 2409 字符，与 MODEL_REPORT 发布契约完全相同。模型未建议图表，未执行强制图形规划。

生产前端使用该实际结果进行仅浏览器内回放：查询明细默认折叠，摘要灰色 12px，自动图表为 0，3 个手动图形分析入口保留，无 JavaScript 异常。没有写入或替换服务器会话。任务、事件、运行元数据、报告、截图、包检查与指标保存在 `target/codex-live/single-brain-20261009/`，最终构建/测试日志为 `target/single-brain-release.log`。
