# Data Analysis Workspace — 接口与数据模型契约

本文件是第三份交付物，对应任务第五节「设计接口与数据模型」。前置结论见 [现有架构审查](data-analysis-workspace-current-state-review.md) 与 [最小增量架构](data-analysis-workspace-architecture.md)。

**本文件是设计草案，不是已实现代码。** 所有 Java 签名、DDL 与 API 路径均为待评审方案。

---

## 0. 契约版本与命名

| 项 | 取值 | 说明 |
| --- | --- | --- |
| Schema 前缀 | `analysis_workspace.*` | 与既有 `analysis_evidence_bundle.v1` / `runtime_evidence.v1` / `report_block.v1` 并列，不覆盖 |
| 表前缀 | `analysis_workspace_*`、`analysis_thread`、`analysis_node`、`analysis_dataset*`、`analysis_artifact` | **禁止**使用 `workspace_*`（已被产品 Workspace 占用） |
| Java 包 | `com.chatchat.api.analysisworkspace` | 应用层，不进 `chatchat-agents` / `chatchat-common` |
| API 前缀 | `/api/v1/analysis-workspaces` | 与 `/api/v1/workspaces`（产品 Workspace）区分 |

既有契约复用一览（**本次不新增版本**）：

| 既有 Schema | 版本 | 本次用途 |
| --- | --- | --- |
| `analysis_evidence_bundle.v1` | `EvidenceBundle` | 节点证据包 |
| `runtime_evidence.v1` / `runtime_evidence_lineage.v1` | `EvidenceRecord` / `EvidenceLineage` | 证据与血缘 |
| `analysis_summary_result.v1` | `AnalysisSummaryResult` | 节点结论 |
| `analysis_artifact.v1` | `AnalysisArtifactProtocol` | 论断类产物 |
| `report_block.v1` | `ReportBlockSchema` | 图表规格 |
| `visualization_capabilities.v1` | `visualization-capabilities.json` | 图表能力清单 |
| `enterprise_ui_artifact_v1` | `UiArtifactEntity` | 产物字节包 |
| `plan_step_checkpoint_v3` | `PlanStepCheckpoint` | 指纹失效语义参照 |
| `tool_execution_result.v1` | `StandardToolExecutionResultFactory` | 脱敏结果 |

---

## 1. 数据模型（PostgreSQL DDL 草案）

> 迁移文件建议：`database/migration/postgresql/V2026xxxx_01__analysis_workspace.sql`，同步提供 `mysql/`、`h2/` 版本（仓库现有三套方言目录）。全部 `CREATE TABLE IF NOT EXISTS`，**纯增量，不改动任何既有表**。

### 1.1 `analysis_workspace`

```sql
CREATE TABLE IF NOT EXISTS analysis_workspace (
  id                 VARCHAR(64)  PRIMARY KEY,
  tenant_id          VARCHAR(64)  NOT NULL,
  owner_user_id      VARCHAR(64)  NOT NULL,
  name               VARCHAR(200) NOT NULL,
  description        TEXT,
  status             VARCHAR(32)  NOT NULL,           -- ACTIVE | ARCHIVED
  visibility         VARCHAR(32)  NOT NULL,           -- PRIVATE | SHARED
  default_thread_id  VARCHAR(64),
  schema_version     VARCHAR(64)  NOT NULL,           -- analysis_workspace.v1
  version            BIGINT       NOT NULL DEFAULT 0, -- 乐观锁
  created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aw_tenant_owner ON analysis_workspace(tenant_id, owner_user_id, status);
```

**归属产品 Workspace**：不新增列，复用既有 `workspace_resource_origin`，写入 `resource_type='analysis-workspace'`、`resource_id=analysis_workspace.id`（该表已有唯一键 `(tenant_id, resource_type, resource_id)`）。

**`visibility=SHARED` 的授权**：不新建共享表。复用 `ResourceAuthorizationService` / `resource_grants`，资源类型新增 `analysis-workspace`。**禁止**自建第二套授权。

### 1.2 `analysis_thread`

```sql
CREATE TABLE IF NOT EXISTS analysis_thread (
  id             VARCHAR(64)  PRIMARY KEY,
  workspace_id   VARCHAR(64)  NOT NULL REFERENCES analysis_workspace(id),
  tenant_id      VARCHAR(64)  NOT NULL,
  name           VARCHAR(200) NOT NULL,
  goal           TEXT,                              -- 分析目标，进入 L0 上下文
  status         VARCHAR(32)  NOT NULL,             -- ACTIVE | ARCHIVED
  root_node_id   VARCHAR(64),
  node_count     INTEGER      NOT NULL DEFAULT 0,
  version        BIGINT       NOT NULL DEFAULT 0,
  created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_at_workspace ON analysis_thread(workspace_id, status, updated_at);
```

### 1.3 `analysis_node`

