# Data Analysis Workspace — 现有架构只读审查

本文件是第一份交付物，对应任务第二节「审查现有架构」与第三节「参考 Data Formulator 机制」的冲突判定部分。

**审查范围与方法**：只读审查本仓库 `main` 分支工作副本，不修改任何生产代码，不部署，不执行业务任务。所有结论均以仓库内可核对的文件路径、类名、表名为依据；无法在仓库内核实的内容在文中显式标注为「未核实」，不做推断性断言。

**结论标注约定**（贯穿全部四份文档）：

| 标注 | 含义 |
| --- | --- |
| **可复用** | 已存在且可直接复用，不应重建 |
| **需扩展** | 已存在但语义/生命周期/持久化范围不足，需增量扩展 |
| **需新增** | 当前不存在，必须新增 |
| **需决策** | 与现有架构存在冲突或任务前提与代码事实不符，需要人工决策 |

---

## 0. 必须先纠正的三个任务前提

在进入逐项审查之前，有三个任务书中的前提与仓库事实不一致。若不先纠正，后续设计会建立在错误基线上。

### 0.1 仓库中不存在 MinIO —— **需决策**

任务第二节第 4 点要求梳理「PostgreSQL、MinIO、OpenSearch、RocksDB 的持久化职责」。实际核查结果：

- 全仓库 `pom.xml`、`*.yml`、`*.properties`、`*.xml` 中没有任何 MinIO / S3 客户端依赖或配置项（`grep -ri minio` 无命中）。
- 产物二进制存储由 `chatchat-chat/src/main/java/com/chatchat/chat/uiartifact/ArtifactBlobStore.java` 抽象，**唯一实现**是 `FilesystemArtifactBlobStore.java`，落地在本地文件系统。

因此本设计**不得**假定存在对象存储。Dataset 快照与图表产物的字节存储必须基于 `ArtifactBlobStore` 接口；若将来确需 MinIO，应作为该接口的新实现独立立项，不作为本次 Workspace 的前置条件。

### 0.2 `workspace` 这个名字在本仓库已被占用 —— **需决策**

仓库中已经存在两个完全不同的「Workspace」概念，而任务书要建设的是第三个：

| 名称 | 代码位置 | 实际语义 |
| --- | --- | --- |
| **产品 Workspace（App Shell）** | `chatchat-api/.../api/workspace/`（`WorkspaceService` / `WorkspaceManifest` / `WorkspaceController`），表 `workspace_registry`、`workspace_user_preference`、`workspace_resource_origin`（`database/migration/postgresql/V20261013_01__workspace_apps.sql`） | 多产品前端壳：菜单投影、页面清单、访问权限码、用户默认产品偏好。与数据分析无关 |
| **运行期 Data Workspace** | `chatchat-agents/.../orchestration/analysis/graph/DataWorkspaceOperations.java`，文档 `docs/runtime-single-brain-data-workspace.md` | 单次 Agent 运行内的数据操作面：`CATALOG` / `QUERY_DATASET` / `BATCH_MODEL_INFERENCE`，生命周期等于一次 run |
| **本次要建的 Data Analysis Workspace** | 不存在 | 跨会话持久、可分支、可回溯的用户分析工作空间 |

`docs/workspace/01-current-architecture.md` 第 23 行已经明确记录过这条边界：「现有 Data Workspace 指数据集、SpillStore、批量推理等分析执行上下文。本次 Workspace App 是用户产品界面，两者不能合并或互相替换。」本次新增的是第三种语义，**必须使用独立命名**，建议全程使用 `AnalysisWorkspace` / `analysis_workspace_*`，禁止复用 `workspace_registry` 表或 `WorkspaceService` 类名。

### 0.3 「复用现有 Agent Loop」在本仓库有强约束 —— **可复用（但有硬约束）**

根 `README.md` 已把 Kernel 越权判定写成硬性准则：「任何新增的模型调用，如果能够在配置未授权的情况下改变工具集合、依赖拓扑或终止固定工作流，就属于 Kernel 越权。」

Data Formulator 的核心交互恰恰是「模型自由生成数据变换代码并立即执行」。这与上述准则直接冲突，是本次设计最大的架构张力，详见 §6.2。

---

## 1. Agent Loop、Session、Execution、Artifact、Evidence Bundle

### 1.1 Agent Loop 与 Runtime 内核 —— **可复用**

