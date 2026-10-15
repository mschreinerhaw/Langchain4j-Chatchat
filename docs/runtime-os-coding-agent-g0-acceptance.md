# Runtime OS Coding Agent G0 验收报告

日期：2026-10-15。结论：**G0 通过，范围限定为 A1 公共契约及 A2 控制面在本地真实 H2 数据库上的验收。** 这不代表 G1 Sandbox、真实 MySQL/PostgreSQL 或生产部署验收通过。

实现、实际复用方案、源码清单、数据库迁移说明及风险见 [A2 实现说明](runtime-os-coding-agent-a2-implementation.md)。A1 的四个公共契约、wire 状态、Schema 与规范化 hash 语义未改变。

## 1 编译与测试结果

最终命令：

```powershell
mvn -pl chatchat-api -am -Pbackend-only '-Dtest=Coding*Test,JdbcRuntimeEvidenceStoreTest,RuntimeCheckpointMutationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

13 个 reactor 模块成功；执行的相关测试合计 **152 项，失败 0、错误 0、跳过 0**。这是定向回归结果，不是全部仓库测试的结果。

| 测试组 | 数量 | 依据 |
| --- | --- | --- |
| A1 CodingCanonicalHashTest / CodingContractsTest / CodingJsonSchemaTest | 97 | 公共类型、状态矩阵、Java/Node 字节/hash、Schema 正反例 |
| RuntimeCheckpointMutationTest | 1 | 无事务适配器不允许用 unlocked read/CAS 模拟 mutation |
| CodingBudgetMathTest | 9 | 每个累计维度的独立超额、精确小数、未知余额和内存峰值 |
| CodingTaskPersistenceTest | 27 | 真实 H2 JDBC、独立连接竞争、事务回滚、停止/预算/恢复及权限 |
| 既有 JdbcRuntimeEvidenceStoreTest | 18 | 原 CAS、分块、读取、清理及 Evidence Store 行为回归 |

可复核输出：

- [完整构建日志](../target/coding-a2-validation.log)
- [按 suite 和 testcase 汇总的结果 JSON](../target/coding-a2-g0-results.json)
- [A2 数据库测试报告](../chatchat-api/target/surefire-reports/com.chatchat.api.runtime.CodingTaskPersistenceTest.txt)
- [预算单元测试报告](../chatchat-agents/target/surefire-reports/com.chatchat.agents.runtime.coding.CodingBudgetMathTest.txt)
- [原 Evidence Store 回归报告](../chatchat-api/target/surefire-reports/com.chatchat.api.runtime.JdbcRuntimeEvidenceStoreTest.txt)

target 下的证据由本次构建产生；重建或清理 target 后需重新生成。

## 2 十二项要求对应的真实测试

| 验收项 | 代表测试方法 | 结果 |
| --- | --- | --- |
| 相同任务重复提交 | acceptedSnapshotAndEventAreCommittedBeforeSubmitReturnsAndDuplicatesAreIdempotent | 12 个并发提交返回相同任务，只有一个接受事件 |
| 同任务不同合同冲突 | sameIdWithDifferentContractOrSharedBudgetLimitsIsRejected | hash 或共享预算定义冲突明确拒绝 |
| 多执行者竞争 | independentDatabaseConnectionsCompeteForOneOwner | 12 个竞争者通过独立 JDBC 连接，仅一个领取成功 |
| Lease 失效 | actualWallClockExpiryRejectsTheOldOwner | 使用真实系统时钟等待过期，旧 owner 不能续租 |
| Fencing 失效 | expiredLeaseAndOldFencingCannotReserveStartOrPublish | 接管后旧 token 不能预留、提交启动意图或发布结果 |
| 取消与完成竞争 | cancellationAndCompletionSerializeOnTheSameDatabaseTransaction | 事务顺序决定结果，已接受取消不会变成 COMPLETED |
| 预算并发预留 | concurrentReservationsAcrossTasksCannotOverspendOneSharedBudget | 六项任务竞争同一预算，仅额度允许的两项预留成功 |
| 未知用量 | unknownCostRetainsResidualReservationAndRepeatedSettlementIsIdempotent | 未知费用保留余额，重复结算不重复计费，累计实际值不回退 |
| 任务重启接管 | twoIndependentJvmProcessesRecoverThePersistedUnknownStartIntent | 两个独立 JVM 依次打开同一 H2 文件库，保留预算、递增 token，并保持未知执行待核对 |
| 终态不可逆 | terminalRevisionsMayRecordLateFeesButNeverRestartTheTask | 终态可增加记账 revision，状态和结束时间不变，禁止新工作 |
| 状态与事件事务一致 | snapshotEventsLeaseAndBudgetRollBackTogetherAfterRealJdbcWriteFailure | 实际 UPDATE 后注入异常，事务回滚快照、事件、账本及物理 revision |
| 跨租户权限隔离 | ownerTenantAndRunAreAuthenticatedBeforeAnyDatabaseAccess | 拒绝跨 tenant/user/run 的读取、停止、恢复和事件访问，不创建越权分区 |

额外验证包括同意图不重复派发、已释放预留不能绕过预算、ACCOUNTING 不能启动代码、实际超额触发共享预算停止、终态证据核对的 fencing、只读权限不能领取、事件重放与跨实例追踪、游标压缩显式失效，以及无效内部版本不被当作空任务集合。

## 3 数据库与进程验证的准确范围

CodingTaskPersistenceTest 不使用 Mock Store、Mock CAS、Mock JDBC 锁或 Mock 恢复。并发测试使用真实 H2 事务与独立数据库连接；故障注入在实际 SQL UPDATE 执行后抛出异常，验证真实事务回滚。

文件恢复既有重新构建服务/连接的测试，也有独立 Java 子进程的创建与恢复测试。进程测试保留逻辑启动意图，没有启动真实代码进程。它验证任务控制状态的跨进程恢复，不验证操作系统强杀、容器重启或 Sandbox 真实停止。

大部分边界时间测试使用可控 Clock，另有真实系统时钟的 Lease 过期测试。未运行多机时钟偏移或网络分区实验。

完成/取消竞态中的六项引用是实际注册到当前 Evidence Store 的确定性测试记录，显式标明 fixture 来源。它们不是伪造的 Sandbox 执行证明，也不作为 C3 数据分析闭环验收。已有启动意图时，默认没有停止证明适配器，测试要求任务保持 RUNNING/待核对，不能用 fixture 或模型声明代替执行节点确认。

## 4 数据库迁移与兼容性

本次没有新增 DDL；复用现有三种方言的 runtime_evidence_partition/runtime_evidence_entry。新数据只增加 coding.tasks.v1 命名空间和内部 storageVersion=1。事件、预算与所有权不拆到另一个无法共事务的数据库。

RuntimeExecutionCheckpointPort 新增默认拒绝的可选 mutation 方法，旧调用方仍使用原 CAS；A2 启用时必须使用已实现该能力的 JDBC 适配器。未修改 A1 公共字段或状态，因此不需要 A1 wire 主版本迁移。

## 5 G0 门槛判定

| 门槛内容 | 判定 |
| --- | --- |
| A1 类型、错误、状态及规范 hash 定型 | 通过，97 项定向回归包含实际 Node 进程 |
| A2 权威快照、幂等、revision CAS | 通过，接受状态提交后返回 |
| A2 Lease/Fencing 与未知结果恢复 | 通过，包含真实数据库并发、系统时钟和独立 JVM |
| A2 累计预算与未知费用保留 | 通过，累计账本与任务原子提交 |
| 停止/终态与事件事务一致性 | 通过，无执行节点证明时拒绝伪造停止 |
| 范围与依赖约束 | 通过，未连接 Sandbox、MCP 异步执行、Cline、Analysis Loop 或页面 |

G0 不消除 [A2 风险清单](runtime-os-coding-agent-a2-implementation.md#10-未解决风险与适用范围) 中的数据库方言、时钟同步、聚合容量、128 条事件窗口、Unicode 身份存储边界、权限/worker 接线及硬资源限制问题。后续可进入 B1 编码准备，但本轮没有实施 B1，不能把 G0 结论用于宣称实际代码执行安全性或生产可用性。