```sql
CREATE TABLE IF NOT EXISTS analysis_node (
  id                    VARCHAR(64) PRIMARY KEY,
  thread_id             VARCHAR(64) NOT NULL REFERENCES analysis_thread(id),
  workspace_id          VARCHAR(64) NOT NULL,
  tenant_id             VARCHAR(64) NOT NULL,
  parent_node_id        VARCHAR(64) REFERENCES analysis_node(id),  -- NULL = 根；多子 = 分支
  depth                 INTEGER     NOT NULL DEFAULT 0,            -- 冗余，防递归过深
  node_kind             VARCHAR(32) NOT NULL,  -- QUERY | TRANSFORM | VISUALIZE | NARRATE | IMPORT
  title                 VARCHAR(200),
  instruction           TEXT        NOT NULL,  -- 自然语言分析指令
  input_dataset_refs    TEXT        NOT NULL,  -- JSON: ["dsid@sha256", ...]
  output_dataset_ref    VARCHAR(200),
  status                VARCHAR(32) NOT NULL,  -- DRAFT|QUEUED|RUNNING|COMPLETED|FAILED|CANCELLED|STALE
  stale_reason          VARCHAR(64),           -- UPSTREAM_RESULT_CHANGED|DATASET_REFRESHED
                                               -- |INSTRUCTION_EDITED|AUTHORIZATION_REVOKED
  input_fingerprint     VARCHAR(64),           -- sha256(instruction‖params‖输入集 contentSha256)
  result_fingerprint    VARCHAR(64),
  current_run_id        VARCHAR(128),          -- AgentRun.runId
  summary_result_id     VARCHAR(128),          -- AnalysisSummaryResult.resultId
  evidence_snapshot_id  VARCHAR(128),          -- EvidenceStorePort.createSnapshot
  evidence_archive_id   VARCHAR(128),          -- AnalysisEvidenceArchivePort.Reference
  error_message         TEXT,
  version               BIGINT      NOT NULL DEFAULT 0,
  created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at            TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_an_thread_parent ON analysis_node(thread_id, parent_node_id);
CREATE INDEX IF NOT EXISTS idx_an_status       ON analysis_node(tenant_id, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_an_run          ON analysis_node(current_run_id);
```

### 1.4 `analysis_node_run`（执行历史，支持可回溯）

```sql
CREATE TABLE IF NOT EXISTS analysis_node_run (
  id                   VARCHAR(64)  PRIMARY KEY,
  node_id              VARCHAR(64)  NOT NULL REFERENCES analysis_node(id),
  tenant_id            VARCHAR(64)  NOT NULL,
  run_id               VARCHAR(128) NOT NULL,
  attempt              INTEGER      NOT NULL,
  input_fingerprint    VARCHAR(64)  NOT NULL,
  result_fingerprint   VARCHAR(64),
  run_status           VARCHAR(32)  NOT NULL,  -- 镜像 AgentRunStatus
  reproduce_outcome    VARCHAR(32),            -- REPRODUCED_IDENTICAL|REPRODUCED_DIVERGENT|INITIAL
  evidence_archive_id  VARCHAR(128),
  trace_expired        BOOLEAN      NOT NULL DEFAULT FALSE, -- AgentRunStore 保留期已过
  started_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  finished_at          TIMESTAMP WITH TIME ZONE,
  CONSTRAINT uk_anr_node_run UNIQUE(node_id, run_id)
);
CREATE INDEX IF NOT EXISTS idx_anr_node ON analysis_node_run(node_id, started_at DESC);
```

### 1.5 `analysis_dataset` 与 `analysis_dataset_version`

```sql
CREATE TABLE IF NOT EXISTS analysis_dataset (
  id                  VARCHAR(64)  PRIMARY KEY,
  workspace_id        VARCHAR(64)  NOT NULL REFERENCES analysis_workspace(id),
  tenant_id           VARCHAR(64)  NOT NULL,
  name                VARCHAR(200) NOT NULL,
  origin_kind         VARCHAR(32)  NOT NULL,  -- MCP_TEMPLATE | SQL_STUDIO | PYTHON_TEMPLATE
                                              -- | NODE_DERIVED | UPLOAD
  -- 来源四元组：绝不存储连接串 / 原始 SQL / 表名（架构文档 P3）
  origin_service_id   VARCHAR(128),
  origin_parent_tool  VARCHAR(200),
  origin_child_tool   VARCHAR(200),
  origin_arguments    TEXT,                   -- JSON，仅逻辑参数
  origin_node_id      VARCHAR(64),            -- NODE_DERIVED 时的产出节点
  current_version_id  VARCHAR(64),
  status              VARCHAR(32)  NOT NULL,  -- ACTIVE | REVOKED | ARCHIVED
  revoke_reason       VARCHAR(128),
  version             BIGINT       NOT NULL DEFAULT 0,
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ad_workspace ON analysis_dataset(workspace_id, status);

CREATE TABLE IF NOT EXISTS analysis_dataset_version (
  id              VARCHAR(64)  PRIMARY KEY,
  dataset_id      VARCHAR(64)  NOT NULL REFERENCES analysis_dataset(id),
  tenant_id       VARCHAR(64)  NOT NULL,
  content_sha256  VARCHAR(64)  NOT NULL,   -- DatasetHandle.contentSha256()
  row_count       BIGINT       NOT NULL,
  row_count_exact BOOLEAN      NOT NULL,   -- DatasetHandle.recordCountExact()
  schema_json     TEXT         NOT NULL,   -- 字段名/类型；来源标注 AUTHORITATIVE|FIRST_TEN_RECORDS
  artifact_id     VARCHAR(128) NOT NULL,   -- ui_artifact.artifact_id，字节经 ArtifactBlobStore
  byte_length     BIGINT       NOT NULL,
  produced_run_id VARCHAR(128),
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_adv_dataset_sha UNIQUE(dataset_id, content_sha256)
);
```