| 构件 | 路径 | 职责 |
| --- | --- | --- |
| `AgentRuntime`（接口） | `chatchat-agents/.../agents/runtime/AgentRuntime.java` | 继承 `RuntimeOsKernel<AgentRunRequest, AgentRunResult>`，提供 `run` / `submit` / `cancel` / `find` / `list` / `events` / `steps` / `observations` / `snapshot` |
| `DefaultAgentRuntime` | `.../agents/runtime/execution/DefaultAgentRuntime.java` | 工作流类型 `agent-run-v1`；组合 `AgentRunExecutor` + `AgentRunStore` + `WorkflowRuntime`；`executeKernel()` 做租户校验 |
| `AgentOrchestrationEngine` | `.../agents/orchestration/AgentOrchestrationEngine.java` | 单次调用的编排节点动作与图边序 |
| `InterpretationAnalysisSession` | `.../agents/orchestration/InterpretationAnalysisSession.java` | **包私有、每次调用新建**的会话对象，持有 query/tenant/user/conversation/tools/plan/budgetCaps |
| `ModelNativeAnalysisHarness` | `.../orchestration/analysis/graph/ModelNativeAnalysisHarness.java` | 数据分析的唯一全局决策主体（见 `docs/runtime-single-brain-data-workspace.md`） |

**关键事实**：`InterpretationAnalysisSession` 是 `final class`、包私有、每次调用新建、**不持久化**。它不是一个可以挂 `workspaceId` 的领域实体。任何「把 Workspace 状态塞进 Session」的设计都是错的。持久状态只能走 `AgentRunStore` 和新增的 Workspace 表。

### 1.2 DAG 调度与节点状态机 —— **可复用（Data Thread 的直接底座）**

| 构件 | 路径 | 对本次设计的价值 |
| --- | --- | --- |
| `DeterministicPlanDagStateMachine` | `.../runtime/plan/execution/DeterministicPlanDagStateMachine.java` | `compile(plan)` → `Graph`；`ready(graph, remaining, completed)` 计算就绪节点；`decideBarrier(...)` 返回 `COMMIT_ALL` / `COMMIT_INDEPENDENT` / `REJECT_WAVE`；`descendants(...)` 计算下游 |
| `PlanDagControlPort` / `LocalPlanDagControlPort` | `.../runtime/plan/execution/` | `plan_dag_control.v1`，会话化 DAG 状态同步与屏障决策 |
| `NodeAttemptStore` | `.../runtime/plan/persistence/NodeAttemptStore.java` | 节点尝试状态机 `CREATED → READY → RUNNING → PREPARED → COMMITTED`（旁路 `FAILED/CANCELLED/SKIPPED`）；`commitBarrier`；租约 `acquireLease` / `heartbeat` / `reclaimExpiredLeases`（默认 30s） |
| `PlanStepCheckpoint` | `.../runtime/plan/persistence/PlanStepCheckpoint.java` | `plan_step_checkpoint_v3`，含 `definitionFingerprint`、`dependencyResultFingerprints`、`resultFingerprint` |

**`descendants(...)` + `dependencyResultFingerprints` 这两件东西合起来，已经实现了 Data Formulator「上游变更导致下游图表失效」所需的全部拓扑能力**。本次只需把它们从「单次 run 内」提升到「跨 run 持久」，不需要重新发明失效算法。这是整份设计中复用价值最高的发现。

### 1.3 Execution 与持久化 —— **需扩展**

`AgentRunStore`（`.../runtime/store/AgentRunStore.java`）是 `AgentRun` / `AgentRunStep` / `AgentObservation` / `AgentRunEvent` / `PlanStepCheckpoint` 的权威存储，实现为 `InMemoryAgentRunStore` 与 `RocksDbAgentRunStore`，并带 `AgentRunRetentionScheduler` 保留期清理与 `cleanupExpiredRuns()`。

`AgentRunStatus` = `PENDING | RUNNING | WAITING_CONFIRMATION | COMPLETED | FAILED | CANCELLED`。

**缺口**：`AgentRunStore` 是 RocksDB 本地嵌入式存储且带保留期清理，**不适合作为分析工作空间的长期事实源**。分析节点必须在 PostgreSQL 中保留自己的 `runId` 引用与结果指纹；run 过期后节点仍需可读（降级为「执行轨迹已过期，结果快照仍可用」）。

### 1.4 Artifact —— **需决策（当前存在两套并行模型）**

仓库中「artifact」一词对应两个互不相关的模型：

