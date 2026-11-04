# Data Analysis Workspace — 最小增量架构

本文件是第二份交付物，对应任务第四节「提出最小增量架构」。前置结论见 [现有架构审查](data-analysis-workspace-current-state-review.md)，接口细节见 [契约文档](data-analysis-workspace-contracts.md)。

---

## 1. 设计原则（不可妥协）

| # | 原则 | 执行含义 |
| --- | --- | --- |
| P1 | **Workspace 管状态，Runtime 管执行** | Workspace 层只写 PostgreSQL 中的持久事实与引用；一旦涉及执行，一律委派给 `AgentRuntime` / `ModelNativeAnalysisHarness`。Workspace 层不得出现任何 ready-node 计算、重试、租约或模型调用循环 |
| P2 | **不建第二套 Agent Runtime** | 新增服务只能**提交**执行请求并**登记**结果引用。`DeterministicPlanDagStateMachine` / `NodeAttemptStore` / `AgentRunStore` 保持唯一 |
| P3 | **模型不得绕过授权模板** | Dataset 的来源只记录 `(serviceId, parentTool, childTool, 逻辑参数)`，绝不记录连接串、原始 SQL、表名。重放 = 重走授权链路 |
| P4 | **增量扩展已有契约** | Evidence / Artifact / ReportBlock / AnalysisSummaryResult 不新建平行模型，只增加引用字段 |
| P5 | **命名隔离** | 一律 `AnalysisWorkspace` / `analysis_workspace_*`，不与产品 `workspace_registry` 混用 |
| P6 | **无对象存储假设** | 字节一律经 `ArtifactBlobStore`；不假定 MinIO |

---

## 2. 分层定位

```
┌───────────────────────────────────────────────────────────────┐
│ Vue 3 分析工作空间 UI（复用 SqlProjectTree / PlanDagGraph /   │
│  VisualizationRenderer / AnalyticalReport，无 Pinia、无 Router）│
└───────────────────────────────┬───────────────────────────────┘
                                │ /api/v1/analysis-workspaces/**
┌───────────────────────────────▼───────────────────────────────┐
│ Workspace 层（新增，位于 chatchat-api，纯持久状态与编排入口）   │
│  WorkspaceService · DataThreadService · DatasetService         │
│  AnalysisNodeService · ArtifactService · VisualizationService  │
│  —— 不含任何 Agent 循环、不含模型调用、不含工具执行 ——         │
└───────────────────────────────┬───────────────────────────────┘
                                │ 仅委派，不实现
┌───────────────────────────────▼───────────────────────────────┐
│ Runtime 层（既有，零改造或极小扩展）                           │
│  AgentRuntime / ModelNativeAnalysisHarness                     │
│  DeterministicPlanDagStateMachine / NodeAttemptStore           │
│  DataWorkspaceOperations（CATALOG/QUERY_DATASET/BATCH_...）    │
│  EvidenceStorePort / AnalysisEvidenceArchivePort               │
└───────────────────────────────┬───────────────────────────────┘
                                │ 唯一源数据通道
┌───────────────────────────────▼───────────────────────────────┐
│ MCP 治理层（既有，零改造）                                     │
│  template_query 父工具 → mcp_template_query_binding            │
│  → McpAuthorizationService → TemplateParameterValidator        │
│  → SQL / HTTP / PythonDockerRuntime                            │
│  → StandardToolExecutionResultFactory（脱敏）                  │
└───────────────────────────────────────────────────────────────┘
```

**模块落点**：Workspace 层六个服务放在 `chatchat-api/src/main/java/com/chatchat/api/analysisworkspace/`。理由是 `docs/workspace/01-current-architecture.md` 已确立「Workspace 服务应位于应用层」，且该模块已持有 `datascience/`、`workspace/` 等同类应用层服务。**不得**放进 `chatchat-agents`（会污染 Runtime 边界）或 `chatchat-common`（会把产品实体塞进协议层）。

---

## 3. 实体关系

