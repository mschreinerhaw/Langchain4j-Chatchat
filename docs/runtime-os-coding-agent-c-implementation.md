# Runtime OS Coding Agent C1–C3 实施与引用协议

日期：2026-10-15。范围：固定脚本的 Python 执行结果回流 Runtime OS Evidence Governance；不调用模型，不接入 Cline，不修改 Analysis Loop。

## 源码变更

| 阶段 | 代码 | 职责 |
| --- | --- | --- |
| C1 | `chatchat-api/src/main/java/com/chatchat/api/runtime/coding/evidence/CodingEvidenceCollector.java` | 从已有 MCP Python 归档读取代码、输入、manifest、结果与回执；核对哈希、大小、JSONL Schema、行数、完整性、A2 停止证明；生成六项引用与血缘；按任务授权读取结果。`registeredResult` 使用现有 `AnalysisEvidenceCoordinator.Dataset` 和 `DatasetHandle` 类型提供分析工作区接入形状。 |
| C1 | `chatchat-agents/src/main/java/com/chatchat/agents/runtime/coding/DurableCodingTaskService.java` | 增加只读 `verifiedOperation`，供归集器核对 A2 已验证的 operationId、启动 fencing token 和停止证明。 |
| C2 | `chatchat-api/src/main/java/com/chatchat/api/runtime/evidence/JdbcRuntimeEvidenceStore.java` | 在既有 owner 行锁与 JDBC 事务内，原子写入证据、血缘、bundle 快照和 COMMITTED 检查点；失败整体回滚。没有新增 Evidence Store 或证据数据库。 |
| C3 | `chatchat-api/src/test/java/com/chatchat/api/runtime/coding/evidence/CodingEvidenceCollectorTest.java` | 真实 H2 事务、授权读取、零行、业务校验失败、VERIFIED 后故障与幂等恢复。 |
| C3 | `chatchat-api/src/test/java/com/chatchat/api/runtime/coding/cline/CodingEvidenceDockerIntegrationTest.java` | 真实 Docker + MCP 执行服务 + A2 任务/预算 + Evidence Store 的固定脚本闭环。 |
| 测试依赖 | `chatchat-api/pom.xml` | 仅 test scope 引入 MCP server，以便直接联测实际 B 执行服务；生产依赖方向不变。 |

没有数据库迁移。沿用 `runtime_evidence_partition`、`runtime_evidence_entry` 及 B 阶段 `mcp_python_managed_operation`/fence 表。`DataWorkspaceOperations` 的任务内结果 map 不适合充当跨进程权威注册表；C 采用现有 Evidence Store 注册权威引用、B 归档保全字节，并把结果转为现有 `DatasetHandle`/`Dataset`。现有 `SpillStore` 负责 Analysis Loop 的临时溢出，不复制已持久归档的结果。`StructuredDataEvidence` 与 `ComputationEvidence` 的语义分别映射为 `CODING_STRUCTURED_DATASET` 与 `CODING_COMPUTATION_VALIDATION` 记录；不生成 Claim，也不将 stdout 当作结果数据。

## 六项引用和解析规则

六项均在同一个 tenantId/userId/runId 的 owner 分区中。`inputSnapshotRef` 必须等于 A1 合同中的引用；其余引用以 `runtime-evidence:` 加不可变 ID 表示。前五项是 `EvidenceRecord`；`evidenceBundleRef` 是既有 `EvidenceSnapshot`。bundle 包含前五项证据 ID。结果血缘父节点为输入、代码和执行回执；验证血缘父节点为结果。代码与结果记录保存 MCP 操作定位信息、文件内容 SHA-256 和字节数；结果另存 Schema、精确行数和完整性。授权读取先检查 A2 任务权限和血缘中的 producingTaskId，再由 MCP 归档接口重新核对字节哈希及行数。`resolveBundle` 同时检查快照 taskId 与五个成员的授权；已有输入证据按合同中的 `inputSnapshotRef` 例外授权。

固定测试中，一次正常执行得到的确定性引用示例（scope 为 `tenant/owner/run`，taskId 为 `normal`，operationId 为 `op-normal`）：

| 字段 | 引用 |
| --- | --- |
| `inputSnapshotRef` | `runtime-evidence:input-normal` |
| `codeArtifactRef` | `runtime-evidence:coding:e6e9098b60c40d05725cf8f695a22e2df8240d07312f3ad61cd4410b1fcb7222:code` |
| `executionReceiptRef` | `runtime-evidence:coding:e6e9098b60c40d05725cf8f695a22e2df8240d07312f3ad61cd4410b1fcb7222:receipt` |
| `resultDatasetRef` | `runtime-evidence:coding:e6e9098b60c40d05725cf8f695a22e2df8240d07312f3ad61cd4410b1fcb7222:result` |
| `validationRecordRef` | `runtime-evidence:coding:e6e9098b60c40d05725cf8f695a22e2df8240d07312f3ad61cd4410b1fcb7222:validation` |
| `evidenceBundleRef` | `runtime-evidence:coding:e6e9098b60c40d05725cf8f695a22e2df8240d07312f3ad61cd4410b1fcb7222:bundle` |

本例结果为 JSONL `{"amount":20.00}`，Schema `{"amount":"NUMBER"}`、行数 1；独立 Java 验算器从已授权的 CSV 原始记录重新求和，并把 `verifierId`、`ruleVersion`、预期值和源行数写入验证记录。`COMPLETED` 仅表示脚本任务按合同完成；业务不一致仍能形成 `ValidationStatus.FAILED` 的独立记录，由 Runtime 决定是否使用或发布。

## 证据阶段与恢复

归档字节由 B4 在 MCP 工作区先保全。C 在读取时逐个验证 manifest/文件哈希、大小、JSONL 结构、Schema、精确行数与完整性，并比对 A2 已接受的停止证明。随后通过 `RuntimeExecutionCheckpointPort` 保存带 batch 哈希的 `STAGED`、`VERIFIED`。只有 VERIFIED 的相同 batch 能进入 `JdbcRuntimeEvidenceStore.commitCodingEvidence`；该方法在一笔 owner 事务中写入所有证据、血缘、bundle 和 `COMMITTED`。事务失败没有公开引用；重试相同 batch 返回原有不可变记录与快照，变更 batch 被拒绝。终态后的迟到证据按 A2 的 `reconcileTerminalEvidence` 只增加任务 revision，不改已提交 manifest，也不重新运行 Python。

`openResult` 返回既有分页 `DatasetHandle`。当前实现会一次读取不超过合同上限的 JSONL 再构造 `InMemoryDatasetHandle`；不适合大结果流式分析。B 的归档是权威字节来源，Evidence Store 持有受控定位、哈希和血缘，不把大文件复制进 2 MiB 的证据行。