**数据集引用语法**：`<datasetId>@<contentSha256>`。省略 `@sha` 表示「当前版本」，是节点编辑期的默认值；节点一旦提交执行，**必须固化为带 sha 的全限定引用**，否则无法重现。

### 1.6 `analysis_artifact`

```sql
CREATE TABLE IF NOT EXISTS analysis_artifact (
  id                VARCHAR(64)  PRIMARY KEY,
  node_id           VARCHAR(64)  NOT NULL REFERENCES analysis_node(id),
  workspace_id      VARCHAR(64)  NOT NULL,
  tenant_id         VARCHAR(64)  NOT NULL,
  artifact_kind     VARCHAR(32)  NOT NULL,  -- CHART | TABLE | CODE | NARRATIVE | REPORT
  title             VARCHAR(200),
  spec_json         TEXT,                   -- CHART: report_block.v1；TABLE: 列投影/排序
  dataset_ref       VARCHAR(200),           -- datasetId@sha
  code_ref_kind     VARCHAR(32),            -- SQL_FILE_VERSION | PYTHON_TEMPLATE_ASSET
  code_ref_id       VARCHAR(128),           -- ds_sql_file_version.id | mcp_python_template_asset.asset_id
  code_ref_hash     VARCHAR(128),           -- mcp_python_template_asset.source_hash
  claim_json        TEXT,                   -- NARRATIVE: analysis_artifact.v1
  ui_artifact_id    VARCHAR(128),           -- 字节包；写入时 expires_at 必须为 NULL
  status            VARCHAR(32)  NOT NULL,  -- ACTIVE | STALE | REVOKED
  renderer_type     VARCHAR(32),            -- 来自 VisualizationCapabilityRegistry.rendererType
  version           BIGINT       NOT NULL DEFAULT 0,
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aa_node ON analysis_artifact(node_id, artifact_kind);
```

### 1.7 API 权限种子（**必需**，否则全部 403）

`ApiPermissionAuthorizationFilter` 无匹配策略直接 403，因此迁移必须包含：

```sql
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type,
                            resource_path, http_method, status, sort_order, created_at, updated_at)
SELECT 'aw-api', 'analysis-workspace:api:access', 'Analysis workspace access', 'api',
       '/api/v1/analysis-workspaces/**', '*', 'enabled', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'analysis-workspace:api:access');
```

管理端（如跨用户查看）必须使用**独立权限码**，不得与普通用户端共用宽路径（审查 §5.4 第 2 点）。

---

## 2. Java 服务接口草案

全部位于 `com.chatchat.api.analysisworkspace`。**所有方法第一参数为 `KernelDataScope`**，与既有 `EvidenceStorePort` / `AnalysisEvidenceArchivePort` 的风格一致，保证租户分区在编译期可见。

### 2.1 `AnalysisWorkspaceService`

```java
public interface AnalysisWorkspaceService {
    Workspace create(KernelDataScope scope, CreateCommand command);
    Workspace rename(KernelDataScope scope, String workspaceId, String name, String description);
    Workspace archive(KernelDataScope scope, String workspaceId);
    Optional<Workspace> find(KernelDataScope scope, String workspaceId);
    Page<Workspace> list(KernelDataScope scope, ListQuery query);

    /** 构建提交执行所需的分层上下文，预算由 ContextTokenEstimator / SynthesisContextBudget 决定。 */
    WorkspaceContext buildContext(KernelDataScope scope, String nodeId, ContextBudget budget);

    record CreateCommand(String name, String description, String visibility,
                         String originProductWorkspaceId) { }
    record Workspace(String id, String tenantId, String ownerUserId, String name,
                     String description, String status, String visibility,
                     String defaultThreadId, long version, Instant createdAt, Instant updatedAt) { }
    record ListQuery(String status, String keyword, int offset, int limit) { }
    record ContextBudget(int maxTokens, int maxAncestorNodes, int maxSampleRows) { }
    record WorkspaceContext(Map<String,Object> required,     // L0，不可压缩
                            List<Map<String,Object>> ancestors, // L1
                            List<Map<String,Object>> datasets,  // L2
                            List<Map<String,Object>> siblings,  // L3
                            int estimatedTokens,
                            List<String> droppedLayers,
                            boolean previewTruncated) { }
}
```