```
 AnalysisWorkspace  1 ──── n  DataThread  1 ──── n  AnalysisNode
        │                         │                     │
        │                         │ parentNodeId（自引用，构成 DAG/分支）
        │                         │                     │
        │ 1                       │                     │ n
        └──── n  WorkspaceDataset ◄───────(输入/输出)────┘
                      │ n                                │
                      │ 1                                │ 1..n
              DatasetVersion                       WorkspaceArtifact
                                                         │
                                            ┌────────────┼────────────┐
                                        CHART         TABLE/CODE    NARRATIVE
                                     (ReportBlock)  (blob引用)   (analysis_artifact.v1)
```

**基数与约束**

| 关系 | 基数 | 约束 |
| --- | --- | --- |
| Workspace → Thread | 1\:n | Thread 不可跨 Workspace 移动 |
| Thread → Node | 1\:n | Node 不可跨 Thread 移动；跨 Thread 只能「引用」 |
| Node → Node | 自引用 | `parentNodeId` 为空 = 根节点；同一 Thread 内必须无环 |
| Node → Dataset | n\:m | `inputDatasetRefs[]` + 至多一个 `outputDatasetRef` |
| Dataset → DatasetVersion | 1\:n | 版本由 `contentSha256` 标识 |
| Node → Artifact | 1\:n | Artifact 必属于且仅属于一个 Node |
| Workspace → Dataset | 1\:n | Dataset 不跨 Workspace 共享（跨空间共享需显式复制 + 重新授权复核） |

**为什么 Dataset 挂在 Workspace 而不是 Thread**：Data Formulator 的数据集在多个 thread 间共享是其核心体验。挂 Thread 会导致分支时数据集被迫复制，破坏「分叉只复制引用」的轻量语义。

---

## 4. 六个逻辑模块

### 4.1 Workspace Service —— **需新增（但大量复用）**

**职责**：分析工作空间的 CRUD、归档、成员可见性、与产品 Workspace / 会话的关联。

| 子能力 | 判定 | 复用对象 |
| --- | --- | --- |
| 实体持久化 | 需新增 | 新表 `analysis_workspace` |
| 租户/用户隔离 | 可复用 | `ApiAuthenticationFilter` 注入的身份快照 |
| API 权限 | 可复用 | `sys_permission` + `ApiPermissionAuthorizationFilter`（**每个新端点必须配策略，否则 403**） |
| 对象级所有权 | 需决策 | `ObjectAccessService` / `ObjectRequestAdvice` —— 须先核实覆盖面（见审查 §5.4） |
| 归属产品 Workspace | 可复用 | `workspace_resource_origin`（已有 `resource_type` + `resource_id` 通用结构，新增 `resource_type='analysis-workspace'` 即可，**无需新表**） |

**明确不做**：不建第二套 RBAC、不复制菜单表、不在 manifest 里声明执行资格。能力可见性仍由 `EnterpriseToolRuntimePolicyProvider` / `ResourceAuthorizationService` 决定。

### 4.2 Data Thread Service —— **需新增**

**职责**：Thread 生命周期、节点 DAG 拓扑维护、分叉、回溯、失效传播。

| 子能力 | 判定 | 说明 |
| --- | --- | --- |
| Thread CRUD / 重命名 / 归档 | 需新增 | 表 `analysis_thread` |
| DAG 拓扑存储 | 需新增 | `analysis_node.parent_node_id` 自引用 |
| 就绪/下游计算 | **可复用** | 把 `(nodeId → parentNodeId)` 投影为 `DeterministicPlanDagStateMachine.Graph`，调 `ready(...)` 与 `descendants(...)`。**不自写拓扑算法** |
| 分叉（branch） | 需新增 | 见 §5 |
| 失效传播 | **可复用** | 指纹比对 + `descendants(...)`，见 §6 |
| 回溯（checkout） | 需新增 | 纯读路径：沿 `parentNodeId` 上溯构造祖先链 |

**与 Runtime 的边界**：`DeterministicPlanDagStateMachine` 是一个**无状态纯函数工具类**（`compile`/`ready`/`descendants` 均为确定性计算），在 Workspace 层调用它**不构成第二套 Runtime**。被禁止的是复制 `NodeAttemptStore` 的租约/重试/屏障提交逻辑——那些必须留在执行期。

### 4.3 Dataset Service —— **需扩展（扩展 `DataWorkspaceOperations` 的注册面）**

