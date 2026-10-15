# Data Analysis Workspace — 分阶段实施计划

本文件是第四份交付物，对应任务第六节的交付要求：分阶段实施顺序、关键风险、验收标准，以及不建议实施的重复建设内容。

前置文档：[现有架构审查](data-analysis-workspace-current-state-review.md)、[最小增量架构](data-analysis-workspace-architecture.md)、[接口与数据模型契约](data-analysis-workspace-contracts.md)。

**本文件不包含任何代码实现。** 按任务第六节要求，本轮只交付设计与审查文档。

---

## 0. 实施前必须完成的决策（阻塞项）

以下 5 项在编码开始前必须有明确结论，否则会导致返工或架构污染。

| # | 决策项 | 选项 | 推荐 | 阻塞范围 |
| --- | --- | --- | --- | --- |
| **D1** | 模型能否即时执行自生成的数据变换代码（冲突 C1） | A 允许 / B 仅限已发布模板与声明式聚合 | **B** | 全部阶段。选 A 等于推翻 README 的 Kernel 越权准则与 MCP 授权模板体系 |
| **D2** | 命名（冲突 C6） | A 复用 `workspace_*` / B 独立 `analysis_workspace_*` | **B** | 阶段 1 的全部 DDL 与类名 |
| **D3** | MinIO（冲突 C7） | A 本次引入 / B 以 `ArtifactBlobStore` 为准，MinIO 另立项 | **B** | 阶段 2 的数据集快照 |
| **D4** | 对象级权限缺口（审查 §5.4-1） | A 先修复 `ObjectAccessService` 覆盖面再建 / B 并行 | **A** | 阶段 1 不能在缺口上建强私有对象 |
| **D5** | 共享可见性 | A 本期只做 `PRIVATE` / B 一步到位做 `SHARED` | **A** | 降低阶段 1 范围，`SHARED` 留到阶段 6 |

**D1 是整个方案的分水岭。** 若决策为 A，则本套设计的 §P3、§4.3、§6.2 全部失效，需要重新论证授权模型，不能在当前设计上打补丁。

---

## 1. 分阶段顺序

每个阶段都以「可独立验收、可独立回滚」为边界。阶段之间严格串行，**不允许**在前一阶段验收未通过时开始下一阶段。

### 阶段 0：基线修复（前置，不属于本特性）

| 工作项 | 说明 |
| --- | --- |
| 核实并修复 `ObjectAccessService` / `ObjectRequestAdvice` 的对象所有权覆盖面 | 决策 D4 |
| 修复既有测试夹具路径问题（审查 §5.4-4） | 不得通过放宽或删除断言取得绿灯 |
| 确认 `AgentOrchestratorArchitectureTest` 既有行数超限的处置结论 | 本特性不得使其恶化 |

**验收**：定向回归全绿；新增的对象权限负例测试（越权读/改/删三类）全部拒绝。

**风险**：这是既有技术债，可能比本特性本身更耗时。若决策为并行（D4 选 B），必须在阶段 1 中为分析工作空间独立实现所有权校验，**不得依赖未修复的通用层**。

---

### 阶段 1：Workspace / Thread / Node 骨架（持久状态，无执行）

**范围**

| 工作项 | 复用/新增 |
| --- | --- |
| 6 张表 DDL（postgresql / mysql / h2 三方言） | 新增 |
| `sys_permission` 种子（普通端 + 管理端分开） | 新增 |
| `AnalysisWorkspaceService` / `DataThreadService` / `AnalysisNodeService`（**仅 CRUD + fork + ancestors + graph**，不含 submit） | 新增 |
| `workspace_resource_origin` 写入 `resource_type='analysis-workspace'` | 复用 |
| DAG 拓扑计算 | **复用** `DeterministicPlanDagStateMachine.compile/ready/descendants` |
| 控制器 + `ApiResponse` 包装 | 复用既有范式 |

**明确不做**：执行、数据集、产物、前端。

**验收标准**

