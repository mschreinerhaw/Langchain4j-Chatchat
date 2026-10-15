# Runtime OS Coding Agent 集成架构基线 v1.0

状态：架构基线与公共契约设计冻结。日期：2026-10-15。

Runtime OS 通过供应商无关的 Coding Agent 能力，将授权数据分析扩展为程序生成、执行验证、错误诊断、代码修复和证据交付。整体组合为 Runtime OS + Coding Agent + Metadata MCP + Execution Sandbox + Evidence Governance。Cline SDK 是首个适配验证对象，供应商替换不得改变 Runtime 核心编排及公共契约。

本文冻结四个公共契约的语义、字段和调用边界。[A1 公共契约实现](runtime-os-coding-agent-a1-contracts.md) 已提供 Java 值类型、状态校验、Schema 和跨语言哈希测试；A2 控制面实现及剩余接入边界见下文。本基线不表示已有可运行的 Coding Agent 集成。

[A2 实现说明](runtime-os-coding-agent-a2-implementation.md) 与 [G0 验收报告](runtime-os-coding-agent-g0-acceptance.md) 记录任务控制面；[B 阶段实现](runtime-os-coding-agent-b-implementation.md) 与 [G1 验收报告](runtime-os-coding-agent-g1-acceptance.md) 记录真实 Docker 执行设施及验证边界。Coding Agent 适配仍按后续阶段门槛推进。

实施前的契约遗漏、必须补齐的嵌套类型和分阶段任务见 [公共契约实现审查与编码计划](runtime-os-coding-agent-contract-review-and-plan-v1.md)。该审查补充不改变双层循环、控制权和公共状态；新增 wire 字段在 Java/JSON 契约落地前逐项定型，已发布协议仍遵守本文的版本兼容规则。

## 1 职责与第一阶段范围

| 组成 | 所有权 |
| --- | --- |
| Runtime OS | Analysis Loop、任务目标、授权、累计预算、任务状态、恢复、证据使用与报告发布 |
| Coding Agent | Coding Loop 内的代码生成、测试、诊断、修复及结构化产物提交 |
| Metadata MCP | 授权范围内的数据结构、字段语义、指标口径、血缘及执行环境元数据 |
| Execution Sandbox | 进程、文件、输入输出、依赖、网络及资源隔离；提供真实执行回执 |
| Evidence Governance | 输入版本、代码内容、执行事实、结果与验证记录之间的关联 |

第一阶段只新增三个主要逻辑模块，不要求建立三个 Maven 模块：

1. `CodingAgentSPI`：公共契约与供应商无关调用边界，归属 `chatchat-common`。
2. `ClineCodingAdapter`：Java 适配层归属 `chatchat-integration`，Node 服务承载 SDK 生命周期、受控工具和事件转换。
3. `CodingEvidenceCollector`：编排与既有证据存储协作的归集组件；领域逻辑不依赖 Spring/JDBC，持久化适配复用现有实现。

Runtime 编排、MCP Gateway、Python Sandbox、Evidence Bundle、事件及检查点优先复用。Coding Agent 不得自行扩展数据授权、改变业务分析目标、发布报告或发布 Python 模板。

## 2 双层循环与执行控制

```text
Runtime Analysis Loop
  -> 决定是否提交编程任务
  -> 授权、固定输入和预算
  -> Coding Loop：生成 -> 执行 -> 诊断 -> 修复
  -> 收集执行结果和验证证据
  -> Analysis Loop 决定解释、后续分析及交付
```

所有 Coding Loop 尝试共享一个 Runtime 预算身份。模型调用、工具调用、代码运行、技术修复、适配层重试及恢复都累计计费，不得重置轮次、用量或截止时间。Runtime 在操作开始前原子预留额度，完成后结算；未知用量保留预留，不能按零费用释放。停止后仍需核对迟到的计费和执行事实。

取消链路必须贯穿 Runtime -> Adapter -> 模型请求及工具 -> Sandbox 进程/容器。只停止模型请求不能提交 `CANCELLED`。Sandbox 必须确认进程及其子进程已退出，关闭可继续写入的执行通道，再提交该终态。无法确认时保持原非终态，并记录取消请求及待核对原因。

临时工作区内的技术修复可以自主进行。缺失交易日的填充规则、重复记录的业务去重口径、金额精度与舍入规则、收益定义等，只能使用任务明确授权的规则；未给定规则时提交异常与局限，由 Analysis Loop 决定后续行动。

## 3 公共契约版本与通用规则