**职责**：持久数据集的注册、版本、快照、来源重放、权限复核、缓存。

| 子能力 | 判定 | 复用/扩展方式 |
| --- | --- | --- |
| 数据集元数据持久化 | 需新增 | 表 `analysis_dataset` / `analysis_dataset_version` |
| 内容指纹 | **可复用** | `DatasetHandle.contentSha256()`（逐页计算，不构造巨型 JSON） |
| 字段发现 | **可复用** | `CATALOG` 的 `FIRST_TEN_RECORDS` 推断；权威 schema 来自 `SqlStudioMetadataService` / MCP metadata 治理 |
| 分页读取 | **可复用** | `DatasetHandle.readPage(offset, limit)` |
| 聚合查询 | **可复用** | `QUERY_DATASET`（COUNT/SUM/AVG/MIN/MAX + EQ/NE/GT/GE/LT/LE） |
| 逐条语义处理 | **可复用** | `BATCH_MODEL_INFERENCE`（回执强制 `semanticQualityCertified=false`） |
| 快照字节 | **可复用** | `ArtifactBlobStore`（**非 MinIO**） |
| 跨 run 复用 | **需扩展** | 见下方「提升路径」 |
| 权限复核 | 需新增（薄层） | 见下方「复核契约」 |

**run 内 Dataset → 持久 Dataset 的提升路径（本设计的关键机制）**

`docs/runtime-single-brain-data-workspace.md` 明确记载：租户/运行隔离与源内容指纹会**阻止跨运行复用**。因此不能偷偷放宽隔离，必须走显式提升：

1. 一次 run 内产生 `workspace:<fingerprint>` 结果句柄（运行期语义，不变）。
2. 用户或节点完成时**显式发起 PROMOTE**：Workspace 层读取该句柄，经 `ArtifactBlobStore` 写出不可变快照，并在 `analysis_dataset_version` 登记 `contentSha256` + `rowCount` + `schemaJson` + 来源四元组。
3. 后续 run 消费持久数据集时，**不是**复用旧 run 的隔离作用域，而是从快照构造一个全新的 `DatasetHandle` 注入当次 run 的 `datasets` Map。隔离语义不变，复用的是**字节**而非**运行作用域**。
4. 提升为**同步、显式、可审计**操作，写入 `EvidenceRecord`（`evidenceType = analysis_workspace.dataset_promotion`）。

**权限复核契约（每次读取，而非仅首次）**

持久数据集的危险在于「首次授权、长期可读」。因此：

- `analysis_dataset` 存储来源四元组 `(serviceId, parentToolName, childToolName, logicalArguments)`，**永不存储**连接串/原始 SQL/表名（P3）。
- **读取快照**（`readPage` / `QUERY_DATASET`）：须通过 `McpAuthorizationService` 对来源四元组做一次**当前有效性复核**。绑定被撤销/禁用时，数据集转为 `REVOKED`，快照不可读，但元数据与血缘保留（审计不可擦除）。
- **刷新数据**（`REFRESH`）：必须完整重走授权模板链路，等价于一次新的工具调用。
- 复核结果带短 TTL 缓存（建议 60s），但**执行刷新时必须 fresh 查询**，与 `McpAuthorizationService.fetchExecutionSnapshot()` 的既有语义一致。

### 4.4 Analysis Node Service —— **需新增（纯委派）**

**职责**：节点生命周期、节点 → Runtime Execution 的映射、结果登记。

节点状态机（**刻意与 `NodeAttemptStore.State` 区分**，因为语义层级不同）：

```
DRAFT ──submit──► QUEUED ──► RUNNING ──┬──► COMPLETED ──(上游变更)──► STALE
                                        ├──► FAILED                      │
                                        └──► CANCELLED        re-execute─┘
```

| 节点状态 | 含义 | 与 Runtime 的关系 |
| --- | --- | --- |
| `DRAFT` | 已创建未提交 | 无 run |
| `QUEUED` | 已提交，等待 Runtime | 已持有 `runId`，`AgentRunStatus=PENDING` |
| `RUNNING` | 执行中 | 订阅 `AgentRunEvent` |
| `COMPLETED` | 成功并已登记结果引用 | `AgentRunStatus=COMPLETED` |
| `FAILED` / `CANCELLED` | 失败/取消，保留诊断 | 对应 Runtime 终态 |
| `STALE` | 上游输入指纹变化，结果仍可读但标注过期 | **Workspace 层独有**，Runtime 无此概念 |