| 模型 | 路径 | 语义 | 存储 |
| --- | --- | --- | --- |
| **UI Artifact** | `chatchat-chat/.../uiartifact/UiArtifactEntity.java` + `UiArtifactService.java` | 大体积 UI 呈现包的外置化。字段：`artifactId/tenantId/taskId/schemaVersion(enterprise_ui_artifact_v1)/catalogVersion/revision/status/storeType/manifestKey/totalBytes/resourceCount/expiresAt`。有 TTL (`expireDueArtifacts()`) | `ArtifactBlobStore` → 文件系统 |
| **Analysis Artifact** | `.../orchestration/analysis/protocol/AnalysisArtifactProtocol.java` | `analysis_artifact.v1`，语义是**带证据的业务论断**（`artifactType: BUSINESS_CLAIM`，含 `claimClass`/`status`/`confidence`/`recordRefs`/`basisClaimIds`/`caveats`） | 随 `AnalysisSummaryResult.evidence` 流转 |
| **Draft Artifact** | `.../runtime/answer/DraftArtifactRuntimePolicy.java` | `draft_artifact_runtime_v2`，对未执行草稿产物的披露治理（`deliveryMode=DRAFT`, `executionStatus=NOT_EXECUTED`） | 仅 metadata |

**决策点**：Data Formulator 意义上的「产物」= 图表 + 数据表 + 代码 + 结论，是一个**可寻址、可重现的工作空间对象**。这三套都不是它：`UiArtifact` 是会过期的呈现包，`AnalysisArtifact` 是论断而非图表，`DraftArtifact` 是披露策略。

建议（详见架构文档 §4.5）：新增 `WorkspaceArtifact` 作为工作空间层的**引用与元数据**实体，**字节仍复用 `ArtifactBlobStore`，论断仍复用 `analysis_artifact.v1`**，并且明确关闭 TTL（工作空间产物不可自动过期）。严禁再造第四套字节存储。

### 1.5 Evidence Bundle 与证据内核 —— **可复用（本次追溯能力的核心资产）**

`chatchat-common/.../runtime/` 下已有一套完整且与实现解耦的证据内核：

| 构件 | 路径 | 说明 |
| --- | --- | --- |
| `EvidenceBundle` | `common/.../analysis/evidence/EvidenceBundle.java` | `analysis_evidence_bundle.v1`，字段 `schemaVersion/evidence/limitations/metadata` |
| `AnalysisEvidence` 家族 | 同包 | `ComputationEvidence` / `StructuredDataEvidence` / `DocumentAnalysisEvidence` / `ToolAnalysisEvidence` / `ExternalResearchEvidence` / `AgentAnalysisEvidence` / `ProjectedAnalysisEvidence` |
| `EvidenceRecord` | `common/.../runtime/evidence/EvidenceRecord.java` | `runtime_evidence.v1`，强制 `KernelDataScope` 租户分区，`contentSha256`，`payload` 或 `payloadReference` 二选一 |
| `EvidenceLineage` | 同包 | `runtime_evidence_lineage.v1`，`parentEvidenceIds` + `producingTaskId` + `producingNodeId` + `toolCallId` |
| `EvidenceStorePort` | 同包 | `runtime_os.evidence.store.v1`：`register` / `find` / `query` / `lineage` / `createSnapshot` / `snapshot` / `delete` |
| `AnalysisEvidenceArchivePort` | `common/.../analysis/spi/` | Judge 接受后的终态归档，返回 `Reference(archiveId, sha256, byteLength)`；实现 `chatchat-api/.../runtime/JpaAnalysisEvidenceArchive.java` |
| `KernelDataScope` | `chatchat-common/.../kernel/KernelDataScope.java` | `tenantId/userId/requestId/conversationId/runId/environment/attributes`，`partitionKey() = tenantId + ":" + (runId ?? requestId)` |

**结论**：任务第四节要求的「证据可追踪」已经有完整契约，`EvidenceLineage.parentEvidenceIds` 就是跨节点溯源边，`EvidenceStorePort.createSnapshot` 就是节点级证据快照。**本次不应新增任何证据模型**，只需让分析节点登记 `evidenceSnapshotId`。

**需扩展的一点**：`KernelDataScope` 没有 workspace/thread/node 维度。它有 `attributes` 开放字段，应通过 attributes 承载 `analysisWorkspaceId` / `threadId` / `nodeId`，**不改 record 签名**（改签名会波及整个 Kernel 调用面）。

### 1.6 分析契约 —— **可复用**