1. 创建/重命名/归档 Workspace、Thread、Node 的 CRUD 全部通过。
2. `fork` 为 O(1)：对 1 个含 500 节点的 Thread 执行 fork，写入行数为 1。
3. `ancestors` 对深度 100 的链正确返回；深度 101 返回明确的容量错误**而非截断**。
4. `graph` 输出可被 `DeterministicPlanDagStateMachine.compile(...)` 接受。
5. **越权负例**：A 租户/A 用户无法读、改、删 B 的 Workspace / Thread / Node（9 条负例全部 403/404）。
6. 未配置 `sys_permission` 时端点返回 403（证明走了 `ApiPermissionAuthorizationFilter`）。
7. 迁移在空库与存量库上各执行一次均成功，且 `workspace_registry` 等既有表零变更（迁移前后 schema diff 为空）。

**回滚**：删除新表 + 权限种子，无既有数据影响。

---

### 阶段 2：Dataset Service 与提升路径

**范围**

| 工作项 | 复用/新增 |
| --- | --- |
| `analysis_dataset` / `analysis_dataset_version` | 阶段 1 已建表，本阶段启用 |
| `promote(...)`：run 结果 → 持久快照 | 新增（架构 §4.3） |
| `registerFromTemplate(...)`：仅存来源四元组 | 新增 |
| `refresh(...)`：重走授权链路 | **复用** MCP 模板链路 |
| 快照字节 | **复用** `ArtifactBlobStore` |
| 内容指纹 | **复用** `DatasetHandle.contentSha256()` |
| `readPage` / `query` | **复用** `DatasetHandle` / `QUERY_DATASET` |
| `recheckAuthorization(...)` | **复用** `McpAuthorizationService` |

**验收标准**

1. **红线测试**：扫描 `analysis_dataset` 全部列，断言不存在连接串、原始 SQL、表名。自动化静态断言 + 运行时写入拦截。
2. 提升一个 10,000 行结果集，`contentSha256` 与原句柄一致；复算一次仍一致。
3. 从快照构造的新 `DatasetHandle`，`QUERY_DATASET` 聚合结果与原 run 内一致（对照 `docs/runtime-single-brain-data-workspace.md` 已验证的 COUNT=10,000 / SUM=50,005,000 / AVG=5,000.5 口径）。
4. **授权撤销测试**：撤销 `mcp_template_query_binding` 后，`readPage` 返回 403，数据集转 `REVOKED`，元数据与血缘仍可读。
5. **跨租户测试**：B 租户无法通过任何路径读取 A 的数据集快照。
6. `refresh` 在执行期 fresh 查询授权绑定（断言未命中缓存）。
7. 提升操作写入 `evidenceType=analysis_workspace.dataset_promotion` 的 `EvidenceRecord`。
8. 快照字节全部经 `ArtifactBlobStore`，无直接文件 IO。

**回滚**：数据集功能开关关闭，已写快照保留（可读不可新建）。

---

### 阶段 3：Node 执行与 Runtime 映射

**范围**

| 工作项 | 复用/新增 |
| --- | --- |
| `submit(...)` 提交序列（契约 §4.1） | 新增（纯委派） |
| `completeFromRun(...)` 终态登记 | 新增 |
| `analysis_node_run` 执行历史 | 新增 |
| 上下文构建 + Token 预算 | **复用** `ContextTokenEstimator` / `SynthesisContextBudget` / `ContextCompressionEnvelope` |
| Agent 执行 | **复用** `AgentRuntime.submit` / `ModelNativeAnalysisHarness` |
| 证据快照与归档 | **复用** `EvidenceStorePort.createSnapshot` / `AnalysisEvidenceArchivePort.archive` |
| 血缘 | **复用** `EvidenceLineage` |

**验收标准**

1. **边界测试（最重要）**：静态断言 `com.chatchat.api.analysisworkspace` 包内**不存在**模型调用、不存在重试循环、不存在租约/心跳、不存在工具执行。可用既有 ArchUnit 风格测试（仓库已有 `*ArchitectureTest` 先例）。
2. 提交节点后 `runId` 非空，节点 `QUEUED`；Runtime 终态后节点正确转 `COMPLETED` / `FAILED` / `CANCELLED`。
3. `KernelDataScope` 签名未变更（编译期断言），三个工作空间键经 `attributes` 传递且在 `EvidenceRecord.scope` 中可见。
4. 提交时输入数据集引用被固化为 `datasetId@sha`（断言数据库中无裸 `datasetId`）。
5. 任一输入数据集授权复核失败 → 整体提交失败，**不产生 run**，不产生部分结果。
6. 节点完成后 `evidenceSnapshotId` 与 `evidenceArchiveId` 均非空，且 `EvidenceStorePort.lineage(...)` 能从结论溯回输入数据集版本与工具调用。
7. Token 预算：L0 永不被裁剪；预算不足时 L3→L2→L1 依次丢弃并在 `droppedLayers` 中如实报告；L0 超限直接失败。
8. 取消：`cancel` 后 Runtime run 进入 `CANCELLED`，节点同步；已写检查点保留。