**节点 → Execution 映射**（任务第五节第 4 点）：

| 节点字段 | Runtime 对应 | 说明 |
| --- | --- | --- |
| `nodeId` | `KernelDataScope.attributes["analysisNodeId"]` | 不改 `KernelDataScope` 签名 |
| `threadId` / `workspaceId` | 同上 attributes | 同上 |
| `runId` | `AgentRun.runId` | 一个节点一次执行对应一个 run |
| `inputDatasetRefs[]` | 注入当次 run 的 `DataWorkspaceOperations.datasets` Map | 由快照构造 `DatasetHandle` |
| `instruction` | `AgentRunRequest` 的 query | 原样传递 |
| `inputFingerprint` | 由 `(instruction, inputDatasetRefs 的 contentSha256, 参数)` 计算 | 失效判定依据 |
| `resultFingerprint` | 来自 `AnalysisSummaryResult` / 输出数据集的 `contentSha256` | 下游失效判定依据 |
| `evidenceSnapshotId` | `EvidenceStorePort.createSnapshot(...)` 返回 | 证据可追踪 |
| `evidenceArchiveId` | `AnalysisEvidenceArchivePort.Reference.archiveId` | 终态归档（含 sha256） |
| `summaryResultId` | `AnalysisSummaryResult.resultId` | 分析结论引用 |

**重执行语义**：重执行创建**新的 run**，节点保留 `runId` 历史列表（`analysis_node_run` 从表）。旧 run 的证据不删除——这是「可回溯」的前提。

**run 过期的降级**：`AgentRunStore` 有保留期清理。`analysis_node` 在 PostgreSQL 中保留 `resultFingerprint`、`summaryResultId`、`evidenceArchiveId` 与产物引用，因此 run 过期后节点仍能呈现结果与证据归档，仅「逐步执行轨迹」不可用。UI 必须如实标注 `executionTraceExpired=true`，**不得伪装为完整轨迹**。

### 4.5 Artifact Service —— **需扩展（引用层，不造字节存储）**

审查 §1.4 已指出仓库中已有三套 artifact 概念。本设计新增的是**引用与元数据层**：

| 产物类型 | 持久化内容 | 复用对象 |
| --- | --- | --- |
| `CHART` | ReportBlock JSON + `datasetRef` + 字段绑定 + `rendererType` | `report_block.v1`、`VisualizationCapabilityRegistry` |
| `TABLE` | `datasetRef` + 列投影 + 排序/筛选（**不复制数据**） | `DatasetHandle` |
| `CODE` | SQL/Python 文本引用 | SQL：`ds_sql_file_version.id`；Python：`mcp_python_template_asset.asset_id` + `source_hash` |
| `NARRATIVE` | 分析结论 | `analysis_artifact.v1`（`AnalysisArtifactProtocol`） |
| `REPORT` | 多 block 组合 | `AnalysisReportContract` + `ReportComposer` |

**三条硬规则**：

1. **字节一律经 `ArtifactBlobStore`**，不新增存储实现。
2. **工作空间产物不自动过期**。`UiArtifactEntity` 有 `expiresAt` 与 `expireDueArtifacts()`；工作空间产物若复用 `ui_artifact` 表，必须写入 `expires_at = NULL` 并在 `UiArtifactService.expireDueArtifacts()` 中确保 NULL 不被回收（现有 `readableAt()` 已是 `expiresAt == null || expiresAt.isAfter(instant)`，语义已兼容——**需在实施时写回归测试锁定**）。
3. **论断类产物不另起模型**，直接用 `analysis_artifact.v1` 的 `recordRefs` / `basisClaimIds` / `caveats`。

### 4.6 Visualization Service —— **需扩展（极薄）**

**职责**：图表规格校验、持久化、重现、失效。

