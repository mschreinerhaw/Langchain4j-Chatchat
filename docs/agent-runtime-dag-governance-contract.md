# Agent Runtime DAG 审核与自动修复规范

## 1. 契约身份

- 契约名称：Agent Runtime DAG Governance and Deterministic Repair Contract
- 契约版本：`runtime_dag_governance.v1`
- 适用组件：DAG Planner、`AgentWorkflowDecisionEngine`、Runtime Plan Auditor、Tool Router、Agent Task 状态聚合器、MCP 发布与调用适配器
- 核心目标：`RUN`。只要仍存在符合用户流程、权限边界且可执行的路径，Runtime 就必须继续执行并交付已有结果，不得因模型漂移或单个节点失败提前终止整个任务。

本规范中的“必须”“不得”“应”属于 Runtime 强制契约，不是 Prompt 建议。

## 2. 唯一业务判定标准

在已授权、可用工具和平台安全边界内，**用户提交并由任务快照固化的流程定义是唯一业务判定标准**。

判定优先级如下：

```text
用户流程快照
  > Runtime 确定性协议与 Schema
  > 当前请求 availableTools / 权限 / 风险确认边界
  > MCP 当前发现结果与执行证据
  > 模型生成的计划、判断和自然语言
```

因此：

1. 模型输出只是候选解释、参数提议或总结，不是 DAG 事实来源。
2. 模型不得新增、删除、改名、重排用户流程节点，也不得改变必选依赖、并行关系、失败策略或确认级别。
3. 模型输出与用户流程冲突时，Runtime 必须按用户流程修复；不得要求模型自行纠正后才能继续。
4. 权限、租户隔离、风险确认、Schema 和本次 `availableTools` 是平台执行边界。它们不能被用户流程或模型绕过，但也不得被解释为新的业务流程标准。
5. 用户流程未定义的业务语义不得由工具名、模型偏好、历史任务或部署环境推断补造。

## 3. 职责边界

| 组件 | 必须负责 | 不得负责 |
| --- | --- | --- |
| 用户流程快照 | 固化节点、依赖、条件、并行关系、必选性、失败策略和确认策略 | 在任务执行中被模型输出覆盖 |
| 模型 | 意图理解、候选评定、语义参数提议、证据总结 | 决定真实拓扑、发明工具、绕过依赖、直接判定任务终态 |
| DAG Planner | 把用户流程投影成候选执行计划 | 把模型生成顺序当作最终顺序 |
| `AgentWorkflowDecisionEngine` | 依据流程快照审核候选计划，输出结构化差异和修复决策 | 依赖自然语言解释决定是否放行 |
| Runtime Plan Auditor | 规范化、静态校验、确定性修复、状态转换和预算控制 | 针对某个模型、问题、模板或部署工具名写特判 |
| Tool Router | 将稳定 capability 解析到本次可见的真实工具 | 让模型自由生成或猜测工具名 |
| MCP | 按授权和 Schema 确定性发现、校验和执行 | 修改用户 DAG 或将单次工具失败升级为任务失败 |
| Task Aggregator | 聚合节点证据并生成任务级终态 | 把任意中间事件的 `FAILED` 状态直接当作任务终态 |

## 4. 标准 DAG 表达

审核输入必须先归一化为稳定结构，至少包含：

```json
{
  "schemaVersion": "runtime_dag_governance.v1",
  "workflowSnapshotId": "workflow-snapshot-001",
  "nodes": [
    {
      "nodeId": "template_execution",
      "capability": "template_execute",
      "assetType": "api_service",
      "required": true,
      "dependsOn": ["template_discovery"],
      "optionalDependsOn": [],
      "parallelWith": [],
      "condition": "用户流程中声明的结构化条件",
      "confirmation": "none",
      "failurePolicy": "continue_independent_branches"
    }
  ]
}
```

强制约束：

- `nodeId` 在一个流程快照内稳定且唯一。
- 依赖只引用 `nodeId`，不得引用模型生成的数组位置。
- 工具绑定使用稳定 `capability + assetType`，真实工具名由 Router 根据当前注册表解析。
- 条件必须来自用户流程的结构化定义；自然语言条件只能由确定性解析器转换，解析失败时保留原条件并产生诊断，不得猜测。
- 审核和执行都必须引用同一个 `workflowSnapshotId`，运行中配置变化只影响新任务。

## 5. 审核流水线