| 契约 | 路径 | Schema |
| --- | --- | --- |
| `AnalysisSummaryResult` | `.../analysis/model/AnalysisSummaryResult.java` | `analysis_summary_result.v1`；`scope` ∈ `DATASET_CHUNK` / `WORKSPACE_RESULT` / `FINAL_SYNTHESIS`；含 `inputSummaryResultIds` 血缘 |
| `AnalysisReportContract` | `.../analysis/model/AnalysisReportContract.java` | 报告发布契约 |
| `SemanticInsightContract` / `SemanticInsightRecipeCatalog` | `.../analysis/model/`、`.../analysis/insight/` | 算子化洞察配方（`SUM` / `TOP_N` / `CONTRIBUTION` / `GROWTH_RATE` / `RATIO` …），含参数校验 |
| `AnalyticalReasoningArcContract` | `.../analysis/contract/` | `analytical_reasoning_arc.v1`，10 级推理阶梯 `OBSERVED_FACTS → … → NEXT_ACTIONS`，治理策略 `CLASSIFY_AND_TRACE_DO_NOT_SUPPRESS_ANALYSIS` |
| `AnalysisContextPresentationContract` / `SynthesisContextBudget` / `ContextTokenEstimator` | `.../analysis/contract/`、`.../analysis/context/` | 上下文呈现与 Token 预算 |

**`ContextTokenEstimator` + `SynthesisContextBudget` + `ContextCompressionEnvelope` 已经是现成的 Token 预算实现**，任务第五节第 5 点要求的「Workspace Context 构建及 Token 预算策略」应当复用它们，而不是另写一套估算器。

---

## 2. MCP 数据资产发布、授权模板、参数绑定、执行与结果链路

### 2.1 链路总览 —— **可复用（且必须是唯一源数据通道）**

```
资产发现 → McpToolPublicationPipeline 发布 → template_query 父工具
   → mcp_template_query_binding 按 (service, role, domain, subject) 取模板
   → 生成子工具规格 → 模型只能在已绑定子工具中选择
   → McpAuthorizationService.authorize() 复核（执行期 fresh 查询，不吃缓存）
   → TemplateParameterValidator 按 mcp_argument_binding_policy 校验参数
   → SQL / HTTP / Python 执行器
   → StandardToolExecutionResultFactory 脱敏封装为 tool_execution_result.v1
```

关键文件：`chatchat-mcp-server/.../tool/McpToolPublicationPipeline.java`、`.../templatepublication/binding/TemplateQueryRouteResolver.java`、`.../templatepublication/admin/TemplateQueryAdminController.java`、`.../tool/StandardToolExecutionResultFactory.java`。

### 2.2 「模型不得绕过授权模板」的实际执行点 —— **可复用**

| 要求 | 执行点 |
| --- | --- |
| 子工具不可直接调用 | `TemplateQueryMcpToolPublisher` 拒绝直呼，强制经父工具路由 |
| 角色级模板授权 | `McpAuthorizationService` + `mcp_template_query_binding`（唯一键含 `service_id, role_id, domain_code, subject_type, subject_id`） |
| 原始 SQL / command / body 永不暴露给模型 | `mcp_argument_binding_policy` 只暴露 `logicalFilterFields` |
| 执行期不吃缓存 | `McpAuthorizationService` 执行前 fresh 查询绑定快照 |
| 结果脱敏 | `StandardToolExecutionResultFactory` 剥离连接信息与原始 SQL，按 `resultMapping` 投影列 |

**对本次设计的硬性结论**：Dataset 的「来源」字段**只能**是 `(serviceId, parentToolName, childToolName, 逻辑参数)` 四元组，**绝不能**是数据源连接串、原始 SQL 或表名。重放一个 Dataset = 用同样的四元组重新走一次授权链路，而不是「重跑保存下来的 SQL」。这条是本次设计不可妥协的红线。

### 2.3 Python Runtime —— **可复用，禁止新建沙箱**

`chatchat-mcp-server/.../python/`：`PythonCapabilityService`、`PythonDockerRuntime`、`PythonTemplateArgumentResolver`、`PythonMcpToolPublisher`、`PythonAdminController`。

表：`mcp_python_environment`（镜像必须锁定版本，依赖必须 `pkg==version`，非 root UID\:GID，超时 1–3600s）、`mcp_python_template_asset`（源码密文 + `source_hash`，输入/输出 schema）、`mcp_python_runtime_execution`（status / exit_code / stdout / stderr / duration_ms / container_id）。

容器约束：`--read-only`、`--cap-drop ALL`、`--security-opt no-new-privileges`、`--user 10001:10001`、默认 `--network none`、`--tmpfs /workspace/output`、`--rm`。