| 子能力 | 判定 | 复用对象 |
| --- | --- | --- |
| 能力清单 | **可复用** | `visualization-capabilities.json`（`line/bar/pie/scatter/metric/table`，`maxBlocks=6`、`maxRows=120`、`maxSeries=4`） |
| 类型授权收窄 | **可复用** | `VisualizationCapabilityRegistry.active(attributes)`；部署策略只能收窄 |
| 规格校验 | **可复用** | `ReportBlockSchema.validate(...)`（封闭协议，非完整 JSON Schema） |
| 数字绑定审计 | **可复用** | `ReportFactBindingAudit` / `ReportNumericAudit` / `ReportVisualizationAudit` |
| 数据执行 | **可复用** | `ChartDataExecutor` + `VerifiedReportDataCatalog` |
| 前端渲染 | **可复用** | `VisualizationRenderer.vue`（ECharts 6） |
| **持久化图表规格** | **需新增** | `analysis_artifact` 的 `spec_json` |
| **重现** | 需新增（薄） | 按 `datasetRef@version` 重新执行 `ChartDataExecutor` |
| **失效** | 需新增（薄） | `datasetRef` 的 `contentSha256` 变化 → 图表标 `STALE` |

**明确拒绝**：不新增图表类型注册表、不引入新图库、不绕过 `ReportNumericAudit`。模型只能在已注册且已授权的类型内提议。

---

## 5. Data Thread DAG、分支与回溯

### 5.1 拓扑模型

Thread 内的节点构成有向无环图，边由 `analysis_node.parent_node_id` 承载。约定：

- 根节点 `parent_node_id = NULL`。
- **分支 = 同一父节点有多个子节点**。无需额外 branch 实体——这是最小建模，也与 Data Formulator 的 thread 语义一致。
- 允许**跨分支引用数据集**（`inputDatasetRefs` 可指向任意同 Workspace 数据集），但**不允许**跨分支建立父子边（保持单父树，分叉语义清晰，回溯链唯一）。

> 设计取舍说明：单父树（而非任意 DAG）是刻意选择。多父 DAG 会使「回溯链」变成「回溯集」，破坏 Data Formulator 的线性可读体验，也使 `STALE` 传播路径解释困难。数据层面的多输入通过 `inputDatasetRefs` 表达，已足够。

### 5.2 分叉

```
fork(nodeId) → 以 nodeId 为 parentNodeId 创建新 DRAFT 节点
```

- **只复制引用，不复制数据**：新节点继承父节点的 `outputDatasetRef` 作为自己的 `inputDatasetRefs` 默认值。
- 不复制证据、不复制产物、不复制 run。
- 分叉是 O(1) 写入，与上游数据量无关。

### 5.3 回溯

| 操作 | 语义 | 实现 |
| --- | --- | --- |
| `ancestors(nodeId)` | 从节点上溯到根的完整链 | 沿 `parent_node_id` 递归（PostgreSQL 递归 CTE 或应用层循环，深度上限见 §8） |
| `checkout(nodeId)` | 把 UI 上下文切到该节点的祖先链 | 纯读，不改数据 |
| `replay(nodeId)` | 按祖先链顺序重执行 | 调 `DeterministicPlanDagStateMachine.ready(...)` 逐波推进，每波提交 run |
| `diff(nodeA, nodeB)` | 比较两个分支的结论/数据 | 比对 `resultFingerprint` + `AnalysisSummaryResult` |

**`replay` 的职责边界**：Workspace 层只负责「按波次提交」，每一波内的调度/重试/租约全部由既有 Runtime 承担。Workspace 层**不得**实现重试或超时。

---

## 6. 失效（STALE）与重现

### 6.1 失效判定（复用既有指纹链）

`PlanStepCheckpoint` 已有 `definitionFingerprint`（节点定义）与 `dependencyResultFingerprints`（上游结果）。工作空间层镜像同一套语义：

```
nodeInputFingerprint = sha256(instruction ‖ 参数 ‖ 每个 inputDataset 的 contentSha256)
```

失效触发：