**回滚**：提交入口下线，已有节点与证据保留只读。

---

### 阶段 4：分支、回溯、失效、重现

**范围**

| 工作项 | 复用/新增 |
| --- | --- |
| `markStaleFrom(...)` 失效传播 | 新增（**复用** `descendants(...)`） |
| 指纹比对 | 新增（语义对齐 `PlanStepCheckpoint`） |
| `replay(...)` 按波次重放 | 新增（**复用** `ready(...)`） |
| `reproduce(...)` + `REPRODUCED_IDENTICAL/DIVERGENT` | 新增 |
| `trace_expired` 降级 | 新增 |

**验收标准**

1. 重执行父节点产生新 `resultFingerprint` → 其全部下游（含跨分支深层）标 `STALE`，且 `stale_reason=UPSTREAM_RESULT_CHANGED`。
2. 数据集 `refresh` → 引用它的所有节点标 `STALE`（`DATASET_REFRESHED`）。
3. 编辑 instruction → 自身及下游标 `STALE`（`INSTRUCTION_EDITED`）。
4. 授权撤销 → 相关节点标 `STALE`（`AUTHORIZATION_REVOKED`）且**不提供重跑入口**。
5. **`STALE` 不删除任何数据**：结果、证据、产物在标记后仍可完整读取。
6. `replay` 波次顺序与 `DeterministicPlanDagStateMachine.ready(...)` 一致；`replay` 实现中不存在重试/超时逻辑（静态断言）。
7. 重现一致 → `REPRODUCED_IDENTICAL`；不一致 → `REPRODUCED_DIVERGENT` 且新旧结果与证据**并存**，旧记录未被覆盖。
8. 模拟 `AgentRunStore` 保留期清理后，节点仍能返回结论与证据归档，`trace_expired=true`。
9. 分叉后修改子分支，不影响父分支与兄弟分支的任何结果（隔离性测试）。

---

### 阶段 5：Artifact 与 Visualization

**范围**

| 工作项 | 复用/新增 |
| --- | --- |
| `analysis_artifact` 引用层 | 新增 |
| 图表规格校验 | **复用** `VisualizationCapabilityRegistry` + `ReportBlockSchema` |
| 数字绑定审计 | **复用** `ReportFactBindingAudit` / `ReportNumericAudit` / `ReportVisualizationAudit` |
| 图表重现 | **复用** `ChartDataExecutor` + `VerifiedReportDataCatalog` |
| 代码引用 | **复用** `ds_sql_file_version` / `mcp_python_template_asset.source_hash` |
| 论断 | **复用** `analysis_artifact.v1` |
| 字节 | **复用** `ArtifactBlobStore`（`expires_at=NULL`） |

**验收标准**

1. 未注册类型（如 `radar`）被拒绝，错误码 `UNAUTHORIZED_OR_UNREGISTERED_CHART`。
2. 部署策略 `chatchat.visualization.allowed-types` 收窄后，被排除类型不可创建；**反向扩张无效**（策略只能收窄）。
3. pie 的 `rules` 继续生效：Top-N / 抽样数据不得生成 pie。
4. `maxBlocks=6` / `maxRows=120` / `maxSeries=4` 边界生效，超限返回明确错误而非静默截断。
5. 图表数字必须绑定 `datasetRef@sha` 的真实值，`ReportNumericAudit` 不可绕过。
6. 数据集新版本 → 引用它的图表标 `STALE`；`render` 返回 `stale=true`。
7. **TTL 回归测试**：`expires_at IS NULL` 的工作空间产物在 `UiArtifactService.expireDueArtifacts()` 执行后仍可读。
8. 代码产物指向不可变版本（`ds_sql_file_version.id` / `source_hash`），原文件后续修改不改变产物内容。

---

### 阶段 6：Vue 3 工作空间 UI