每次执行必须按以下顺序完成：

```text
流程快照
  -> 结构归一化
  -> 静态拓扑审核
  -> 模型候选计划对账
  -> 确定性漂移修复
  -> availableTools / 权限 / Schema / 确认门控
  -> 就绪节点调度
  -> 节点结果与证据落库
  -> 局部恢复或继续独立分支
  -> 任务级结果聚合
```

### 5.1 静态拓扑审核

执行前必须检查：

- 节点 ID 唯一；
- 所有必选依赖存在；
- 不存在自依赖和环；
- 并行节点之间没有未声明的数据依赖；
- 必选节点具有可解析 capability；
- 高风险或写操作的确认策略没有被降级；
- 所有工具均在本次 `availableTools` 中并通过租户、权限和资产类型校验；
- 参数绑定能追溯到用户输入、流程常量、模板默认值或成功工具证据。

### 5.2 模型候选计划对账

模型计划必须逐项投影回用户流程快照：

- 可识别且合法的节点提议被接受；
- 节点别名只通过注册表中的显式别名映射归一化；
- 模型发明的节点、依赖、工具和模板被删除并记录审计；
- 模型遗漏的用户必选节点由 Runtime 从快照恢复；
- 模型顺序与 DAG 依赖冲突时按拓扑顺序重排；
- 模型把可并行节点串行化时，Runtime 可恢复用户定义的并行关系；
- 模型输出为空、非法 JSON、截断或拒绝规划时，Runtime 仍从用户流程快照构建可执行计划。

## 6. 节点漂移分类与确定性修复

| 漂移类型 | 示例 | Runtime 修复 |
| --- | --- | --- |
| 节点遗漏 | 模型漏掉必选模板发现节点 | 从用户流程快照恢复节点及依赖 |
| 节点增生 | 模型增加未定义的搜索或执行节点 | 删除节点并记录 `UNDECLARED_NODE_REMOVED` |
| 节点改名 | 模型输出近义词或供应商特有名称 | 仅按显式注册别名映射；无映射则回退流程节点 |
| 顺序漂移 | 执行节点排到发现节点之前 | 按 DAG 拓扑稳定排序 |
| 依赖漂移 | 必选依赖被删除或改成可选 | 恢复快照中的依赖强度 |
| 并行漂移 | 独立分支被错误互相依赖 | 恢复 `parallelWith` 和原依赖集合 |
| 工具漂移 | 模型发明工具名或选择错误资产类型 | 按 capability、assetType 和注册表重新路由 |
| 参数漂移 | 字段别名、类型、包装层或默认值错误 | 按 Tool Schema 和参数来源协议编译 |
| 输出格式漂移 | Fence、前置文本、字段缺失、大小写变化 | 宽容解析后投影到规范 Schema；不改变业务含义 |
| 状态漂移 | 中间 `TOOL_RESULT/FAILED` 被当成任务失败 | 按事件类型重算任务状态 |

修复不得依赖再次调用模型。模型可以提供新的语义候选，但 Runtime 必须能够在模型完全不可用时完成所有结构性修复。

### 6.1 修复算法

```text
canonical = normalize(userWorkflowSnapshot)
candidate = tolerantParse(modelOutput)
projected = intersect(candidate, canonical.nodes)
projected = restoreRequiredNodes(projected, canonical)
projected = restoreEdgesAndPolicies(projected, canonical)
projected = resolveCapabilities(projected, availableTools, registry)
projected = compileArguments(projected, schemas, evidence)
audited = validateAcyclicAndAuthorized(projected)
schedule(all ready nodes from audited)
```

相同的流程快照、工具目录、Schema、权限快照和证据输入必须产生相同的修复结果。若不能满足该性质，该修复不得进入 Runtime。

## 7. 执行与恢复语义

### 7.1 节点失败不等于任务失败

事件必须分成两级：

- 节点级事件：`TOOL_CALL`、`TOOL_RESULT`、`STEP_RESULT`。
- 任务级事件：`RESULT`、`ANSWER`、`COMPLETE`、`ERROR`、`RUNTIME_FAILED`、`RUNTIME_CANCELLED` 和任务 `STATUS`。

只有任务级事件可以终止任务轮询。任何节点级事件，即使其 `status=FAILED`，也只能改变该节点及其依赖分支的状态，不能直接终止任务。

### 7.2 失败传播

节点失败后 Runtime 必须：