**结论**：Data Formulator 的「模型写 Python 做数据变换」在本仓库有现成且合规的落点——**发布为 Python 模板资产**。但这意味着变换代码要走「发布 → 授权」流程，不能即时执行任意代码。这是冲突点，见 §6.2。

### 2.4 SQL Studio —— **可复用（Dataset 的人工来源）**

`chatchat-api/.../datascience/sql/`：`SqlStudioService`、`SqlStudioMetadataService`、`SqlStudioGenerationToolPolicy`、`SqlOutputBundle`。
表（`V20261013_03__sql_studio.sql`）：`ds_sql_project` / `ds_sql_file` / `ds_sql_file_version` / `ds_sql_generation`。

`ds_sql_file` 已有 `version` 与独立的 `ds_sql_file_version` 历史表，`ds_sql_generation` 已有 `base_versions_json` + `task_id` + `applied`，**即「AI 生成基于哪些版本、是否已应用」的完整记录**。Data Thread 节点的「代码版本引用」语义可直接对齐这套模型。

### 2.5 图表与报告 —— **可复用**

- 能力清单：`chatchat-common/src/main/resources/runtime/visualization-capabilities.json`，`visualization_capabilities.v1`，`maxBlocks=6` / `maxRows=120` / `maxSeries=4`，类型 `line|bar|pie|scatter|metric|table`，每类带 `rules`（如 pie 要求「已验证的完整划分，Top-N 与抽样不算完整划分」）。
- 服务端治理：`VisualizationCapabilityRegistry`（部署策略只能**收窄**清单，`chatchat.visualization.allowed-types`）、`VisualizationPlanningNode`、`VisualizationPlanningContract`、`ReportVisualizationAudit`、`ChartDataExecutor`、`ReportBlockSchema`（`report_block.v1` 封闭声明式协议）、`VerifiedReportDataCatalog`、`ReportFactBindingAudit`、`ReportNumericAudit`、`ReportComposer`。
- 前端：`web-app/src/components/VisualizationRenderer.vue`（ECharts 6）、`AnalyticalReport.vue`、`ReportMarkdown.vue`、`EnterpriseUiArtifactRenderer.vue`、`src/js/utils/reportVisualization.js`。

**结论**：图表规格不需要新模型。工作空间的「图表」= 一个持久化的 ReportBlock + 其绑定的 datasetRef/字段。`ReportFactBindingAudit` / `ReportNumericAudit` 已经保证图表数字必须绑定真实数据，这正是 Data Formulator 所缺少的治理，应当保留而非绕过。

---

## 3. 运行期 Data Workspace 的真实能力与边界

`DataWorkspaceOperations`（`.../analysis/graph/DataWorkspaceOperations.java`）的构造签名是：

```
DataWorkspaceOperations(Map<String,Dataset> datasets, AnalysisEvidenceSpillStore store,
    GovernanceIsolationScope scope, ChatModel model, Runnable guard,
    Consumer<Map<String,Object>> observe, Map<String,Object> metadata)
```

能力（`capabilities()`）：

| 操作 | 语义 | 边界 |
| --- | --- | --- |
| `CATALOG` | 游标式发现本 run 内的数据集引用与字段（字段取前 10 条记录推断） | `limit 1..20` |
| `QUERY_DATASET` | 对句柄**全量扫描**聚合；`COUNT/SUM/AVG/MIN/MAX`；`EQ/NE/GT/GE/LT/LE`；点路径嵌套字段 | ≤20 聚合、≤8 分组；非数值拒绝；空值不计入数值聚合 |
| `BATCH_MODEL_INFERENCE` | 逐条语义执行，`batchSize 1..100`、`concurrency 1..8`、≤100,000 条 / 2,000 批 | 回执强制 `semanticQualityCertified=false`；失败记录不丢弃；`retryFailed` 仅重试失败项；SpillStore 关闭时此能力被移除 |

`DatasetHandle`（`.../analysis/dataset/DatasetHandle.java`）是数据面抽象：`recordCount()` / `readPage(offset,limit)` / `scan(pageSize,consumer)` / `execute(OperationRequest)` / `materialize(maxRows)`（显式上限逃生口）/ `contentSha256()`（**逐页计算，不构造巨型 JSON**）/ `descriptor()`。实现：`InMemoryDatasetHandle` / `PagedDatasetHandle` / `SpillDatasetHandle`。

**`DatasetHandle.contentSha256()` 直接提供了 Dataset 版本指纹**，是数据集版本管理的现成基础。

**三条关键边界（均为 `需扩展`）**：

