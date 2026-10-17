# Runtime OS Coding Agent A2 实现与数据库说明

日期：2026-10-15。范围：任务持久化、执行所有权、Lease/Fencing、累计预算和状态恢复。验收证据见 [G0 验收报告](runtime-os-coding-agent-g0-acceptance.md)。

A2 使用现有 RuntimeExecutionCheckpointPort 和 JdbcRuntimeEvidenceStore 保存 Coding 专用控制状态。submit 在数据库事务提交后返回 CREATED 快照，不等待编程任务执行。A2 不连接 Sandbox、MCP 异步执行或模型，不生成真实执行停止证明。

## 1 源码审查与实际复用

| 实际源码 | 已核对能力 | A2 的复用方式 |
| --- | --- | --- |
| [RuntimeExecutionCheckpointPort](../chatchat-common/src/main/java/com/chatchat/common/runtime/evidence/RuntimeExecutionCheckpointPort.java) | 原接口提供按作用域读取及字符串 CAS，没有多步骤原子事务回调 | 增加可选的事务内纯 mutation；不支持的适配器显式拒绝，不能用未加锁读取模拟 |
| [JdbcRuntimeEvidenceStore](../chatchat-api/src/main/java/com/chatchat/api/runtime/evidence/JdbcRuntimeEvidenceStore.java) | REQUIRES_NEW 短事务、所属分区 SELECT FOR UPDATE、完整性哈希、分块检查点和安全分块清理 | mutation 在相同锁/事务中读改写；更新 SQL 校验原 checkpoint revision，保留原 CAS 行为 |
| [DefaultAgentRuntime](../chatchat-agents/src/main/java/com/chatchat/agents/runtime/execution/DefaultAgentRuntime.java) | submit 先写 AgentRunStore，再启动 agent-run-v1 Workflow；该工作流处理既有 AgentRunRequest | 不把 Coding DTO 冒充分析请求，不启动已有 LLM 业务循环；复用 Runtime 执行设施及独立 Coding 检查点命名空间 |
| [ResumableAgentRunExecutor](../chatchat-agents/src/main/java/com/chatchat/agents/runtime/plan/execution/ResumableAgentRunExecutor.java) | 已有恢复接口针对 InterpretationPlan 挂起/继续 | Coding 使用持久快照恢复，不调用 planner；不会将该接口误描述为已能恢复任意代码进程 |
| [AgentRuntimeTaskScheduler](../chatchat-chat/src/main/java/com/chatchat/chat/task/queue/AgentRuntimeTaskScheduler.java) | 扫描既有 Agent 任务表，提供数据库队列与 claim 恢复 | 不创建第二套通用队列；A2 提供 claim/resume 入口，现有上层调度后续可携带 scope/taskId 调用，当前不自动扫描全库 Coding 任务 |
| [AgentRuntimeExecutorConfig](../chatchat-agents/src/main/java/com/chatchat/agents/runtime/config/AgentRuntimeExecutorConfig.java) | 已有有界共享 Executor | 事件订阅复用它及应用 TaskScheduler，不新建生产线程池或执行引擎 |
| [KernelDataScope](../chatchat-common/src/main/java/com/chatchat/common/kernel/KernelDataScope.java) | 身份及追踪值对象，不是授权证明；旧存储可回退 requestId 作为 run | Coding 强制显式 tenant/user/run，禁止 requestId 回退；授权由独立可信适配器每次检查 |
| [A1 契约](../chatchat-common/src/main/java/com/chatchat/common/runtime/coding/) | 已有构造器语义校验、validateAgainst、validateSuccessor 和跨语言 hash | 原样复用，未修改四个公共 DTO、状态、Schema 或 hash 语义 |

既有 AgentEventStore/RuntimeEventJournal 分别有自身事件类型和存储生命周期，没有与 Evidence checkpoint CAS 共事务的既成能力。A2 将 Coding 标准事件与快照放在同一检查点聚合中提交，避免在两个存储间宣称不存在的原子性。

## 2 持久化与 revision CAS

每个 `(tenantId,userId,runId)` 分区使用一个 `coding.tasks.v1` 检查点，内部 storageVersion=1，包含 tasks、budget definitions，以及每项任务的合同、Outcome、Lease、操作账本和事件。taskId 在分区内寻址，因此完整任务身份为 tenant/user/run/task。

