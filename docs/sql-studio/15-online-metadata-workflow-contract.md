# SQL Studio 元数据工作流与应用接口契约草案

本契约定义拟新增的元数据领域工作流和 SQL Studio 应用接口。现有 Agent 任务、MCP 传输、事件、确认与证据协议继续使用，不将下述 DTO 解释为已存在的 Runtime 核心接口。

## 工作流执行契约

工作流标识：`SQL_METADATA_DISCOVERY`。一次调用执行模型已选定的一项元数据操作；多次调用通过 discoveryId 关联。探索策略和终止条件由现有模型编排层根据用户意图、证据和预算判断，Runtime 只执行、治理与记录。

```text
SqlMetadataDiscoveryCommand
  contractVersion = sql-studio.metadata-workflow.v1
  discoveryId, invocationId, requestId
  operation = LIST_ASSETS | LIST_NAMESPACES | SEARCH | DESCRIBE | RELATIONS | CHANGES | REFRESH
  bindingRef?, modelArguments
  knownEvidenceRefs[], expectedSnapshotId?

TrustedExecutionContext                    // 不是模型参数
  KernelDataScope                         // tenant/user/request/run 等沿用现有契约
  authenticatedCallerRef
  projectOwnershipRef
  bindingRevision, currentAuthorizationRevision
  generationProfile = METADATA_ONLY
  budgets, cancellationContext

SqlMetadataDiscoveryResult
  contractVersion = sql-studio.metadata-workflow-result.v1
  discoveryId, invocationId, workflowId = SQL_METADATA_DISCOVERY
  outcome = SUCCEEDED | PARTIAL | DENIED | FAILED | CANCELLED
  metadataResultRef?, evidenceRefs[], snapshotRefs[]
  coverage, freshness, limitations[], recoverableError?
```

运行时遵循既有 `AbstractStagedExecutionWorkflow` 的校验、执行和组装边界。它可以拒绝无权限操作、预算外操作和不一致快照，但不能据此自主选择另一张表、另一数据源或自动执行 SQL。

MCP 发布绑定复用 `ToolWorkflowContract`，声明 DIRECT 角色，领域 workflowId 随真实工具结果 trace 返回。有需要的异步刷新才使用已有注册端口：

```java
workflowRuntime.register(
    "SQL_METADATA_DISCOVERY",
    SqlMetadataDiscoveryCommand.class,
    SqlMetadataDiscoveryResult.class,
    discoveryWorkflow::execute
);
```

上例是拟注册形状，不是修改 `AnalysisWorkflowType` 的要求。命令 DTO 不序列化口令、Spring Security 对象或活动线程上下文；异步恢复时根据可信身份引用重新获取当前授权。现有 `WorkflowStartRequest` 的 tenant 默认值不能用于本应用缺失身份时的兜底，SQL Studio 入口必须先拒绝缺失租户。

Local 执行不承诺跨进程恢复。需要恢复保证的异步操作使用已启用的持久化适配器；不能在设计中把“已有 Temporal 代码”当作“生产已配置 Temporal”。

## 发现会话生命周期

发现会话保存上下文而不占用一个永远 RUNNING 的任务：无活跃调用时为 READY，调用中为 DISCOVERING，可因新鲜度或缺少信息显示 NEEDS_REFRESH/NEEDS_CONTEXT，用户关闭后为 CLOSED。权限拒绝是调用结果，不能给会话永久授予旧权限。

原有 AgentTask 与 WorkflowExecutionStatus 保持原状态枚举，前端探索状态作为应用投影，不向全局任务枚举添加 SQL 专用状态。用户取消任务时调用现有取消机制；已完成的有效证据可保留，但已取消操作不得迟到写入当前 Generation 的覆盖声明。

续问使用同一 discoveryId 和当前仍可见的证据引用，可继续调用 MCP。新建会话产生新 discoveryId，保留工程及历史审计。重放相同 invocationId 在参数、绑定与快照相同的情况下幂等；同 ID 不同内容返回冲突。缓存命中也需要新授权检查和可审计的本次 invocation。

## SQL Studio 应用接口

根路径沿用 `/api/v1/data-science/sql`。以下为拟新增或扩展的应用接口，身份由已有认证取得，所有 projectId/discoveryId/generationId 均执行对象归属检查。