`WorkspaceContext.droppedLayers` 与 `previewTruncated` 必须一路传到 UI。`docs/runtime-single-brain-data-workspace.md` 明确要求不能把有界预览当作「模型已读全部」。

### 2.2 `DataThreadService`

```java
public interface DataThreadService {
    Thread create(KernelDataScope scope, String workspaceId, String name, String goal);
    Thread rename(KernelDataScope scope, String threadId, String name, String goal);
    Thread archive(KernelDataScope scope, String threadId);
    List<Thread> list(KernelDataScope scope, String workspaceId, String status);

    /** 祖先链，从根到该节点；深度上限 100，超限抛容量错误而非截断。 */
    List<AnalysisNodeService.Node> ancestors(KernelDataScope scope, String nodeId);

    /** 线程内完整 DAG 视图（前端 PlanDagGraph 直接消费）。 */
    ThreadGraph graph(KernelDataScope scope, String threadId);

    /** 按波次重放：每波调用 DeterministicPlanDagStateMachine.ready(...)，逐波提交 run。
     *  本方法不实现重试/租约/超时——全部由既有 Runtime 承担。 */
    ReplayHandle replay(KernelDataScope scope, String nodeId, ReplayOptions options);

    /** 标记某节点及其全部下游为 STALE，使用 DeterministicPlanDagStateMachine.descendants(...)。 */
    int markStaleFrom(KernelDataScope scope, String nodeId, String reason);

    record Thread(String id, String workspaceId, String name, String goal,
                  String status, String rootNodeId, int nodeCount, long version) { }
    record ThreadGraph(String threadId, List<GraphNode> nodes, List<GraphEdge> edges) { }
    record GraphNode(String nodeId, String parentNodeId, String nodeKind, String title,
                     String status, String staleReason, int depth) { }
    record GraphEdge(String fromNodeId, String toNodeId) { }
    record ReplayOptions(boolean stopOnFirstFailure, int maxWaves) { }
    record ReplayHandle(String replayId, List<String> waveNodeIds, List<String> runIds) { }
}
```

### 2.3 `WorkspaceDatasetService`

```java
public interface WorkspaceDatasetService {
    /** 把一次 run 内的 workspace:<fingerprint> 结果提升为持久数据集（架构文档 §4.3 提升路径）。
     *  同步、显式、可审计；写入 evidenceType=analysis_workspace.dataset_promotion 的 EvidenceRecord。 */
    Dataset promote(KernelDataScope scope, PromoteCommand command);

    /** 从 MCP 授权模板注册数据集。仅记录 (serviceId, parentTool, childTool, 逻辑参数)。 */
    Dataset registerFromTemplate(KernelDataScope scope, TemplateOriginCommand command);

    /** 重走完整授权模板链路取新数据，产生新版本。执行期必须 fresh 查询授权绑定。 */
    DatasetVersion refresh(KernelDataScope scope, String datasetId);

    Optional<Dataset> find(KernelDataScope scope, String datasetId);
    List<Dataset> list(KernelDataScope scope, String workspaceId, String status);
    List<DatasetVersion> versions(KernelDataScope scope, String datasetId);

    /** 读取前必须通过 AuthorizationRecheck；失败抛授权异常，不降级为部分读取。 */
    DatasetHandle.Page readPage(KernelDataScope scope, String datasetRef, long offset, int limit);

    /** 委派 DataWorkspaceOperations 的 QUERY_DATASET，不在本层实现聚合。 */
    Map<String,Object> query(KernelDataScope scope, String datasetRef, Map<String,Object> request);

    /** 当前授权是否仍然有效；撤销时把数据集置为 REVOKED 并级联标记节点 STALE。 */
    AuthorizationRecheck recheckAuthorization(KernelDataScope scope, String datasetId);

    record PromoteCommand(String workspaceId, String runId, String runDatasetReference,
                          String name, String producedByNodeId) { }
    record TemplateOriginCommand(String workspaceId, String name, String serviceId,
                                 String parentToolName, String childToolName,
                                 Map<String,Object> logicalArguments) { }
    record Dataset(String id, String workspaceId, String name, String originKind,
                   String currentVersionId, String status, String revokeReason, long version) { }
    record DatasetVersion(String id, String datasetId, String contentSha256, long rowCount,
                          boolean rowCountExact, Map<String,Object> schema, String schemaSource,
                          String artifactId, long byteLength, Instant createdAt) { }
    record AuthorizationRecheck(boolean allowed, String decision, String reason, Instant checkedAt) { }
}
```