单个聚合使共享 budgetId 的跨任务预留也能与任务状态原子提交。预算身份只在同一 tenant/user/run 内共享，不承诺跨运行或跨租户共享钱包。

submit 先检查可信身份，再在事务内检查 taskId 与 A1 canonical hash。相同 hash 返回原任务，不增加任务 revision 或重复创建事件；不同 hash 返回 TASK_ID_CONFLICT。新任务的 CREATED 与 TASK_CREATED 在同一次 checkpoint 提交。响应丢失时沿用同一 taskId 重试。

所有改变通过同一分区行锁串行化：worker 写操作校验 expectedRevision；构建新 Outcome 时 validateSuccessor 要求 revision 严格加一；物理检查点 UPDATE 同时校验旧 entry revision。cancel/resume 的 A1 签名不携带 expectedRevision，由控制器在锁内读取最新 revision 后更新。修改多个任务时，每个任务独立递增 revision，最后统一提交聚合。

事务回调仅做受限内存转换、身份检查、时间检查及 JSON 编码。不得在其中调用模型、Sandbox、外部网络或再次访问同一 Store。证据及执行证明先在事务外校验，写入时再校验 revision/fencing；引用生命周期保护与证据归集由 C 阶段继续实现。

## 3 Lease 与启动意图

每项任务持久化 owner、leaseUntil、fencingToken。claim 必须具备 WORKER 权限和正确任务 revision，只能领取未被有效租约占有的任务；每次领取 token 加一。renew 必须仍持有有效 owner/token，不能在过期后续租旧 token。租约范围为 1ms 至 5 分钟。

预算预留、启动意图、结算、执行证明和证据提交都在事务锁内校验当前 owner/token 及时间。过期 owner 无法写入；新 owner 获得更大 token，但租约过期本身不证明外部工作已停止。

每个 operationId 固定 intentSha256、操作类别、预留、执行句柄、启动 fencing、已知实际用量及停止证明。类别区分 MODEL、TOOL、CODE 与仅记账的 ACCOUNTING；执行类别必须预留相应调用次数，CODE 还需合同明确授权 CODE_EXECUTION。ACCOUNTING 不能提交启动意图。

startIntent 只持久化派发意图，返回 dispatchIntentCreated；重复相同请求返回 false，不授予第二次派发。不能使用已结算释放的预留启动工作。A2 不产生 EXECUTION_STARTED 事件，因为还没有执行节点确认。B1 仍必须在执行节点按 operationId 去重并核验 fencing；A2 不能代替该节点门禁。

接管时发现启动意图而没有可信停止证据，会设置 reconciliationRequired、EXECUTION_OUTCOME_UNKNOWN，并阻止新预留、重放和终态。旧 owner 的句柄和剩余预算保留。无启动意图的任务可以在新 owner 下继续准备工作，累计预算不重置。

## 4 累计预算

预留与结算覆盖 modelCalls、modelTokens、toolCalls、codeExecutions、cpuMillis、costAmount、outputBytes。金额用 BigDecimal 精确运算，并按 A1 规范输出十进制字符串。

每个操作的 actual 是累计已知下界，不是本次增量。未完成计量时，每个维度保留 `max(reservation - actual, 0)`；预算占用为 actual 加剩余预留。已知调用次数可以结算，同时费用未知的剩余额度继续占用。meteringComplete=true 才释放该操作未用余额；重复相同结算不重复计费，已知累计值不能回退。

同 budgetId 下所有任务的操作账本共同参与额度检查。预留被拒绝会持久化预算停止请求；实际用量超额仍原样保存，并向共享该预算的非终态任务下发停止请求。恢复、续租、接管和终态后记账都沿用原 budgetId。

peakMemoryBytes 按峰值观察，不相加成累计内存；预留不接受非零瞬时内存值。maxMemoryBytes 和磁盘、文件数的实际限制交由执行环境实施。A2 没有进程、网关和计量器，不能强制中止 CPU、Token、费用或输出超限；每个权威快照都明确保留 admission-only 限制。未知费用也不能由 A2 独立判断为已知零费用，结算输入必须来自后续可信计量适配器。