**范围**：契约 §6 的 8 个新增组件 + 4 个状态模块 + 9 个复用组件接入；页面注册进 `App.js` views 映射与 `workspace_registry.manifest_json`（按 `sqlStudio` 的既有接入方式）。

**验收标准**

1. **无 Pinia、无 Vue Router**（依赖与导入静态断言）。
2. 页面在未授权菜单时不可见、直接访问 hash 被 `canAccessView()` 拒绝。
3. 草稿键包含 `tenantId + userId + workspaceId + nodeId`；切换用户后不串草稿（负例测试）。
4. DAG 画布使用 `PlanDagGraph.vue`，未引入新图库。
5. 执行事件复用 `agentTaskEvents.js`，**无新建 SSE 端点**。
6. 七个治理标志（契约 §6.4）全部在 UI 可见：`previewTruncated`、`semanticQualityCertified=false`、`schemaSource=FIRST_TEN_RECORDS`、`trace_expired`、`REPRODUCED_DIVERGENT`、`stale_reason`、`droppedLayers`。
7. 前端 production build 成功，定向单测通过。
8. 切换页面 / `KeepAlive` 失活 / 刷新三种场景下草稿行为符合预期（刷新后恢复不得仅依赖 `KeepAlive`）。

---

### 阶段 7（可选）：共享与协作

仅在 D5 选 B 或后续立项时执行：`visibility=SHARED`，复用 `ResourceAuthorizationService` / `resource_grants`，新增资源类型 `analysis-workspace`。**不得**自建第二套授权表。

---

## 2. 关键风险

| # | 风险 | 等级 | 影响 | 缓解 |
| --- | --- | --- | --- | --- |
| R1 | **D1 决策推翻设计前提** | 高 | 全盘返工 | 编码前书面确认 D1；若选 A，重新立项论证授权模型 |
| R2 | **Workspace 层逐渐长出第二套 Runtime** | 高 | 违反 P2，双调度冲突 | 阶段 3 的包级静态断言为**强制门禁**，每次 CI 执行 |
| R3 | **持久数据集成为授权绕过通道** | 高 | 合规事故：撤销授权后旧数据仍可读 | 每次读取复核（§4.3）；阶段 2 验收第 1、4、5 条为阻断性 |
| R4 | **对象级权限缺口被继承** | 高 | 跨用户数据泄露 | 阶段 0 前置；阶段 1 的 9 条越权负例为阻断性 |
| R5 | **跨 run 隔离被悄悄放宽** | 高 | 破坏既有运行隔离保证 | 只允许显式 PROMOTE 路径；禁止修改 `GovernanceIsolationScope` 判定 |
| R6 | 命名混淆导致误用产品 `workspace_registry` | 中 | 数据错乱 | D2 决策 + 迁移前后 schema diff 为空的验收项 |
| R7 | 失效传播在深 DAG 上性能劣化 | 中 | 接口超时 | 容量上限（节点 500 / 深度 100）；`descendants` 单次计算 + 批量 UPDATE |
| R8 | `ui_artifact` TTL 误删工作空间产物 | 中 | 产物丢失 | 阶段 5 验收第 7 条回归测试锁定 |
| R9 | 快照存储无界增长（无对象存储、本地盘） | 中 | 磁盘耗尽 | 沿用 `UiArtifactProperties` 既有阈值；监控盘使用率；版本数上限 |
| R10 | Token 预算不足导致 L0 超限、节点无法执行 | 中 | 可用性 | L0 超限必须明确失败并提示收缩输入，**不得静默裁剪必含层** |
| R11 | 既有测试基线未全绿被用作「本次通过」的借口 | 中 | 质量失控 | 阶段 0 固定基线快照；本特性只对增量负责，但不得使基线恶化 |
| R12 | 前端引入 Pinia/Router 造成双范式 | 中 | 维护成本 | 阶段 6 静态断言 |
| R13 | 「可重现」被过度承诺 | 中 | 信任受损 | 文案与验收统一为「可重放、可比对」；`REPRODUCED_DIVERGENT` 必须如实呈现 |
| R14 | 迁移在存量生产库上失败 | 中 | 发布中断 | 全部 `IF NOT EXISTS`；空库与存量库各演练一次；三方言同步 |
| R15 | 进程内 Token 重启失效影响体验 | 低 | 发布期需重登 | 如实说明，不借本特性重写认证 |

---

## 3. 整体验收标准（跨阶段）