**`schemaSource` 取值**：`AUTHORITATIVE`（来自 `SqlStudioMetadataService` / MCP metadata 治理）或 `FIRST_TEN_RECORDS`（来自 `CATALOG` 的有界推断）。UI 必须区分呈现，不得把推断结果显示为权威 schema（冲突裁决 C4）。

### 2.4 `AnalysisNodeService`

```java
public interface AnalysisNodeService {
    Node create(KernelDataScope scope, CreateCommand command);
    Node updateInstruction(KernelDataScope scope, String nodeId, String instruction);

    /** 以 nodeId 为父创建 DRAFT 子节点。O(1)，只复制引用，不复制数据/证据/产物。 */
    Node fork(KernelDataScope scope, String nodeId, String title);

    /** 提交执行：固化 datasetRef 为 datasetId@sha，构建上下文，委派 AgentRuntime。
     *  本层不做调度、不做重试、不调模型。 */
    Submission submit(KernelDataScope scope, String nodeId, SubmitOptions options);

    /** 用记录的引用重新执行；比对指纹得到 REPRODUCED_IDENTICAL / REPRODUCED_DIVERGENT。
     *  不覆盖旧结果与旧证据。 */
    Submission reproduce(KernelDataScope scope, String nodeId);

    void cancel(KernelDataScope scope, String nodeId, String reason);
    Optional<Node> find(KernelDataScope scope, String nodeId);
    List<NodeRun> runs(KernelDataScope scope, String nodeId);

    /** Runtime 终态回调：登记结果引用、证据快照/归档、产物，并向下游传播 STALE。 */
    Node completeFromRun(KernelDataScope scope, String nodeId, RunCompletion completion);

    record CreateCommand(String threadId, String parentNodeId, String nodeKind,
                         String title, String instruction, List<String> inputDatasetRefs) { }
    record Node(String id, String threadId, String workspaceId, String parentNodeId, int depth,
                String nodeKind, String title, String instruction,
                List<String> inputDatasetRefs, String outputDatasetRef,
                String status, String staleReason,
                String inputFingerprint, String resultFingerprint,
                String currentRunId, String summaryResultId,
                String evidenceSnapshotId, String evidenceArchiveId,
                String errorMessage, long version) { }
    record SubmitOptions(Integer timeoutMs, List<String> skillIds, String modelId) { }
    record Submission(String nodeId, String runId, int attempt, String status) { }
    record NodeRun(String id, String runId, int attempt, String inputFingerprint,
                   String resultFingerprint, String runStatus, String reproduceOutcome,
                   String evidenceArchiveId, boolean traceExpired,
                   Instant startedAt, Instant finishedAt) { }
    record RunCompletion(String runId, String runStatus, String summaryResultId,
                         String resultFingerprint, String outputDatasetRef,
                         String evidenceSnapshotId, String evidenceArchiveId,
                         String errorMessage) { }
}
```

### 2.5 `WorkspaceArtifactService`

```java
public interface WorkspaceArtifactService {
    Artifact attach(KernelDataScope scope, AttachCommand command);
    Optional<Artifact> find(KernelDataScope scope, String artifactId);
    List<Artifact> listByNode(KernelDataScope scope, String nodeId);
    int markStaleByDataset(KernelDataScope scope, String datasetId, String reason);
    void revoke(KernelDataScope scope, String artifactId, String reason);

    record AttachCommand(String nodeId, String artifactKind, String title,
                         Map<String,Object> spec,      // CHART: report_block.v1
                         String datasetRef,
                         String codeRefKind, String codeRefId, String codeRefHash,
                         Map<String,Object> claim,     // NARRATIVE: analysis_artifact.v1
                         String uiArtifactId) { }
    record Artifact(String id, String nodeId, String workspaceId, String artifactKind,
                    String title, Map<String,Object> spec, String datasetRef,
                    String codeRefKind, String codeRefId, String codeRefHash,
                    Map<String,Object> claim, String uiArtifactId,
                    String status, String rendererType, long version) { }
}
```

**`uiArtifactId` 写入约束**：工作空间产物对应的 `ui_artifact` 行必须 `expires_at IS NULL`。`UiArtifactEntity.readableAt()` 现有实现为 `"ACTIVE".equals(status) && (expiresAt == null || expiresAt.isAfter(instant))`，语义已兼容；实施时必须补回归测试，锁定「`expires_at=NULL` 的产物永不被 `expireDueArtifacts()` 回收」。

### 2.6 `WorkspaceVisualizationService`

```java
public interface WorkspaceVisualizationService {
    /** 用 VisualizationCapabilityRegistry.active(attributes) + ReportBlockSchema.validate(...) 校验。
     *  不新增图表类型，部署策略只能收窄清单。 */
    Validation validate(KernelDataScope scope, Map<String,Object> reportBlock,
                        Map<String,Object> runtimeAttributes);

    /** 按 datasetRef@sha 重新执行 ChartDataExecutor，产出可渲染数据。 */
    Rendered render(KernelDataScope scope, String artifactId);

    /** 当前可用图表类型（已授权 ∩ 已注册），供前端能力协商。 */
    List<Map<String,Object>> capabilities(KernelDataScope scope);

    record Validation(boolean valid, String rendererType, List<String> violations) { }
    record Rendered(String artifactId, String rendererType, Map<String,Object> block,
                    Map<String,Object> data, String datasetRef, String contentSha256,
                    boolean stale) { }
}
```