deadline 在 claim、resume、预留、启动和终态提交路径复核，达到时持久化停止请求。A2 不创建新的全库超时调度器；活动任务的周期检查由后续既有任务调度接入触发。

## 5 取消、停止与终态

cancel 先持久化停止意图及标准事件；不将接受取消等同于停止。完成与取消以同一事务序列为准：已提交 COMPLETED 不被迟到取消改写；已接受停止则不会提交后续 COMPLETED。提交完成时新发现的 deadline 会保存为停止快照，而不是抛异常后把停止请求回滚。

停止确认有两种不同依据：

1. 持久账本能证明该任务从未发出任何启动意图，控制器可通过 finishStoppedWithoutExecution 结束为 CANCELLED/BUDGET_EXCEEDED。
2. 已有启动意图，必须通过 CodingExecutionProofVerifier 核验不可变执行节点证明。A2 没有该适配器，默认拒绝确认；模型声明、租约到期或客户端断连都不构成停止证据。

commitOutcome 忽略提议方对 revision、usage、取消标志和执行停止字段的控制，使用权威账本重新构建快照。终态要求所有已派发操作完成停止核对，并检查证据引用和 fencing。无真实执行节点证明的未知工作不会被伪装为终态。

终态不可回到 RUNNING。settle 可以在有效租约下增加终态 revision 记录迟到费用；reconcileTerminalEvidence 可以追加已提交证据的核对事件，保持原结果引用、状态及 endedAt。TASK_FINISHED 不会重复生成。

## 6 事件与权限

事件与快照、操作、Lease、账本写在同一 JSON 聚合事务中。事件序号来自任务快照，读者只能看到提交后的配对状态。subscribe 使用共享调度和 Executor 回放、追踪持久日志；不依赖本进程内通知。回调串行化，关闭监听不取消任务。

每个任务保留最近 128 条标准事件；更早的游标显式返回 EVENT_CURSOR_EXPIRED，错误说明携带 earliestSequence。权威快照和操作账本保留，但这不是完整的长期事件审计归档。生产如要求保留完整历史，需要在既有事件设施中增加与检查点一致的归档机制，不能把当前窗口宣称为完整历史。

CodingTaskAccess 是强制依赖，没有 allow-all 默认。BoundCodingTaskAccess 将可信身份供应器的 tenant/user/run 及 Action grants 与目标比较，不采信 attributes、调用方自报租户或 owner 名称。生产授权适配器还需接入实际权限撤销策略；后台订阅应绑定可复核的委托身份，不能依赖已结束 HTTP 请求的 ThreadLocal。

## 7 启用方式与实施边界

CodingTaskRuntimeConfiguration 默认关闭。显式配置 chatchat.coding.enabled=true 时必须提供 CodingTaskAccess、既有 RuntimeExecutionCheckpointPort/EvidenceStorePort、共享 agentRuntimeExecutor 和应用 TaskScheduler；缺少可信授权或执行设施会启动失败，不会构造另一套默认 Runtime。

默认证据引用校验复用现有 EvidenceRecord/EvidenceSnapshot 读取；自定义 CodingEvidenceVerifier 可复用资源解析适配器。实际 DatasetHandle、代码文件及验证 manifest 的统一归集属于 C1/C2，A2 不宣称已完成六项真实执行产物闭环。

A2 没有新增 REST 入口、全库 Coding 队列或自动 Coding worker，不启动 LLM Workflow。已知 scope/taskId 可通过 submit/get/claim/resume 等入口恢复。Python Sandbox、MCP 执行、Cline、Analysis Loop 和 Studio 页面均未接入。

## 8 数据库迁移说明

本次不新增表、列、索引或通用 Evidence Store，因此没有新增 DDL 迁移文件。依赖已有 runtime_evidence_partition 和 runtime_evidence_entry，以及对应方言的历史迁移：

- [H2 V20261009_05](../database/migration/h2/V20261009_05__runtime_evidence_revision_gate.sql)
- [MySQL V20261009_05](../database/migration/mysql/V20261009_05__runtime_evidence_revision_gate.sql)
- [PostgreSQL V20261009_05](../database/migration/postgresql/V20261009_05__runtime_evidence_revision_gate.sql)