特性整体可发布，必须同时满足：

### 3.1 架构边界（阻断性）

| # | 标准 | 验证方式 |
| --- | --- | --- |
| A1 | 未新增第二套 Agent Runtime | 包级静态断言：无模型调用、无重试、无租约、无工具执行 |
| A2 | 未新增证据模型 / 血缘模型 / Token 估算器 / 图表注册表 / 代码沙箱 / 对象存储实现 | 代码评审清单 + 依赖断言 |
| A3 | `KernelDataScope` / `EvidenceRecord` / `EvidenceLineage` / `EvidenceBundle` / `report_block.v1` 签名与版本号未变更 | 编译期 + schema 版本断言 |
| A4 | 既有表零结构变更 | 迁移前后 schema diff 为空 |
| A5 | 未使用 `workspace_*` 命名 | 静态扫描 |

### 3.2 治理与安全（阻断性）

| # | 标准 |
| --- | --- |
| B1 | 数据集表中不存在连接串、原始 SQL、表名 |
| B2 | 每次数据读取都复核当前授权；撤销后不可读 |
| B3 | 刷新数据完整重走授权模板链路，执行期 fresh 查询 |
| B4 | 跨租户、跨用户越权负例全部拒绝 |
| B5 | 每个新端点都有 `sys_permission` 策略；管理端与用户端权限码分离 |
| B6 | 模型无法通过工作空间获得任何未经模板授权的源数据 |
| B7 | 图表只能使用已注册且已授权类型；数字绑定审计不可绕过 |

### 3.3 功能完整性

| # | 标准 |
| --- | --- |
| C1 | **持久化**：工作空间、Thread、Node、Dataset、Artifact 跨会话、跨重启可读 |
| C2 | **可分支**：任意节点可 fork，分支间互不影响，fork 为 O(1) |
| C3 | **可回溯**：祖先链完整；run 过期后结论与证据仍可读并如实标注 |
| C4 | **可重现**：可重放 + 可比对；一致/不一致如实区分，旧结果不被覆盖 |
| C5 | **证据可追踪**：任一结论可溯回节点、数据集版本、工具调用、证据归档 sha256 |
| C6 | **失效可见**：四类 `stale_reason` 正确传播且不删除数据 |

### 3.4 质量

| # | 标准 |
| --- | --- |
| D1 | 后端定向回归全绿；既有基线失败项未恶化 |
| D2 | 前端定向单测通过 + production build 成功 |
| D3 | 未放宽或删除任何既有测试断言 |
| D4 | 容量边界全部返回明确错误，无静默截断 |
| D5 | 七个治理标志在 UI 全部可见 |

---

## 4. 不建议实施的重复建设（最终清单）