---

## 3. HTTP API 草案

统一包装为既有 `ApiResponse<T>`（`{code, data, message}`，`code=0` 为成功）。

### 3.1 Workspace

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/analysis-workspaces` | 创建 |
| `GET` | `/api/v1/analysis-workspaces` | 列表（分页，仅当前用户可见） |
| `GET` | `/api/v1/analysis-workspaces/{wsId}` | 详情 |
| `PUT` | `/api/v1/analysis-workspaces/{wsId}` | 重命名/描述 |
| `POST` | `/api/v1/analysis-workspaces/{wsId}/archive` | 归档 |
| `GET` | `/api/v1/analysis-workspaces/{wsId}/capabilities` | 可用图表类型 + 可用数据能力 |

### 3.2 Thread 与 DAG

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/analysis-workspaces/{wsId}/threads` | 创建 Thread |
| `GET` | `/api/v1/analysis-workspaces/{wsId}/threads` | Thread 列表 |
| `PUT` | `/api/v1/analysis-workspaces/{wsId}/threads/{tid}` | 重命名/目标 |
| `POST` | `/api/v1/analysis-workspaces/{wsId}/threads/{tid}/archive` | 归档 |
| `GET` | `/api/v1/analysis-workspaces/{wsId}/threads/{tid}/graph` | DAG 视图（PlanDagGraph 消费） |
| `POST` | `/api/v1/analysis-workspaces/{wsId}/threads/{tid}/replay` | 按波次重放 |

### 3.3 Node

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `.../threads/{tid}/nodes` | 创建节点（可带 `parentNodeId`） |
| `GET` | `.../nodes/{nodeId}` | 详情 |
| `PUT` | `.../nodes/{nodeId}` | 编辑 instruction（触发下游 STALE） |
| `POST` | `.../nodes/{nodeId}/fork` | 分叉 |
| `POST` | `.../nodes/{nodeId}/submit` | 提交执行 → 返回 `runId` |
| `POST` | `.../nodes/{nodeId}/reproduce` | 重现 |
| `POST` | `.../nodes/{nodeId}/cancel` | 取消 |
| `GET` | `.../nodes/{nodeId}/ancestors` | 祖先链 |
| `GET` | `.../nodes/{nodeId}/runs` | 执行历史 |
| `GET` | `.../nodes/{nodeId}/evidence` | 证据快照/归档引用 |

**执行事件流**：**不新建** SSE 端点。节点提交后返回 `runId`，前端复用既有 `fetchAgentTaskEvents(taskId)` / `agentTaskEvents.js` 订阅。新建第二套事件流属于重复建设。

### 3.4 Dataset

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `.../{wsId}/datasets` | 数据集列表 |
| `POST` | `.../{wsId}/datasets/from-template` | 从 MCP 授权模板注册 |
| `POST` | `.../{wsId}/datasets/promote` | 从 run 结果提升 |
| `GET` | `.../datasets/{dsId}` | 详情（含授权复核结果） |
| `GET` | `.../datasets/{dsId}/versions` | 版本列表 |
| `POST` | `.../datasets/{dsId}/refresh` | 重走授权链路刷新 |
| `GET` | `.../datasets/{dsId}/page?offset=&limit=` | 分页读取（每次复核授权） |
| `POST` | `.../datasets/{dsId}/query` | 委派 `QUERY_DATASET` |

**`/page` 与 `/query` 的强制约束**：
- `limit` 上限对齐既有边界（预览有界），响应必须带 `truncated` / `previewTruncated` 标志。
- 授权复核失败返回 `403` + `decision` + `reason`，**不返回部分数据**。

### 3.5 Artifact 与 Visualization

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `.../nodes/{nodeId}/artifacts` | 附加产物 |
| `GET` | `.../nodes/{nodeId}/artifacts` | 节点产物列表 |
| `GET` | `.../artifacts/{aid}` | 详情 |
| `GET` | `.../artifacts/{aid}/render` | 重新执行并返回可渲染数据 |
| `POST` | `.../artifacts/{aid}/revoke` | 作废 |
| `POST` | `.../{wsId}/visualizations/validate` | 校验 ReportBlock |

---

## 4. 分析节点 → Runtime Execution 映射（完整）

### 4.1 提交序列