1. 保存结构化错误、输入摘要、尝试次数和关联证据；
2. 根据用户流程的依赖边阻断真正依赖该输出且无法满足输入的节点；
3. 继续执行不依赖该失败节点的就绪分支；
4. 若存在安全且实质变化的确定性修复，按预算重试；
5. 汇总已有成功结果，并在最终回答中明确披露失败节点及影响范围。

禁止因为一个工具出现网络、超时、业务错误或空结果而取消所有独立分支。

### 7.3 自动恢复边界

允许自动恢复：

- 根据 Schema 修正字段别名、大小写、标量/单元素容器和允许的类型转换；
- 使用模板声明的默认值；
- 从已验证证据重新绑定参数；
- 对瞬时错误执行有界、带退避且幂等的重试；
- 在用户流程允许的候选内重选模板或路由工具；
- 恢复遗漏节点、依赖和拓扑顺序；
- 跳过已失败分支并继续独立分支。

禁止自动恢复：

- 猜测缺失业务参数、租户、资产、凭据或用户意图；
- 扩大用户流程、权限或资产范围；
- 把写操作降级为无需确认；
- 无变化无限重试；
- 使用失败或未提交结果作为成功证据；
- 为使任务显示成功而隐藏失败事实。

无法安全修复的节点必须产生清晰诊断；这不妨碍其他分支继续运行。

## 8. 任务级结果聚合

聚合只依据已提交的节点事实，不依据模型对“成功/失败”的自然语言判断。

| 条件 | 任务状态 | 行为 |
| --- | --- | --- |
| 所有命中的必选节点成功并形成结果 | `SUCCESS` | 正常交付 |
| 至少有一个可交付结果，同时存在工具失败、被阻断分支或证据缺口 | `PARTIAL_SUCCESS` | 交付已有结果并说明失败与影响 |
| 没有最终答案，但存在可展示的工具结果、来源或观察 | `PARTIAL` | 展示证据和未完成原因 |
| 流程合法执行但没有可展示结果 | `NO_PRESENTABLE_RESULT` | 说明未获得结果，不伪造答案 |
| 没有任何可交付证据，且所有满足用户目标的路径均被致命错误阻断 | `FAILED` | 任务级失败并给出可操作诊断 |
| 用户取消、拒绝确认、确认超时或 Runtime 主动终止 | 对应任务终态 | 保留已提交证据和终止原因 |

“可交付结果”包括最终回答、成功来源、成功或失败的结构化工具轨迹、已提交观察和其他可展示证据。失败轨迹本身是诊断证据，但不得被用来证明业务结论成功。

最终输出在 `PARTIAL_SUCCESS` 或 `PARTIAL` 时必须包含：

- 已完成内容；
- 失败或未完成节点；
- 对用户目标的实际影响；
- 已执行的自动修复；
- 如仍需用户输入，明确指出最小缺失信息。

## 9. 重试、幂等与预算

- 重试必须由错误分类、节点策略和预算共同决定，不由模型一句“再试一次”触发。
- 相同工具、相同参数、相同执行上下文的无变化重试默认禁止；仅明确的瞬时错误策略可例外。
- 有副作用调用必须携带稳定幂等键，任务恢复或事件重放不得重复副作用。
- 每个节点记录 `attempt`、`repairAction`、`inputHash`、`idempotencyKey` 和 `retryReason`。
- 达到节点重试预算只终止该节点；是否影响任务由 DAG 依赖和结果聚合规则决定。
- 达到任务时间或模型预算时停止新增工作，但仍必须聚合并交付已提交结果。

## 10. MCP 发布命名规范

MCP 发布名称必须与 API 流程审核机制使用的规范工具名一致。

### 10.1 规范名称

基础名称采用：

```text
{domain}_{capability}_{action}
```

要求：

- 只使用小写 ASCII 字母、数字和下划线；
- 名称表达稳定能力，不包含租户、环境、主机、模型、版本时间戳或部署实例；
- API 注册表、DAG 节点绑定、权限 Scope、审计记录和 MCP Tool Schema 的 `canonicalToolName` 必须完全一致；
- 发布前必须检查名称唯一性、Schema 兼容性及 capability/assetType 一致性；
- 工具重命名属于契约变更，必须提供显式别名、迁移期和兼容测试，不能靠 Runtime 字符串猜测。

示例：