| 触发源 | 判定 | 传播 |
| --- | --- | --- |
| 上游节点重执行产生新 `resultFingerprint` | 当前节点 `inputFingerprint` 变化 | `descendants(...)` 全部标 `STALE` |
| 数据集 `REFRESH` 产生新版本 | 引用该数据集的节点 `inputFingerprint` 变化 | 同上 |
| 节点 `instruction` 被编辑 | 定义指纹变化 | 自身 + 下游标 `STALE` |
| 授权绑定被撤销 | 数据集转 `REVOKED` | 相关节点标 `STALE` 并附不可执行原因 |

**`STALE` 不等于删除**：结果、证据、产物全部保留并可读，仅在 UI 上标注「上游已变更，结果可能过期」。这是「可追溯」的要求——抹掉过期结果就无法解释历史结论。

### 6.2 重现（Reproduce）

重现一个节点 = 用**记录的引用**重新执行，而不是回放缓存：

1. 读取节点的 `inputDatasetRefs` 及其**版本**（`datasetRef@contentSha256`）。
2. 对每个数据集做**权限复核**（§4.3）。复核失败即终止，返回明确原因，**不降级为部分重现**。
3. 从快照构造 `DatasetHandle` 注入新 run。
4. 提交 Agent 执行，登记新 `runId` 到 `analysis_node_run`。
5. 比对新旧 `resultFingerprint`：一致 → `REPRODUCED_IDENTICAL`；不一致 → `REPRODUCED_DIVERGENT`（并保留两份结果与证据，**不覆盖**）。

**`REPRODUCED_DIVERGENT` 必须如实呈现。** 模型调用本身非确定性，声称「完全可重现」是不诚实的。本设计保证的是**可重放与可比对**，而非位级确定性。这一限制必须写进 UI 文案与验收标准。

---

## 7. Workspace Context 构建与 Token 预算

### 7.1 上下文分层

提交节点执行时，注入 Harness 的上下文按优先级分层，**高优先级永不被压缩掉**：

| 层 | 内容 | 预算策略 |
| --- | --- | --- |
| L0 必含 | 当前节点 instruction、输入数据集引用与 schema、授权能力清单 | 不可压缩；超限直接失败并提示 |
| L1 高 | 祖先链上最近 N 个节点的结论摘要（`AnalysisSummaryResult.content` 截断） | 按距离衰减，先丢最远 |
| L2 中 | 数据集字段统计、导航样本（`CATALOG` 的 `FIRST_TEN_RECORDS`） | 可压缩为字段名列表 |
| L3 低 | 同 Thread 内其他分支的结论、工作空间描述 | 预算不足时整层丢弃 |

### 7.2 预算实现 —— **全部复用**

| 需求 | 复用对象 |
| --- | --- |
| Token 估算 | `ContextTokenEstimator`（`.../analysis/context/`） |
| 合成预算上限 | `SynthesisContextBudget` |
| 压缩信封与披露 | `ContextCompressionEnvelope` |
| 上下文呈现规则 | `AnalysisContextPresentationContract` |
| 分析预算上限 | `AgentPlanBudgetPolicy.BudgetCaps`（已在 `InterpretationAnalysisSession` 中） |

**不新写估算器。** 若发现现有估算器不满足工作空间场景，正确做法是扩展 `ContextTokenEstimator`，而非在 Workspace 层另写一份。

### 7.3 必须遵守的既有披露约束

`docs/runtime-single-brain-data-workspace.md` 规定：`truncated=false` 表示完整结果已保留；`previewTruncated` / `previewOmitted` 表示上下文视图有界，**不能据此宣称所有结果已被模型阅读**。工作空间上下文构建必须原样传递这些标志，UI 必须如实呈现。

---

## 8. 容量与边界（防止无界增长）

| 维度 | 建议上限 | 理由 |
| --- | --- | --- |
| 每 Workspace Thread 数 | 200 | UI 可浏览性 |
| 每 Thread 节点数 | 500 | 递归祖先链与 DAG 计算成本 |
| 节点祖先链深度 | 100 | 递归 CTE 深度与上下文构建成本 |
| 每 Workspace 数据集数 | 500 | `CATALOG` 分页上限为 20，需配套分页 |
| 每节点产物数 | 20 | 与 `maxBlocks=6` 的报告约束协调 |
| 数据集快照单版本字节 | 复用 `UiArtifactProperties` 的既有阈值 | 不另设阈值 |
| 每节点保留 run 数 | 20（超出按时间淘汰轨迹引用，**不淘汰证据归档**） | 与 `AgentRunRetentionScheduler` 协调 |