1. `datasets` 是一个**普通 Map，随 run 结束而消失**。没有任何跨会话注册表。
2. `AnalysisEvidenceSpillStore`（`RocksDbAnalysisEvidenceSpillStore`）按 `GovernanceIsolationScope` 隔离，是**运行期溢出存储**，不是用户可浏览的数据集仓库。
3. 衍生结果登记为 `workspace:<fingerprint>`，**指纹即引用**。跨 run 复用会被「租户/运行隔离 + 源内容指纹」显式阻止（见 `docs/runtime-single-brain-data-workspace.md` 第 57 行）。要做跨会话复用，必须**显式设计一条合规的提升路径**，而不是放宽隔离。

---

## 4. 持久化职责实测

| 存储 | 实际职责 | 证据 | 判定 |
| --- | --- | --- | --- |
| **PostgreSQL** | 唯一的结构化事实源。`database/migration/postgresql/` 共 28 个版本化迁移（`V20260822_01` → `V20261014_02`）。表族：身份权限 `sys_*`；MCP 治理 `mcp_*`；Python `mcp_python_*`；SQL Studio `ds_sql_*`；产品工作空间 `workspace_*`；产物 `ui_artifact` | 迁移目录 | 新增分析工作空间表的**唯一落点** |
| **RocksDB** | 嵌入式 KV：`RocksDbAgentRunStore`（run/step/observation/event/checkpoint）、`RocksDbAnalysisEvidenceSpillStore`（分析证据溢出）、`chatchat-mcp-server/.../cache/rocksdb/McpRocksDbStore`、`RocksDbChatMessageDetailStore` | `chatchat-chat/pom.xml`、`chatchat-mcp-server/pom.xml` 依赖 `rocksdbjni 10.10.1.1` | **不可**作为工作空间事实源（本地嵌入 + 保留期清理） |
| **OpenSearch** | 知识库文档索引与 KNN 向量检索（`chatchat-knowledge-base/.../opensearch/OpenSearchDocumentIndexService`）、`chatchat-runtime-news` | `opensearch-rest-client` / `opensearch-java` 依赖 | 与分析证据**无关**，本次不引入 |
| **Redis** | `chatchat-mcp-server/.../cache/redis/RedisCacheConfig`，查询/API 响应缓存 | 同上 | 可选，非必需 |
| **文件系统（经 `ArtifactBlobStore`）** | UI Artifact 字节 | `FilesystemArtifactBlobStore` | **替代任务书中的 MinIO** |
| **MinIO** | **不存在** | 无命中 | 见 §0.1 |

---

## 5. Vue 3 前端现状

### 5.1 工程与路由 —— **需扩展**

- 单一 Vite 工程 `chatchat-api/web-app/`，Vue `^3.5.22`，Vite `^7.1.7`，Vitest `^3.2.4`。
- **没有 Vue Router**。导航靠 `src/js/App.js` 中的 `views` 映射 + `window.location.hash` + `hashchange`，页面 `defineAsyncComponent` 懒加载，`<KeepAlive>` 保活。
- Hash 形态：`#/w/{workspaceId}/{page}`（产品 Workspace 维度，非分析工作空间）。
- 页面清单来自服务端 `workspace_registry.manifest_json`，再与 `listAuthorizedMenus()` 取交集。

### 5.2 状态管理 —— **需扩展**

**没有 Pinia**。状态分散在 `src/js/utils/` 的模块化单例中：`workspaceContext.js`（注入 `X-Workspace-ID` 等头）、`sqlStudioState.js`、`sqlIdeState.js`、`chatRuntimeState.js`（单一 localStorage 键 + 30 分钟 TTL）、`agentTaskEvents.js`、`workspaceDrafts.js`、`conversationPersistenceBudget.js`、`studioConversation.js`、`studioAi.js`、`sqlMetadataSession.js`。

**结论**：新工作空间 UI **不应**引入 Pinia 或 Vue Router。引入会造成两套导航/两套状态范式并存，且与 `App.js` 的集中式导航、`canAccessView()` 权限判断冲突。应沿用 `utils/*State.js` 单例 + 服务端清单的既有范式。

### 5.3 可复用组件 —— **可复用**