公共协议标识为 `runtime_os.coding.v1`。冻结的四个契约是 `CodingAgentSPI`、`CodingTaskContract`、`CodingTaskOutcome`、`CodingExecutionEvent`。下文表格中的嵌套对象属于这些契约，不引入供应商公共类型。

所有跨进程边界使用 JSON；时间为 UTC ISO 8601，时长为毫秒，计数为非负整数，金额和容差使用十进制字符串并明确币种或单位。缺失引用使用 `null`，集合使用空集合，不能使用空字符串冒充有效引用。v1 新增字段必须可选；必填字段、状态、单位或语义变更需要新主版本。消费者拒绝未知主版本与未知状态。

`KernelDataScope` 复用现有 Runtime 类型。任务必须绑定非空 tenantId、userId、runId；scope 由可信 Runtime 身份生成，不能采信模型或 Node 服务自报身份。每个读取、订阅、取消、恢复及引用解析操作都重新校验当前权限。公共报文不携带凭证、Cline sessionId、内部工具消息或供应商状态。

## 4 CodingAgentSPI

以下为原始调用语义。A1 已将订阅形式定型为具备事件、错误及关闭回调的 Observer/Subscription，具体签名以 [CodingAgentSPI](../chatchat-common/src/main/java/com/chatchat/common/runtime/coding/CodingAgentSPI.java) 为准：

```java
interface CodingAgentSPI {
    CodingTaskOutcome submit(CodingTaskContract task);
    CodingTaskOutcome get(KernelDataScope scope, String taskId);
    CodingTaskOutcome cancel(KernelDataScope scope, String taskId, String reason);
    CodingTaskOutcome resume(KernelDataScope scope, String taskId);
    AutoCloseable subscribe(KernelDataScope scope, String taskId,
        long afterSequence, Consumer<CodingExecutionEvent> listener);
}
```

`submit` 返回已持久化的任务快照，不等待整个 Coding Loop。`get` 返回最新已提交快照。`cancel` 返回已提交取消请求后的快照，可能仍为 CREATED/RUNNING；不将请求接受等同于进程停止。

`resume` 仅恢复同一非终态任务，沿用原授权上限、预算、截止时间与操作身份。恢复时权限收紧立即生效；扩权、更改目标或输入需要新任务。`subscribe` 从严格大于 afterSequence 的已提交事件开始回放，随后推送新事件；允许至少一次投递，按 eventId 去重。游标已超出保留范围时显式报告 `EVENT_CURSOR_EXPIRED`，不得伪装成完整历史。

Runtime 的接入门禁先检查请求版本、权限、输入和预算。同一 scope/taskId 与相同契约哈希的重复提交返回同一任务；哈希不同返回 `TASK_ID_CONFLICT`。未通过门禁的请求不创建已接受任务，通过供应商无关错误返回原因。已接受任务的错误通过 Outcome 表达。

## 5 CodingTaskContract

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| schemaVersion | 是 | `runtime_os.coding.v1` |
| taskId | 是 | Runtime 分配的稳定任务身份，也是提交幂等键 |
| scope | 是 | Runtime 身份、运行和数据分区 |
| objective | 是 | 不可自行修改的编程目标、交付项与验收条件 |
| inputSnapshotRef | 是 | 输入快照或明确声明可复现性限制的输入描述记录 |
| metadataRefs | 是 | 获准使用的元数据版本引用，可为空 |
| workspaceRef | 是 | Sandbox 分配的任务级工作区引用，不接受任意宿主路径 |
| authorization | 是 | 授权决策引用、允许工具/数据/文件范围、网络与副作用范围；默认拒绝未列明操作 |
| budget | 是 | 见下文；所有尝试共享 |
| environmentRef | 是 | 固定执行环境规范及依赖锁定策略引用 |
| validationPlanRef | 是 | 目标检查、业务规则版本、容差及异常处置权限 |
| outputContract | 是 | 必须提交的产物类别、Schema 与结果要求 |

budget 包含 budgetId、deadlineAt、maxModelCalls、maxModelTokens、maxToolCalls、maxCodeExecutions、maxCostAmount、costCurrency、maxCpuMillis、maxMemoryBytes、maxOutputBytes。额度由 Runtime 显式给出；零表示禁止，不表示无限。费用无法获得可靠计量或预留上界时，不能声称具备硬费用上限；实施前须配置可计量模型网关或拒绝该组合。