| 方法与路径 | 行为 | 授权边界 |
| --- | --- | --- |
| GET `/projects/{p}/metadata/assets` | 分页返回可绑定逻辑资产的安全投影 | 既有资产 RBAC/Scope；不代理管理员完整配置 |
| POST `/projects/{p}/metadata/contexts` | 创建发现会话和绑定，返回 discoveryId、binding refs、能力与预算 | 当前身份与工程归属；资产范围取授权交集 |
| GET `/projects/{p}/metadata/contexts/{d}` | 恢复发现会话、可见证据引用与快照状态 | 当前授权重新投影，旧证据不自动续权 |
| POST `/projects/{p}/metadata/contexts/{d}/operations` | 用户在 Schema 树触发浏览/刷新；调用同一 MCP/领域工作流 | 元数据读取或刷新权限；客户端不能提交执行 SQL |
| POST `/projects/{p}/generations` | 保留原接口，扩展 purpose、connectionMode、discoveryId、绑定和证据引用 | 生成权限与发现范围；不得从 body 取得执行权 |
| GET `/projects/{p}/generations/{g}` | 原结果新增引用、覆盖、新鲜度和校验投影 | 结果归属与当前资源可见性 |
| POST `/projects/{p}/generations/{g}/validations` | 对固定 SQL 内容和方言进行语法及 Schema 校验 | 元数据可见性；不运行生成 SQL |
| POST `/projects/{p}/sql-operations` | 创建 EXPLAIN 或只读查询操作，进入已有 Runtime 确认与执行体系 | 执行权限、本次确认、内容与绑定一致性 |
| GET `/projects/{p}/sql-operations/{o}` | 返回执行状态、结果和审计引用 | 操作归属与当前权限 |

Schema 树的人工操作与模型操作使用同一资源过滤逻辑，不新增一条绕过 Gateway 的后端 JDBC 路径。已有 task events、events/stream、cancel 等接口继续复用，应用接口只关联 task/run，不实现第二个调度器。

### 创建发现会话

```json
{
  "purpose": "QUERY",
  "bindings": [
    {"assetRef": "${authorizedAssetRef}", "role": "SOURCE", "namespaceRef": "${namespaceRef}"}
  ],
  "targetDialect": {"name": "${dialect}", "version": null}
}
```

purpose 为 QUERY 或 ETL；ETL 可有 SOURCE 和 TARGET。请求不得包含 tenantId、userId、roles、凭据或 approval=true。响应包含服务端的 discoveryId、bindingId/revision、工具能力投影、预算与新鲜度策略，不返回完整 ToolRuntime 内部配置。

### 扩展生成请求

```json
{
  "prompt": "${userRequirement}",
  "action": "generate",
  "purpose": "ETL",
  "connectionMode": "ONLINE_METADATA",
  "discoveryId": "${discoveryId}",
  "targetDialect": {"name": "spark", "version": null},
  "currentFile": {"fileId": "${fileId}", "version": 3, "contentSha256": "${sha256}"},
  "selection": null,
  "knownEvidenceRefs": ["${evidenceId}"],
  "mappingDocumentIds": [],
  "referenceIds": []
}
```

上例 spark 只是调用方选择，不是内置 SQL 模板或默认 ETL 策略。action 保留 generate/explain/fix/optimize；purpose 描述 Query/ETL 目标，connectionMode 描述离线或联机，三者不能合并成一个含混的 tab 值。

旧请求不带新字段时沿用离线生成。联机请求必须进入现有支持工具调用的模型编排路径；指定 modelName 不能退回无工具的 `llm_chat` 却显示“已联机”。应用选择现有授权 profile 或 Agent 配置，不创建一个通用 SQL Agent 服务。

本次选区和文本附件仍受现有长度与内容约束；代码是非可信上下文，不得通过编辑器内容修改绑定或扩大工具集合。任务请求由服务端保存并绑定上下文摘要；后续任务提交或恢复不能修改 metadata-only profile。普通管理员的广泛 RBAC 权限也不能绕过本轮“生成不执行”的操作限制。

### 联机输出包

```json
{
  "schemaVersion": "sql-studio.output.v2",
  "purpose": "QUERY",
  "files": [
    {
      "path": "${relativeSqlPath}",
      "content": "${modelGeneratedSql}",
      "dialect": "${confirmedDialect}",
      "evidenceRefs": ["${evidenceId}"]
    }
  ],
  "notes": "${explanation}",
  "assumptions": [
    {
      "id": "${assumptionId}",
      "kind": "INCREMENTAL_RULE",
      "statement": "${unconfirmedBusinessRule}",
      "status": "UNCONFIRMED",
      "affectedPaths": ["${relativeSqlPath}"]
    }
  ],
  "metadataCoverage": {"status": "PARTIAL", "evidenceRefs": ["${evidenceId}"], "missing": []}
}
```