应用层迁移是新的 Coding 命名空间和内部 storageVersion=1。缺少/未知内部版本显式拒绝，不把无法解码的内容当成空任务集合。合同 hash 继续使用 A1 算法，存储 codec 的 map 排序只用于稳定检查点编码。

新增 mutateExecutionCheckpoint 是默认拒绝的可选 Java 方法，对原 CAS 调用方保持兼容；不修改 A1 wire。启用 A2 前必须同时升级 agents 与 JDBC 适配器。其他 checkpoint 实现若未提供事务 mutation，A2 会拒绝写入。

回滚先停用 Coding 入口/worker，再关闭该功能。必须保留现有检查点、启动意图、token、未知结果和预留，不能删行来解除额度或让旧执行重放。现有分块清理仅回收无当前检查点引用的载荷，不回收有效 Coding 账本。

## 9 源码变更清单

| 位置 | 变更 |
| --- | --- |
| common/runtime/evidence/RuntimeExecutionCheckpointPort.java | 增加事务 mutation 可选能力；A1 Coding 文件未改 |
| api/runtime/JdbcRuntimeEvidenceStore.java | 复用分区事务，实现 mutation 和带 revision 的 UPDATE |
| [agents/runtime/coding](../chatchat-agents/src/main/java/com/chatchat/agents/runtime/coding/) | DurableCodingTaskService、CodingJournal、CodingBudgetMath、CodingTaskAccess、BoundCodingTaskAccess、CodingEvidenceVerifier、CodingExecutionProofVerifier |
| [CodingTaskRuntimeConfiguration](../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/CodingTaskRuntimeConfiguration.java) | 默认关闭、要求可信授权的组合配置 |
| [CodingTaskPersistenceTest](../chatchat-api/src/test/java/com/chatchat/api/runtime/coding/CodingTaskPersistenceTest.java) | 真实 JDBC 并发、事务回滚、权限、Lease、预算、事件和恢复测试 |
| [CodingRecoveryProcess](../chatchat-api/src/test/java/com/chatchat/api/runtime/coding/CodingRecoveryProcess.java) | 独立 JVM 创建/恢复文件数据库；使用 Java 参数文件避免 Windows 命令行长度限制 |
| [CodingBudgetMathTest](../chatchat-agents/src/test/java/com/chatchat/agents/runtime/coding/CodingBudgetMathTest.java) | 逐维预算、精确费用及内存峰值纯单元测试 |
| [RuntimeCheckpointMutationTest](../chatchat-common/src/test/java/com/chatchat/common/runtime/evidence/RuntimeCheckpointMutationTest.java) | 未支持事务的适配器不能伪装成原子 mutation |

## 10 未解决风险与适用范围

- 数据库并发和恢复在真实 H2 上验证，不能等同于真实 MySQL/PostgreSQL 的隔离级别、死锁及故障恢复验收。
- Lease 使用注入的可信 UTC Clock，生产配置使用系统时钟；多节点需同步时钟并评估偏差，当前没有数据库时钟适配。
- 每个 owner run 为单一聚合与分区锁，容量为 64 项任务、每任务 256 个操作、每任务 128 条事件，整个检查点最多 64MiB；适合首阶段控制面，不代表已验证高吞吐、无限长任务和长期完整审计。
- 预算预留依赖可信上界与后续实际计量。进程内存、磁盘、CPU 硬中止、模型 Token/费用硬上限和网络隔离尚需 B/D 阶段实现。
- 已启动意图的停止确认默认不可用，任务会保持待核对，不能承诺自动完成实际进程恢复。
- 引用校验与资源回收间的引用保留保护、完整 Dataset/Artifact 归集尚属 C 阶段；不能把存在性校验等同于完整证据治理。
- 既有 JDBC 分区身份限制为 Java String.length 不超过 128；A1 使用 Unicode scalar 长度。极端补充平面字符身份的存储兼容性仍需统一，本轮不改变 A1 或既有全局身份列规则。
- 尚未连接生产权限提供器、Coding worker 和跨全库任务发现；默认禁用可防止缺少这些接线时误执行。