| # | 不要做 | 原因 | 替代 |
| --- | --- | --- | --- |
| 1 | 新 Agent Loop / 工作流引擎 | 违反 P2，且会与 `WorkflowRuntime` 装配冲突 | `AgentRuntime` + `ModelNativeAnalysisHarness` |
| 2 | 新拓扑排序 / 就绪计算 / 下游计算 | `DeterministicPlanDagStateMachine` 已是确定性实现 | 直接调用 |
| 3 | 新重试 / 租约 / 心跳 / 超时 | `NodeAttemptStore` 已含分布式租约与回收 | 直接复用 |
| 4 | 新检查点与失效算法 | `PlanStepCheckpoint` 的指纹链已覆盖 | 语义对齐复用 |
| 5 | 新证据模型 / 新血缘模型 / 新归档 | `EvidenceRecord` / `EvidenceLineage` / `EvidenceSnapshot` / `AnalysisEvidenceArchivePort` 完备 | 只增引用字段 |
| 6 | 新 Token 估算器 / 上下文压缩器 | `ContextTokenEstimator` / `SynthesisContextBudget` / `ContextCompressionEnvelope` 已有 | 需要时扩展既有类 |
| 7 | 新图表类型注册表 / 新图库 / 新渲染器 | 封闭清单 + 审计 + ECharts 已就位 | `visualization-capabilities.json` + `VisualizationRenderer.vue` |
| 8 | 新代码沙箱 / notebook 后端 | `PythonDockerRuntime` 安全基线更高 | 发布为 Python 模板资产 |
| 9 | 本地 SQL 引擎 / 会话内 DuckDB | 绕过授权与脱敏，形成第二数据面 | `QUERY_DATASET` + MCP 模板 |
| 10 | 新对象存储实现 / 引入 MinIO 作为前置 | 抽象已存在且本次无需 | `ArtifactBlobStore` |
| 11 | 新 SSE / 事件流端点 | `agentTaskEvents.js` + `/agent/tasks/{id}/events` 已有 | 复用 `runId` 订阅 |
| 12 | 新 SQL 编辑器 / 文件树 / DAG 画布 / 报告渲染 | 前端均已存在 | `SqlEditorPane` / `SqlProjectTree` / `PlanDagGraph` / `AnalyticalReport` |
| 13 | 引入 Pinia / Vue Router | 与既有范式冲突，造成双导航双状态 | `utils/*State.js` + hash 导航 |
| 14 | 新 RBAC / 新菜单表 / 新共享授权表 | `sys_*` + `ResourceAuthorizationService` + `resource_grants` 已完备 | 新增资源类型取值 |
| 15 | 新会话/任务状态机 | `AgentRunStatus` + `NodeAttemptStore.State` 已有两层 | 工作空间层只加 `STALE` 语义层 |
| 16 | 在 `KernelDataScope` 加字段 | 波及整个 Kernel 调用面 | 用 `attributes` |
| 17 | 把 Workspace 状态塞进 `InterpretationAnalysisSession` | 它是包私有、每次调用新建、不持久化 | PostgreSQL 事实源 |
| 18 | 复用 `workspace_registry` 存分析空间 | 语义完全不同（产品壳 vs 分析空间） | 独立表 |
| 19 | 用 RocksDB 作分析工作空间事实源 | 本地嵌入 + 保留期清理 | PostgreSQL |
| 20 | 引入 OpenSearch 索引分析证据 | 当前仅用于知识库/向量检索，与证据无关 | `EvidenceStorePort.query` |
| 21 | 迁移 Data Formulator Python 后端 | 任务已明确排除，且安全基线更低 | 现有 Python 模板链路 |
| 22 | 以 Cline SDK 为前置依赖 | 任务已明确排除 | — |

---

## 5. 阶段摘要

| 阶段 | 内容 | 主要新增 | 主要复用 | 阻断性验收 |
| --- | --- | --- | --- | --- |
| 0 | 基线修复 | — | — | 对象权限负例、基线全绿 |
| 1 | Workspace/Thread/Node 骨架 | 6 表 + 3 服务 | DAG 状态机、权限过滤器 | 9 条越权负例、schema diff 为空 |
| 2 | Dataset 与提升 | 2 服务方法组 | `DatasetHandle`、`QUERY_DATASET`、`ArtifactBlobStore`、`McpAuthorizationService` | 红线（无 SQL/连接串）、授权撤销不可读 |
| 3 | 节点执行映射 | 提交/登记/历史 | `AgentRuntime`、Evidence 全家、Token 预算 | 包级边界静态断言 |
| 4 | 分支/回溯/失效/重现 | 失效传播、重放、重现 | `ready` / `descendants`、指纹语义 | `STALE` 不删数据、DIVERGENT 如实 |
| 5 | 产物与可视化 | 引用层 | 图表清单与三项审计、`ReportComposer` | 未注册类型拒绝、TTL 回归 |
| 6 | 前端工作空间 | 8 组件 + 4 状态模块 | 9 既有组件、`agentTaskEvents` | 无 Pinia/Router、7 个治理标志可见 |
| 7 | 共享（可选） | — | `ResourceAuthorizationService` | 不新建授权表 |

---

## 6. 本轮交付边界说明

- 本轮按任务第六节要求**只交付设计与审查文档**，未修改任何生产代码，未执行构建、测试或部署。
- 文档中的 Java 签名、DDL、API 路径均为**待评审草案**，不代表已实现。
- 对 Microsoft Data Formulator 的机制对照基于其公开设计范式；本沙箱未拉取该仓库源码，故未逐行引用其实现，相关处已在审查文档 §6 标注。
- 审查文档中所有关于本仓库的结论均可按文中给出的文件路径、类名与表名核对。三处与任务书前提不一致的事实（MinIO 不存在、`workspace` 命名已被占用、Artifact 存在三套并行模型）已在审查文档 §0 与 §1.4 显式标注为需决策项，未做掩饰性表述。