authorization 中的网络和文件权限由执行器强制执行；MCP 元数据发现不授予底层数据访问权限。依赖安装同样计入工具预算，并受网络和依赖来源策略约束。

## 6 CodingTaskOutcome 与状态机

```text
CREATED -> RUNNING -> COMPLETED | PARTIAL | FAILED | CANCELLED | BUDGET_EXCEEDED
CREATED -> FAILED | CANCELLED | BUDGET_EXCEEDED
```

终态不可回到 RUNNING。终态更新与完成事件使用现有持久化机制原子提交，并发完成、取消及预算耗尽以持久化 CAS 的结果为准。完成提交必须同时核验有效租约和停止请求：已接受的取消/预算停止阻止后续提交 COMPLETED；已经提交的完成结果不会因迟到取消被改写。任何终态均要求相关执行已停止或确认从未启动。终态后的新尝试必须创建关联的新任务。

| 状态 | 判定 |
| --- | --- |
| CREATED | 已接受，尚未启动执行 |
| RUNNING | Coding Loop 活跃，或执行结果/取消仍待核对 |
| COMPLETED | 编程交付项及要求的验证记录均按契约提交；业务检查允许得到失败结果 |
| PARTIAL | 正常结束但部分要求未完成，已有可用产物及明确缺口 |
| FAILED | 技术或契约失败；任务总截止时间耗尽使用 BUDGET_EXCEEDED |
| CANCELLED | 取消生效且执行器确认相关进程已停止 |
| BUDGET_EXCEEDED | 任一累计额度或总截止时间耗尽，且执行器确认活动执行已停止 |

单次工具超时可由 Coding Loop 在剩余预算内处理；耗尽任务 deadlineAt 使用 `BUDGET_EXCEEDED/DEADLINE_EXCEEDED`。达到预算或接受取消后立即禁止新操作，清理与用量核对使用受控运维资源，不能再启动模型或业务计算。

| 字段 | 语义 |
| --- | --- |
| schemaVersion、taskId、scope | 协议和身份，必须存在 |
| contractSha256 | 规范化任务合同指纹，排除任何供应商私有字段 |
| status、revision、lastEventSequence | 标准状态、单调递增快照版本和事件位置 |
| createdAt、updatedAt、startedAt、endedAt | 时间；未启动/未结束时间为 null |
| cancellationRequested | 取消请求是否已提交，不代表取消已完成 |
| reasonCode、summary、limitations | 标准原因、可展示摘要及限制 |
| inputSnapshotRef | 与任务输入一致 |
| codeArtifactRef | 实际执行代码的不可变 manifest，含路径、版本与内容哈希 |
| executionReceiptRef | 全部执行尝试的不可变回执 manifest |
| resultDatasetRef | 结果数据集或多个结果的目录引用 |
| validationRecordRef | 检查记录集合，含规则、期望值、实际值、容差及来源 |
| evidenceBundleRef | 归入既有 Runtime Evidence Bundle 的引用 |
| validationStatus | `PASSED/FAILED/INCONCLUSIVE/NOT_RUN`，独立于任务状态 |
| reproducibility | `FULL/CONDITIONAL/UNAVAILABLE`、限制与环境/输入依赖 |
| usage | Runtime 预算身份、累计计量、未结算预留及计量完整性 |

CREATED/RUNNING 可以只有输入引用。异常终态允许缺少未产生的产物，但必须列明原因，保留所有已提交产物。PoC 的 COMPLETED 必须具备全部六项引用；不存在结果时不能伪造空结果引用。PARTIAL/CANCELLED 等保留阶段 manifest，不把阶段输出冒充最终完整结果。

`COMPLETED + validationStatus=FAILED` 表示任务成功识别并记录了校验异常，不能转换为“计算正确”。发现并报告预设计算异常可以满足编程任务验收条件。

## 7 CodingExecutionEvent

| 字段 | 语义 |
| --- | --- |
| schemaVersion、eventId、taskId、scope | 版本、稳定事件身份和隔离范围 |
| sequence、revision、occurredAt | 任务内持久化序号、任务版本和时间 |
| type | 下文标准事件类型 |
| operationId、attempt | 实际操作身份及尝试序号；任务事件可为 null |
| status、reasonCode | 当前公共任务状态及标准原因 |
| evidenceRefs | 已提交的代码、回执、数据集、验证或 Bundle 引用 |
| usage | 累计用量快照；不在消费端累加重复投递 |
| summary | 经过脱敏的执行进展，不含供应商原始消息或模型私有工作笔记 |