输出仍须安全相对 .sql 路径、大小和文件数量约束。explain 必须 files=[]；模型引用未知、不可见或本发现会话之外的 evidenceId 时返回诊断，不能把其当成验证证据。模型不能声称执行完成、Schema 校验通过或业务假设已被用户确认；这些状态由服务端证据与人工确认记录产生。

原有 v1 输出继续兼容，validation 状态默认 NOT_RUN；联机 v2 的证据字段不由宽松字符串提取器“修复”成有效引用。

## 校验结果契约

```text
SqlValidationResult
  validationId, generationId, artifactContentSha256, targetDialect
  discoveryId, bindingRevision, snapshotRefs[], evidenceRefs[]
  syntaxStatus: PASSED | FAILED | UNSUPPORTED | UNAVAILABLE
  schemaStatus: PASSED | FAILED | INDETERMINATE | STALE | NOT_RUN
  diagnostics[]:
    code, severity, message, filePath, range?
    objectRef?, fieldRef?, evidenceRefs[], suggestedNextAction?
  validatedAt, executionStatus = NOT_RUN
```

`executionStatus=NOT_RUN` 为应用校验服务的结果，不是接受模型返回的常量。校验针对内容摘要，修改 SQL 后旧 validationId 不能继续显示为当前文件已通过。

不存在字段的检测依据授权且符合新鲜度要求的结构。缺失元数据、无权限、同名歧义与真实字段不存在分别返回诊断；可见替代字段作为候选交给模型决定，不自动按硬编码词表替换。别名、CTE、SELECT *、引用大小写、方言扩展与 UDF 的覆盖边界须经测试确认。

## EXPLAIN 与查询操作契约

```json
{
  "operation": "EXPLAIN",
  "discoveryId": "${discoveryId}",
  "bindingId": "${bindingId}",
  "artifactRef": {"fileId": "${fileId}", "version": 3},
  "contentSha256": "${sha256}",
  "parameters": {},
  "validationId": "${validationId}",
  "idempotencyKey": "${operationKey}"
}
```

服务端获取实际 SQL 内容并检查摘要；若支持未保存草稿，使用服务端签名的固定草稿快照，而不是只相信模型提供的 SQL 字符串。创建操作后返回 taskId、operationId 和现有 confirmation 状态/引用。用户确认沿现有 `/agent/tasks/runtime/tasks/{taskId}/confirm` 链路，不新建替代审批引擎。

确认绑定操作类型、SQL/参数摘要、资产、环境、Schema、方言和期限。用户提交后仍检查当前执行权限、资产绑定和文件内容；任一变化返回确认失效。无权限不是“等待确认”，同意按钮不能授予权限。

实际执行统一走已有 `sql_query_execute`，逻辑 executionContext 由可信绑定生成；客户端和模型不能指定 datasourceId/JDBC 目标。生成 profile 不获得该工具，执行只属于独立操作任务。ETL INSERT/MERGE/DDL 由现有只读策略拒绝，不以多语句或模板绕过。EXPLAIN ANALYZE 等具有执行语义的变体默认拒绝，支持范围要逐方言证明。

## 事件与审计

复用现有 AgentTask 事件查询/SSE 和 Runtime EvidenceStore；新增内容采用应用 payload，不更换事件运输协议：

```text
SqlMetadataObservation
  schemaVersion = sql-studio.metadata-observation.v1
  discoveryId, invocationId, taskId/runId, sequence
  phase = CALL_STARTED | CALL_COMPLETED | CACHE_USED | REFRESH_COMPLETED | CALL_DENIED
  toolRef, operation, visibleObjectRefs[], evidenceRefs[]
  snapshotRefs[], collectedAt?, freshness?, durationMs, outcome
```

模型决定选用哪个工具，但审计要记录实际运行的工具契约版本、绑定修订、授权结果、元数据版本和生成文件摘要。拒绝事件只记录可见请求与安全原因，不泄露隐藏 Schema。前端展示这些执行事实，不要求或保存模型隐藏思维链。

事件按 sequence 续传并去重；脱敏后的详细证据按引用读取。日志、事件和错误不得包含连接口令、签名令牌或未授权字段。权限变化后重新读取历史事件中的对象明细也须做可见性投影。