| 组件 | 路径 | 复用点 |
| --- | --- | --- |
| `SqlProjectTree.js` | `src/js/components/sql-studio/` | 文件树（增删改名、右键菜单、嵌套） → Thread 树的交互原型 |
| `SqlSchemaExplorer.vue` | `src/components/sql-studio/` | Catalog→Schema→Table→Column 浏览、拖列入编辑器 → Dataset 字段面板 |
| `SqlEditorPane.js` / `SqlBottomPanel.js` / `SqlAssistantPanel.js` | 同上 | Monaco 编辑、结果网格分页、AI 面板 |
| `VisualizationRenderer.vue` | `src/components/` | ECharts 渲染，受注册类型约束 |
| `AnalyticalReport.vue` / `ReportMarkdown.vue` | 同上 | 报告组织、PDF 导出（`answerPdfExport.js`） |
| `PlanDagGraph.vue` + `planDagLayout.js` + `@dagrejs/dagre` + `@vue-flow/core` | 同上 | **DAG 可视化已存在**，Data Thread 分支树可直接复用，无需引入新图库 |
| `EnterpriseUiArtifactRenderer.vue` | 同上 | 服务端产物渲染 |

`PlanDagGraph.vue` 的存在意味着任务第五节第 3 点「Data Thread DAG、分支和回溯机制」的前端呈现**已有现成底座**。

### 5.4 既有遗留问题（会直接影响本次） —— **需决策**

`docs/workspace/01-current-architecture.md` §9 记录的问题，本次必须正视：

1. **对象级权限缺口**：`ConversationController` / `UiArtifactController` 只按 tenant 过滤，缺少完整的当前用户所有权判断。分析工作空间是强私有对象，不能继承这个缺口。仓库中已有 `chatchat-api/.../security/ObjectAccessService.java` 与 `ObjectRequestAdvice.java`，应核实其覆盖面后复用。
2. **新增 HTTP 入口必须配 DB 权限策略**：`ApiPermissionAuthorizationFilter` **无匹配策略直接 403**。每个新 API 都要有 `sys_permission` 记录。
3. **进程内 Token**：`EnterpriseAdminService` 的 `activeTokens` 是 `ConcurrentHashMap`，重启即失效。不能承诺工作空间在发布期间免登录。
4. **测试基线未全绿**：`AgentOrchestratorArchitectureTest` 存在既有的行数上限超限失败（Engine/Planner/Finalizer）。本次不得通过放宽或删除该断言来取得绿灯。

---

## 6. Data Formulator 机制对照：可借鉴 vs. 冲突

> 说明：以下对 Data Formulator 0.8 Beta 的描述基于其公开设计（`https://github.com/microsoft/data-formulator`）。本次沙箱环境未拉取该仓库源码，故**不逐行引用其实现**，只对其公开的机制范式做对照。凡涉及其内部实现细节处均标注为「未核实」。

### 6.1 可直接借鉴 —— **需新增（但语义可照搬）**

| DF 机制 | 本仓库对应 | 借鉴方式 |
| --- | --- | --- |
| **Data Threads 分析分支** | 无持久等价物 | 新增 `analysis_thread` + `analysis_node`，节点 DAG 复用 `DeterministicPlanDagStateMachine` 语义 |
| **从任意历史节点分叉继续** | `descendants()` + `PlanStepCheckpoint.dependencyResultFingerprints` | 分叉 = 以某节点为新父节点创建子节点，不复制上游数据，只复制引用 |
| **派生数据集沿用上游血缘** | `AnalysisSummaryResult.inputSummaryResultIds`、`EvidenceLineage.parentEvidenceIds` | 直接复用，不新建血缘模型 |
| **图表与数据的双向联动** | `ReportBlock` + `VerifiedReportDataCatalog` | 图表持久化为绑定 datasetRef 的 ReportBlock |
| **上游变更使下游失效** | `definitionFingerprint` / `dependencyResultFingerprints` 指纹失效 | 复用指纹链，节点状态新增 `STALE` |
| **工作空间持久化与生命周期** | 产品 `workspace_registry` 仅是壳 | 新增 `analysis_workspace`，PostgreSQL |

### 6.2 架构冲突 —— **需决策（五项）**