```text
api_asset_query
api_template_query
api_template_execute
sql_metadata_search
database_query_template_query
linux_command_execute
```

### 10.2 传输限定名

MCP Client 为避免多 Server 冲突可以显示传输限定名，例如：

```text
mcp_chatchat_mcp_server_api_template_execute
```

但该名称只是传输标识。注册表必须显式保存映射：

```json
{
  "canonicalToolName": "api_template_execute",
  "publishedToolName": "api_template_execute",
  "transportQualifiedName": "mcp_chatchat_mcp_server_api_template_execute",
  "capability": "template_execute",
  "assetType": "api_service"
}
```

DAG 审核、权限和结果聚合一律使用 `canonicalToolName`；调用适配器只在最后一跳使用 `transportQualifiedName`。禁止在 Runtime 中通过删除前缀、模糊匹配或针对某个 Server 写字符串分支来推导规范名称。

## 11. 审计输出

每次审核至少记录：

```text
traceId, taskId, runId, workflowSnapshotId,
governanceContractVersion, modelId, promptVersion,
originalPlanHash, repairedPlanHash,
driftTypes, repairActions,
canonicalToolName, transportQualifiedName,
nodeStatus, taskStatus, evidenceIds,
attempt, latencyMs, decisionReason
```

修复过程必须以结构化 Runtime observation 实时发布，不能只写日志文本：

```json
{
  "eventKind": "DAG_REPAIR",
  "eventState": "STARTED | APPLIED | REJECTED",
  "repairAttempt": 1,
  "fromIteration": 1,
  "toIteration": 2,
  "reason": "结构化触发原因",
  "failedStepId": 1,
  "failedToolName": "规范工具名",
  "changeCount": 4,
  "changes": [],
  "validationIssues": []
}
```

前端必须按 `eventKind/eventState` 展示“检测到问题、修复中、已修复、修复未通过”，不得通过匹配中英文日志文本推断状态。修复事件属于执行链路事件：它可以显示告警或修复状态，但不能直接把整个任务标记为失败。

审核记录必须能回答：

1. 用户原始流程是什么；
2. 模型产生了什么漂移；
3. Runtime 做了哪些确定性修复；
4. 哪些节点成功、失败、被阻断或跳过；
5. 为什么任务最终是 `SUCCESS`、`PARTIAL_SUCCESS`、`PARTIAL` 或 `FAILED`。

## 12. 极端 E2E 验收矩阵

以下场景必须从真实用户请求入口执行，并回读任务 timeline、节点证据、最终回答和持久化状态：

| 场景 | 注入条件 | 必须结果 |
| --- | --- | --- |
| 空模型计划 | 模型返回空文本或空节点 | Runtime 从用户流程快照恢复 DAG 并运行 |
| 非法模型输出 | 前置文本、Fence、截断 JSON、未知字段 | 可解析部分被投影；结构由 Runtime 修复 |
| 全量节点改名 | 模型使用近义名或供应商限定名 | 仅显式别名生效，其余恢复规范节点 |
| 逆序计划 | 执行先于发现、依赖顺序颠倒 | 按 DAG 拓扑重排 |
| 节点增删 | 模型删除必选节点并加入未声明工具 | 恢复必选节点、删除增生节点 |
| 单工具失败 | 多分支中一个工具返回 `UNAVAILABLE` | 独立分支继续；有结果时任务为 `PARTIAL_SUCCESS` |
| 首个事件失败、后续成功 | `TOOL_RESULT/FAILED` 先于 `RESULT/PARTIAL_SUCCESS` | 轮询不提前终止，最终选择任务级结果 |
| 连续多工具失败 | 部分分支失败、部分分支成功 | 不丢成功证据，最终披露每个失败影响 |
| 全工具失败且无证据 | 所有目标路径不可恢复 | 唯一任务终态为 `FAILED`，无伪造回答 |
| 模型服务不可用 | Planner/Reviewer 超时或拒绝 | 结构性修复不依赖模型；能执行的确定性节点继续 |
| MCP 名称加前缀 | Client 暴露传输限定名 | 审核仍使用注册表中的规范名称，无字符串猜测 |
| Schema 演进 | 参数改名、类型改变、增加必填项 | 兼容映射生效或明确阻断当前节点，不误路由 |
| 恢复与乱序 | 重启、事件重复、终态事件乱序 | 副作用不重复、证据不丢、任务终态唯一 |
| 高并发混合故障 | 并行节点超时、重试、取消交错 | 无死锁、无全局误取消、状态可追溯 |