这些是**容量边界**而非业务规则，超限必须返回明确错误，不得静默截断。

---

## 9. 与现有架构的冲突裁决汇总

| # | 冲突 | 裁决 | 依据 |
| --- | --- | --- | --- |
| C1 | 模型自由生成并即时执行变换代码 | **拒绝**。模型只能提议；执行必须落到 `QUERY_DATASET` / 已发布 Python 模板 / 经授权的 SQL | README Kernel 越权准则；P3 |
| C2 | 全量数据进模型上下文 | **拒绝**。沿用 `DatasetHandle` 分页与有界预览 | `DatasetHandle` 设计意图 |
| C3 | 工作空间状态存浏览器 | **拒绝**。事实源在 PostgreSQL | 租户治理与审计 |
| C4 | 模型自动改写数据集权威 schema | **折中**。存为节点级建议，可审阅，不覆盖权威 schema | `CATALOG` 的 `FIRST_TEN_RECORDS` 已标注为有界推断 |
| C5 | 模型自由选择图表类型 | **保留现有治理**。仅限已注册且已授权类型 | `VisualizationCapabilityRegistry` 只能收窄 |
| C6 | `workspace` 命名冲突 | **独立命名** `AnalysisWorkspace` | 已存在两个 workspace 语义 |
| C7 | MinIO 前提 | **以 `ArtifactBlobStore` 为准** | 仓库无 MinIO |
| C8 | 跨 run 复用被隔离阻止 | **显式 PROMOTE 路径**，复用字节而非运行作用域 | 不放宽隔离 |
| C9 | 节点状态机与 `NodeAttemptStore.State` 重名风险 | **分离命名**，语义层级不同 | 避免误用 |
| C10 | 工作空间产物与 `ui_artifact` TTL | **`expires_at=NULL` + 回归测试锁定** | 工作空间产物不可自动过期 |

---

## 10. 明确不建议实施的重复建设

| 不要做 | 原因 | 用什么替代 |
| --- | --- | --- |
| 新 Agent Loop / 工作流引擎 | 违反 P2 | `AgentRuntime` + `ModelNativeAnalysisHarness` |
| 新拓扑排序 / 就绪计算 / 下游计算 | 已有确定性实现 | `DeterministicPlanDagStateMachine` |
| 新重试 / 租约 / 超时 | 已有且含分布式租约 | `NodeAttemptStore` |
| 新证据模型 / 新血缘模型 | 已有完整契约 | `EvidenceRecord` / `EvidenceLineage` / `EvidenceSnapshot` |
| 新 Token 估算器 | 已有 | `ContextTokenEstimator` / `SynthesisContextBudget` |
| 新图表类型注册表 / 新图库 | 已有封闭清单与审计 | `visualization-capabilities.json` + ECharts |
| 新代码沙箱 | 已有强安全基线容器 | `PythonDockerRuntime` |
| 新对象存储实现 | 抽象已存在 | `ArtifactBlobStore` |
| 新 SQL 编辑器 / 文件树 / DAG 画布 | 前端已有 | `SqlEditorPane` / `SqlProjectTree` / `PlanDagGraph` |
| 引入 Pinia / Vue Router | 与既有范式冲突 | `utils/*State.js` 单例 + hash 导航 |
| 新 RBAC / 新菜单表 | 已有 | `sys_permission` / `sys_menu` / `ResourceAuthorizationService` |
| 本地 SQL 引擎 / 会话内 DuckDB | 绕过脱敏与授权 | `QUERY_DATASET` + MCP 模板 |
| 在 `KernelDataScope` 加字段 | 波及整个 Kernel 调用面 | 用 `attributes` 承载 |

---

**下一步**：实体字段、Java 接口与 API 草案见 [契约文档](data-analysis-workspace-contracts.md)；分阶段顺序、风险与验收见 [实施计划](data-analysis-workspace-implementation-plan.md)。