```
1. 校验节点处于 DRAFT / COMPLETED / STALE / FAILED（RUNNING 拒绝重复提交）
2. 固化 inputDatasetRefs：datasetId → datasetId@contentSha256
3. 对每个输入数据集调 recheckAuthorization()；任一失败 → 整体失败
4. 计算 inputFingerprint = sha256(instruction ‖ 序列化参数 ‖ 各 contentSha256)
5. buildContext(nodeId, budget)，由 ContextTokenEstimator / SynthesisContextBudget 裁剪
6. 从各版本快照构造 DatasetHandle，装配当次 run 的 datasets Map
7. 构造 KernelDataScope：
     tenantId / userId / conversationId 来自认证上下文
     runId 由 Runtime 分配
     attributes += {analysisWorkspaceId, analysisThreadId, analysisNodeId, nodeAttempt}
     （不改 KernelDataScope 签名）
8. AgentRuntime.submit(AgentRunRequest)  ← 唯一执行入口
9. 写 analysis_node_run（attempt、inputFingerprint、run_status=PENDING）
10. 节点置 QUEUED，返回 runId
```

### 4.2 终态登记

```
AgentRun 终态
  → completeFromRun(...)
      ├ summaryResultId      ← AnalysisSummaryResult.resultId
      ├ resultFingerprint    ← 输出数据集 contentSha256 或结论内容哈希
      ├ evidenceSnapshotId   ← EvidenceStorePort.createSnapshot(scope, id, evidenceIds, meta)
      ├ evidenceArchiveId    ← AnalysisEvidenceArchivePort.archive(context, bundle).archiveId
      ├ outputDatasetRef     ← 如产生结果集，先 promote 再登记
      ├ 产物                 ← ReportBlock → analysis_artifact(CHART)
      │                        analysis_artifact.v1 论断 → analysis_artifact(NARRATIVE)
      └ 失效传播             ← resultFingerprint 变化 → markStaleFrom(nodeId)
```

### 4.3 `EvidenceLineage` 的工作空间边

节点完成时为其产出证据登记血缘：

```java
new EvidenceLineage(
    EvidenceLineage.SCHEMA_VERSION,
    producedEvidenceId,
    scope,                                   // attributes 含 analysisNodeId
    upstreamNodeEvidenceIds,                 // 父节点及输入数据集的证据 ID
    runId,                                   // producingTaskId
    nodeId,                                  // producingNodeId
    toolCallId,
    Map.of("analysisWorkspaceId", wsId, "analysisThreadId", threadId)
);
```

这使得「某个结论依赖哪些节点、哪些数据集版本、哪次工具调用」可以完全由既有 `EvidenceStorePort.lineage(...)` 回答，**无需新增血缘模型**。

---

## 5. 失效与重现状态表

| 场景 | 节点状态 | 产物状态 | 数据保留 | UI 必须呈现 |
| --- | --- | --- | --- | --- |
| 上游重执行，结果指纹变化 | `STALE` (`UPSTREAM_RESULT_CHANGED`) | `STALE` | 全部保留 | 「上游已变更，结果可能过期」+ 重跑入口 |
| 数据集 REFRESH | `STALE` (`DATASET_REFRESHED`) | `STALE` | 全部保留 | 新旧版本号对比 |
| 编辑 instruction | 自身及下游 `STALE` (`INSTRUCTION_EDITED`) | `STALE` | 全部保留 | 变更前后 instruction diff |
| 授权绑定撤销 | `STALE` (`AUTHORIZATION_REVOKED`) | `REVOKED` | 元数据与血缘保留，快照不可读 | 「数据授权已撤销」+ 不提供重跑 |
| 重现结果一致 | `COMPLETED` | `ACTIVE` | 新增一条 run 记录 | `REPRODUCED_IDENTICAL` |
| 重现结果不一致 | `COMPLETED` | `ACTIVE` | 新旧结果与证据**并存** | `REPRODUCED_DIVERGENT` + 差异对比 |
| `AgentRunStore` 保留期过 | 状态不变 | 不变 | `trace_expired=true` | 「执行轨迹已过期，结论与证据归档仍可用」 |

**不得把 `REPRODUCED_DIVERGENT` 显示为成功重现。** 模型调用非确定性，本设计保证可重放与可比对，不保证位级确定性。

---

## 6. Vue 3 前端组件结构与状态方案

### 6.1 约束

- **不引入 Pinia**，不引入 Vue Router。沿用 `src/js/utils/*State.js` 模块单例 + `App.js` 的 hash 导航 + `canAccessView()` 权限判断。
- 新页面以 `defineAsyncComponent` 注册进 `App.js` 的 `views` 映射，并经 `workspace_registry.manifest_json` 的 `pages` + 授权菜单交集暴露（与 `sqlStudio` 的接入方式完全一致）。
- hash 形态：`#/w/{productWorkspaceId}/analysisWorkspace`，分析空间/Thread/Node 的选中态由查询参数或内部状态承载，**不扩展 hash 段数**（避免改动 `App.js` 的路由解析）。

### 6.2 组件树