标准 type：`TASK_CREATED`、`TASK_STARTED`、`CODE_COMMITTED`、`EXECUTION_STARTED`、`EXECUTION_FINISHED`、`VALIDATION_RECORDED`、`BUDGET_UPDATED`、`CANCELLATION_REQUESTED`、`RECOVERY_RECONCILED`、`TASK_FINISHED`。

事件必须对应已持久化事实。模型声称执行成功不能产生 EXECUTION_FINISHED。事件仅提供引用，原始数据、完整日志和代码通过现有授权资源通道读取。事件重放不得触发工具再次执行。供应商 sessionId 及原始事件保存在适配层受控映射中，不进入公共报文。

## 8 可复现性与证据提交

每次代码执行在启动前固定 inputSnapshotRef、codeArtifactRef、环境指纹及 operationId。执行回执记录镜像 digest、依赖锁、入口、参数、确定性配置/随机种子、时间、退出状态、资源计量、日志与输出哈希。镜像标签本身不足以保证环境可复现。

同一任务多次运行通过 operationId/attempt 区分；manifest 必须关联每次实际代码、输入、执行、结果与校验，禁止把最后一次代码配到之前的回执。结果登记必须提交真实行数、Schema、完整性和来源，不将模型预览当成完整结果。

不能完整快照时，输入描述至少保存授权来源、规范查询、参数、源版本或水位线、提取时间、记录数、可获得的内容指纹及缺失项。仅保存查询与水位线不能保证历史重放；无法重新读取相同版本时标记 CONDITIONAL 或 UNAVAILABLE，并说明限制。

先保存内容及哈希，后通过现有证据事务或可恢复提交过程发布 manifest 和 Bundle 引用；公开引用只能指向可授权解析的已提交记录。失败时保留暂存身份供恢复，不能提前发布无内容引用。同一派生结果按任务、操作及内容指纹幂等登记。

计算检查和业务规则结果分别记录。业务规则来自 validationPlanRef 的版本化定义；不能由同一个模型自行改写预期值以通过检查。PoC 使用独立确定性验算器/固定期望值判定注入异常是否被发现。

## 9 恢复与结果未知

复用 RuntimeExecutionCheckpointPort 的 CAS 记录操作领取、预算预留、执行句柄及回执提交。进程重启后先核对 Sandbox 状态和已提交证据，再决定恢复 Coding Loop。

已领取但无回执不等于未执行。能够定位执行句柄时查询或终止该进程；无法确认时记录 `EXECUTION_OUTCOME_UNKNOWN`，禁止自动重放可能有副作用的操作。可保持 RUNNING 等待核对；确认没有活动进程但无法恢复结果时以 FAILED 结束并记录局限。未知结果使用 reasonCode 表达，不新增供应商专有任务状态。

任何重试均使用相同预算身份；同一执行意图不得被 SDK、MCP 和 Temporal 多层独立重试。非幂等执行关闭自动重试，幂等只读操作也必须具备稳定 operationId 与预算预留。

## 10 当前源码适配审查

以下路径均相对仓库根目录 `Langchain4j-Chatchat`。