硬断言：

- 中间 `TOOL_RESULT/FAILED` 不得成为任务轮询终点；
- `stopOnFailure=false` 时，一个节点失败不得阻止独立节点启动；
- 有任何可交付结果且存在局部失败时，不得返回任务级 `FAILED`；
- 结构修复结果不随模型供应商或自然语言输出风格变化；
- Runtime 生产代码中不得出现部署限定 MCP 名称、样例工具名或业务问题特判；
- 最终回答必须忠实披露局部失败，不得以“成功”隐藏证据缺口。

## 13. 发布门禁

以下任一情况必须阻断发布：

- 用户流程被模型输出改变且 Runtime 未修复；
- 单个节点失败导致独立分支或整个任务被错误终止；
- 有可交付结果却返回任务级 `FAILED`；
- DAG 审核依赖模型二次输出才能完成结构修复；
- MCP 发布名称与 API 规范名称无显式映射或语义不一致；
- 生产代码包含具体部署、工具、模板、租户或自然语言问题的硬编码分支；
- 无法从审计记录重建原计划、漂移、修复和最终状态原因；
- 极端 E2E 矩阵中的硬断言失败。

本规范与模板评定、参数绑定、MCP 工作流和发布测试规范共同生效；发生冲突时，权限与安全边界优先，业务拓扑和完成标准以用户流程快照为准。

## 14. 已准入模板的强制执行完成性

当受治理的模板发现节点已返回模板集合，且该集合通过当前用户流程、租户、权限、资产范围和 Schema 边界后，集合中的每个模板都是本轮的执行必选项。

强制规则：

- DAG 中的 `templates[0]` 标量绑定只是模型输出的传输形式，不能裁剪已准入模板集合。
- Runtime 必须将两个及以上已准入模板自动编译为 `stopOnFailure=false` 的隔离批次，不依赖 Planner 或 Reviewer 声明批量结构。
- 每个已准入模板必须获得 `SUCCESS`、`FAILED` 或安全边界导致的 `BLOCKED` 终态记录；错误结果是可审计证据，不得导致其余模板被跳过。
- 在全部已准入模板获得终态记录之前，`final_answer` 不得进入可就绪状态。
- 模型对局部结果的“已满足”、“不相关”或“证据足够”判断不具有提前终止权。
- 若任一已准入模板因缺少必填参数、元数据或可用执行器而无法安全调用，Runtime 必须为该模板生成带明确错误码与原因的 `BLOCKED` 终态，并继续执行其余模板；禁止以批次预检失败为由阻断整个 DAG，也禁止静默删除该模板。
- 预检修复必须发布结构化 `DAG_REPAIR/APPLIED` 事件，修复码为 `TEMPLATE_BATCH_TERMINAL_COVERAGE_APPLIED`，至少包含模板 ID、`BLOCKED` 状态、错误码、原因及 `remainingCallsContinued=true`，供前端展示完整链路。
- 已准入数量、批次调用数量和终态结果数量必须一致；远程调用数量允许小于已准入数量，但差值必须全部由可审计的 `BLOCKED` 结果解释。

该契约的完成判定只依赖 MCP 发现输出、已发布 Schema、用户流程快照和实际工具终态，不依赖模型是否正确生成 `selected_template_ids`、`calls` 或批量绑定。

## 15. Agent 环境与截断摘要的契约优先级

- Agent 配置中的运行环境是本次 MCP 路由的权威上下文。当 Agent 已设置 `env`，Planner 产生的 `asset.environment` 或 `filters.env` 边契约必须从该上下文确定性恢复，不得因工具预览中未重复环境字段而失败。
- `tool_result_summary.v1.summaryTruncated=true` 只表示内联 `preview` 被缩短，不表示原始结果缺少字段。
- 边契约解析顺序为：完整输出或 `routingProjection` → 已解析的工具输入 → Agent 运行环境 → 用户流程快照中的显式环境。
- 使用 Agent 环境恢复契约时，Runtime 必须发布 `DAG_REPAIR/APPLIED` 事件，修复码为 `AGENT_ENVIRONMENT_CONTEXT_APPLIED`。
- 最终汇总不得从被截断的预览推断“字段缺失”；只有 Runtime 在完成上述恢复后仍输出的权威契约违例才能作为失败事实。