```
AnalysisWorkspaceView.js                  （新增，页面壳；三栏布局）
├─ AnalysisWorkspacePicker.vue            （新增，空间选择/创建）
├─ left: AnalysisThreadTree.vue           （新增；交互范式照搬 SqlProjectTree.js）
│    └─ 节点项：状态徽标 DRAFT/RUNNING/COMPLETED/FAILED/STALE
├─ center:
│    ├─ AnalysisThreadCanvas.vue          （新增，薄封装）
│    │    └─ PlanDagGraph.vue             （**复用**，dagre + vue-flow）
│    └─ AnalysisNodeEditor.vue            （新增）
│         ├─ instruction 输入             （复用 PromptComposer.vue）
│         ├─ 输入数据集选择器
│         └─ 执行状态                      （复用 agentTaskEvents.js 订阅 runId）
├─ right: AnalysisNodeInspector.vue       （新增，Tab 容器）
│    ├─ 结果     → ReportMarkdown.vue      （复用）
│    ├─ 图表     → VisualizationRenderer.vue（复用，ECharts）
│    ├─ 数据     → 结果网格               （复用 resultTableEnhancer.js）
│    ├─ 代码     → SqlEditorPane.js / PythonCodeDiff.vue（复用）
│    └─ 证据     → 血缘视图                （复用 EvidenceDebuggerView.js 的呈现范式）
└─ bottom: AnalysisDatasetPanel.vue       （新增；字段浏览复用 SqlSchemaExplorer.vue）
```

**新增组件 8 个，复用组件 9 个。** 复用比例高是刻意的——引入新编辑器、新图库或新 DAG 画布都属于重复建设。

### 6.3 状态模块（新增，沿用既有范式）

| 模块 | 职责 | 对标既有模块 |
| --- | --- | --- |
| `src/js/utils/analysisWorkspaceState.js` | 当前空间/Thread/选中节点、DAG 缓存、乐观更新 | `sqlStudioState.js` |
| `src/js/utils/analysisNodeDrafts.js` | 未提交 instruction 草稿；**键必须含 `tenantId + userId + workspaceId + nodeId`** | `workspaceDrafts.js` |
| `src/js/utils/analysisDatasetCache.js` | 数据集 schema / 预览页缓存，按 `datasetId@sha` 键 | `sqlMetadataSession.js` |
| `src/services/analysisWorkspaceApi.js` | API 客户端，复用 `workspaceHeaders()` 注入上下文头 | `sqlStudioApi.js` |

**草稿键必须带租户与用户维度。** 审查 §5.4 记录 `chatRuntimeState.js` 使用单一全局 localStorage 键，是已知隔离缺陷；新模块不得复制该缺陷。

### 6.4 前端必须如实呈现的治理标志

| 标志 | 来源 | 呈现要求 |
| --- | --- | --- |
| `previewTruncated` / `previewOmitted` | `DataWorkspaceOperations` 回执 | 不得暗示模型已读全部数据 |
| `semanticQualityCertified=false` | `BATCH_MODEL_INFERENCE` 回执 | 必须显示「执行通过类型校验，语义正确性未认证」 |
| `schemaSource=FIRST_TEN_RECORDS` | `CATALOG` 字段推断 | 与权威 schema 区分显示 |
| `trace_expired` | `analysis_node_run` | 显示「执行轨迹已过期」 |
| `REPRODUCED_DIVERGENT` | 重现比对 | 必须显示差异，不得标为「一致」 |
| `stale_reason` | 节点/产物 | 必须显示具体原因，不只显示灰色 |
| `droppedLayers` | `WorkspaceContext` | 提示哪些上下文因预算被省略 |

---

## 7. 契约变更影响面

| 变更 | 类型 | 影响 |
| --- | --- | --- |
| 新增 6 张表 + 权限种子 | 纯增量 | 无既有表改动 |
| `workspace_resource_origin` 新增 `resource_type` 取值 | 数据值扩展 | 无 DDL 变更 |
| `resource_grants` 新增资源类型 `analysis-workspace` | 数据值扩展 | 无 DDL 变更 |
| `KernelDataScope.attributes` 新增三个键 | 向后兼容 | **不改 record 签名** |
| `ui_artifact` 写入 `expires_at=NULL` | 行为约定 | 需回归测试锁定 |
| `EvidenceLineage.metadata` 新增两个键 | 向后兼容 | 已是开放 Map |
| 新增 `/api/v1/analysis-workspaces/**` | 新端点 | 必须配 `sys_permission` |
| 前端新增 1 个页面 + 8 组件 + 4 状态模块 | 新增 | 需进 `App.js` views 映射与 manifest |

**零破坏性变更。** 任何需要修改既有 record 签名、既有表结构或既有 Schema 版本号的方案，都应视为设计缺陷重新论证。

---

**下一步**：分阶段实施顺序、风险与验收标准见 [实施计划](data-analysis-workspace-implementation-plan.md)。