| 适配点 | 当前源码事实 | v1 实施要求 |
| --- | --- | --- |
| `chatchat-agents/.../analysis/graph/ModelNativeAnalysisHarness.java` | CALL_TOOL 分支明确返回 TEMPLATE_SCOPE_LIMIT | 增加受控 Coding 任务操作；仍限制于已交付授权数据，保留原资产发现边界 |
| `chatchat-agents/.../analysis/graph/DataWorkspaceOperations.java` | CATALOG、QUERY_DATASET、BATCH_MODEL_INFERENCE；衍生结果已有句柄与 lineage | 复用结果登记模式；代码输入导出和代码结果导入需要显式桥接 |
| `chatchat-agents/.../analysis/graph/HarnessToolAccess.java` | 仅接纳 read/DIRECT 工具；已有领取和回执恢复逻辑 | 不将代码执行伪装成 read；复用执行身份和恢复原则，单独授权副作用 |
| `chatchat-common/.../runtime/agent/AgentComputeRuntimePort.java` 与 `AgentGatewayPort.java` | 已有通用 Agent 执行与 Gateway 扩展接口 | 可经内部桥接复用，不向 Coding 公共协议泄漏其状态 |
| `chatchat-common/.../runtime/agent/AgentExecutionOutcome.java` 与 `chatchat-agents/.../federation/DefaultAgentComputeRuntime.java` | Outcome 为 claims/artifacts；通用计算路径有 OutcomeVerifier 及候选失败回退 | 不为代码任务伪造 Claim；审查 verifier、状态映射和副作用后回退，避免再次执行 |
| `chatchat-common/.../runtime/evidence/RuntimeExecutionCheckpointPort.java` 与 `chatchat-api/.../runtime/JdbcRuntimeEvidenceStore.java` | 共享 CAS 检查点和持久化证据 | 扩展 coding 命名空间与引用，复用持久化设施；补齐预算账本语义 |
| `chatchat-api/.../datascience/python/PythonDataScienceService.java` | testScript 实际调用 McpPythonControlPlanePort.preview；publish 为独立发布流程 | Sandbox 主接入点沿 MCP 控制面；Coding 交付不自动调用 publish |
| `chatchat-mcp-server/.../python/PythonDockerRuntime.java` | 每次执行临时容器；只读挂载、受限用户和资源；输出为临时 tmpfs；finally 清理容器 | 增加任务/操作级隔离、取消句柄、可靠进程停止、输出导出及回执；必须在容器清理前保全产物 |
| `chatchat-api/.../datascience/python/PythonRuntimeService.java` | 仍存在按资产复用容器的本地实现 | 不误认为 testScript 的实际执行路径，不另建第二套 Coding Sandbox |
| `chatchat-api/.../runtime/AgentRuntimeEventStreamService.java` | 已有任务事件流基础设施 | 映射公共 Coding 事件并保证持久化序号、去重和游标失效语义 |

当前 PythonDockerRuntime.execute 接口没有任务级取消参数或公开执行句柄，现有容器 finally 清理不能替代端到端取消契约。临时输出目录也需要新增保全通道。以上属于实施缺口，不能因已有 Docker 支持就判定 PoC 的取消及证据要求已满足。

## 11 PoC 验收与故障注入

给定已授权金融数据及版本化验证目标，Coding Agent 必须生成程序、执行并修复技术错误，发现预设计算异常，提交可追溯结果与验证记录；Runtime OS 控制结果使用和发布。模型、输入及验证目标固定，验算器与注入答案不暴露给 Coding Agent。

| 场景 | 预期行为与验收证据 |
| --- | --- |
| 正常数据 | 六项引用可解析，结果与独立基准在声明容差内一致 |
| 字段类型不匹配 | 收集原始错误，按授权规则转换或报告阻塞，保留失败与修复后的执行记录 |
| 缺失交易日 | 依据授权交易日历识别缺口；未授权时不自动填充或宣称完整 |
| 重复记录 | 按声明业务键识别重复；去重须有明确规则，原始数据不被覆盖 |
| 金额精度误差 | 精确十进制/声明舍入规则验算，记录差额、单位和容差 |
| 收益对账不一致 | 识别公式、现金流、时间范围或复权口径差异，不能修改基准掩盖差异 |
| 执行中取消 | 模型及实际进程停止；确认前不能宣称 CANCELLED；已提交产物保留 |
| 预算耗尽 | 拒绝新操作，停止活动执行；重试及恢复不重置累计预算 |
| 进程重启与网络断开 | 已提交结果复用，未知副作用不自动重放，操作关联和预算保持一致 |
| 重复提交与事件重放 | 相同合同幂等，不同合同冲突，事件回放不触发计算 |
| 跨租户与越权访问 | 元数据、输入、产物、事件、取消与恢复均拒绝越权 |

报告任务完成率、异常检出情况、误报、人工介入、累计费用、耗时和重复执行情况。COMPLETED 数量不能替代异常检出或业务正确性指标。首轮逐项检查固定注入案例，之后再扩展统计样本。

## 12 开发实施顺序

1. 按本基线实现 common 中四项公共契约，并验证序列化、版本拒绝、状态转换、幂等冲突和隔离。
2. 补齐 MCP Python 执行句柄、任务级工作区、取消传播、累计预算与产物保全，先用固定脚本验证。
3. 实现 CodingEvidenceCollector，贯通六项引用、逐次执行关联及恢复核对。
4. 实现 ClineCodingAdapter，只暴露 Runtime 授权工具，显式控制 SDK 重试，私有存储供应商映射。
5. 给 Analysis Loop 增加受控任务提交、观察与结果消费能力，执行金融故障注入验收。

基线变更必须记录协议兼容性及迁移影响。Cline SDK 的具体版本和 API 在适配实现阶段锁定并验证，不作为本公共协议的依赖。