| # | DF 机制 | 与本仓库的冲突 | 建议裁决 |
| --- | --- | --- | --- |
| **C1** | 模型自由生成数据变换代码（Python/SQL）并即时执行 | 直接违反 README 的 Kernel 越权准则，也绕开 `mcp_template_query_binding` 授权模板 | **拒绝即时任意代码执行。** 变换只能走三条已授权通道之一：① `QUERY_DATASET` 声明式聚合；② 已发布的 Python 模板资产；③ SQL Studio 人工编写并经模板授权执行。模型只能**提议**，执行必须落到已授权通道 |
| **C2** | 数据全量加载进会话 / 前端，模型直接看到全表 | `DatasetHandle` 的全部设计（分页、有界预览、`materialize` 必须带上限、`previewTruncated`）就是为了禁止这一点 | **拒绝。** 工作空间沿用句柄 + 有界预览，模型永远不直接持有全量数据 |
| **C3** | Workspace 状态存在浏览器端（localStorage / 导出 JSON） | 与租户治理、对象权限、审计不兼容；`chatRuntimeState.js` 的单键 localStorage 已经是已知的隔离缺陷 | **拒绝。** 工作空间事实源在 PostgreSQL，浏览器只保留 UI 草稿 |
| **C4** | 数据集语义/字段推荐由模型自动生成并直接写入数据集定义 | `CATALOG` 的字段发现明确标注 `FIRST_TEN_RECORDS`，是有界推断而非权威 schema；权威元数据属于 `SqlStudioMetadataService` / MCP metadata 治理 | **折中。** 模型生成的语义标注存为**节点级建议**（可审阅），不得覆盖数据集权威 schema |
| **C5** | 图表类型与编码由模型自由决定 | `visualization-capabilities.json` 是封闭清单，部署策略只能收窄；`ReportVisualizationAudit` / `ReportNumericAudit` 强制数字绑定真实数据 | **保留现有治理。** 模型只能在已注册且已授权的类型内选择；pie 等类型的 `rules` 继续强制 |

### 6.3 明确不建议借鉴

- DF 的 Python 后端与数据加载层：任务已明确不迁移，且本仓库 `PythonDockerRuntime` 的安全基线（只读根、`cap-drop ALL`、无网络）高于通用 notebook 后端，不应降级。
- DF 的会话内 DuckDB / 内存引擎：会在 `DatasetHandle` 之外形成第二条数据面，且绕过结果脱敏层。
- DF 的前端状态容器（Redux 范式）：与本仓库无 Pinia / 无 Router 的既有范式冲突。

---

## 7. 审查结论汇总表

| 能力项 | 判定 | 落点 |
| --- | --- | --- |
| Agent Loop / Harness | 可复用 | `ModelNativeAnalysisHarness`，不建第二套 Runtime |
| DAG 就绪计算 / 屏障 / 下游计算 | 可复用 | `DeterministicPlanDagStateMachine` |
| 节点尝试状态机 / 租约 / 重试 | 可复用 | `NodeAttemptStore` |
| 指纹失效链 | 可复用 | `PlanStepCheckpoint` |
| Evidence Bundle / Record / Lineage / Snapshot / Archive | 可复用 | `chatchat-common/.../runtime/evidence/`、`.../analysis/evidence/` |
| Token 预算与上下文压缩 | 可复用 | `ContextTokenEstimator` / `SynthesisContextBudget` / `ContextCompressionEnvelope` |
| MCP 授权模板执行链 | 可复用 | 唯一源数据通道 |
| Python 沙箱执行 | 可复用 | `PythonDockerRuntime`，禁止新沙箱 |
| 图表能力清单与审计 | 可复用 | `visualization-capabilities.json` + `Report*Audit` |
| 前端 DAG 可视化 | 可复用 | `PlanDagGraph.vue` + dagre + vue-flow |
| 数据集跨会话持久化 | 需扩展 | `DataWorkspaceOperations` 的 run 内 Map → 持久注册表 |
| `KernelDataScope` 携带工作空间维度 | 需扩展 | 经 `attributes`，不改签名 |
| 节点结果在 run 过期后仍可读 | 需扩展 | PostgreSQL 保留指纹与快照引用 |
| 工作空间产物不过期 | 需扩展 | `UiArtifact` TTL 对工作空间产物需关闭 |
| Workspace / Thread / Node 实体 | 需新增 | 新表 + 新服务 |
| 分析工作空间前端 | 需新增 | 复用既有组件，不引入 Pinia / Router |
| `workspace` 命名冲突 | 需决策 | 采用 `AnalysisWorkspace` 独立命名 |
| MinIO 前提不成立 | 需决策 | 以 `ArtifactBlobStore` 为准 |
| Artifact 三套并行模型 | 需决策 | 新增引用层，不再造字节存储 |
| 对象级权限缺口 | 需决策 | 先核实 `ObjectAccessService` 覆盖面 |
| 模型自由代码执行（C1） | 需决策 | 建议拒绝，见 §6.2 |

---

**下一步**：架构设计见 [data-analysis-workspace-architecture.md](data-analysis-workspace-architecture.md)，接口与数据模型见 [data-analysis-workspace-contracts.md](data-analysis-workspace-contracts.md)，分阶段实施见 [data-analysis-workspace-implementation-plan.md](data-analysis-workspace-implementation-plan.md)。
