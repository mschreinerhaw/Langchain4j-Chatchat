# Runtime OS Coding Agent：原有数据获取体系兼容性审查

日期：2026-10-15。结论：D1/D2 必须保留现有业务取数路径。Coding Agent 只能消费 Runtime 已交付的授权数据快照，不能成为第二个数据获取入口。**D1 本地代码已有可信 DatasetHandle 快照导出与归集后的派生 DatasetHandle 登记能力，但尚未接入 Analysis Loop，生产 Java 也未部署回调代码；D1 未验收，因此不实施 D2 修改。**

## 源码事实与边界

| 环节 | 实际源码行为 | D1/D2 约束 |
| --- | --- | --- |
| 资产发现、模板选择、参数绑定和执行 | `AgentOrchestrationEngine` 与既有计划/工具执行链负责模板候选评估、参数绑定和执行；`AnalysisEvidenceCoordinator.project` 只将成功且可用的执行结果投影为 Dataset，失败或未调用结果进入排除记录。批量结果按 `templateId/templateCode#callId` 分隔，避免不同实体绑定混淆。 | 不改动该工作流；Coding 不能调用模板选择/绑定接口，也不能自行声明数据授权。 |
| Analysis Loop | `AnalysisCoverageCoordinator.analyzeWithHarness` 将已投影的 Dataset 列表送入 `ModelNativeAnalysisHarness`。Harness 的 `CALL_TOOL` 分支明确拒绝外部工具派发，其 L2 仅列出已交付数据的操作。模型可基于这些数据直接完成分析，不存在必须调用 Coding 的步骤。 | D2 只增加可选的受控 Coding 意图；保持既有数据操作、原始取数和模型自主分析。不得开放通用 `CALL_TOOL`。 |
| `HarnessToolAccess` | 有授权工具白名单、恢复检查点及 `DIRECT`/只读筛选，但 `ModelNativeAnalysisHarness.withToolAccess` 保存的字段未被执行路径使用；`AnalysisCoverageCoordinator` 中的 factory 也未实际调用。 | 不把这个休眠入口直接接给 Cline；它不是可证明的模板授权路径。 |
| `DataWorkspaceOperations` | `CATALOG`、`QUERY_DATASET`、`BATCH_MODEL_INFERENCE` 仅从当前 run 的 `Map<String, Dataset>` 读取。`QUERY_DATASET` 扫描已有 `DatasetHandle` 并登记 `workspace:` 派生句柄；没有业务数据库查询入口。 | Coding 输入只能从这个受控 run 数据集集合选择，使用确切 datasetReference，不接受模型提供的数据库连接或文件路径。 |
| `CodingTaskContract` | 已有 `inputSnapshotRef`、`workspaceRef`、`authorization.allowedDataRefs`、`metadataRefs`、`environmentRef`、预算与输出契约；合同只携带引用，本身不证明引用来自已授权 DatasetHandle。 | D1/D2 必须增加提交前的可信绑定校验，不修改 A1 字段语义：原始句柄、快照、授权决定、合同 hash 和任务身份必须能互相核对。 |
| B/C 设施 | `CodingPythonExecutionBridge` 对输入 fileId、环境和网络策略做合同校验；B 的任务工作区验证代码/输入哈希并阻断路径、符号链接越界。`CodingEvidenceCollector` 验证 manifest、完整性、行数、内容哈希与任务血缘，并通过 `registeredResult` 返回 `AnalysisEvidenceCoordinator.Dataset`。 | 复用这些设施；现有 `registeredResult` 是派生句柄入口，但尚未自动加入 Harness 当前 run 的数据集映射。 |
| SQL Studio | `SqlStudioGenerationToolPolicy` 只允许元数据读取，明确拒绝生成会话的业务 SQL 执行；`SqlStudioMetadataService` 只允许 `LIST_NAMESPACES`/`SEARCH`/`DESCRIBE`，服务端固定绑定并让 MCP 重新核验授权。 | SQL Studio 的交互式执行须使用自身明确的执行契约，不继承 Coding 任务的 Analysis Loop 数据权限。 |

## 输入与输出映射要求

1. **输入选择**：Analysis Loop 从当前 run 已交付的 Dataset 列表中按精确 `datasetReference` 选择；Runtime 再核对租户、用户、run、模板执行来源和授权决定。`metadataRefs` 只能供口径解释，不能增加 `allowedDataRefs`。
2. **不可变输入快照**：Runtime 以 `DatasetHandle.scan` 分页导出指定句柄，记录源 reference、内容 SHA-256、Schema、精确/估计行数、授权模板和执行回执、水位线及导出版本。导出受合同大小/行数限制；快照先保存并授权，再把其 fileId/hash 列入 B 的 task 专属工作区。`inputSnapshotRef` 引用这份已提交的快照证据。不能把整个 uploads 目录或来源数据库挂进 Sandbox。
3. **Coding 计算**：Node 只收到快照引用与受限工具；Python 只读本任务快照、仅写指定输出。`metadata_read` 只能解析合同列明且当前仍有权限的元数据。Coding 工具列表不包含业务 SQL 执行或数据库连接能力；Python 源码本身仍是不受信任的，必须靠 Sandbox 网络/挂载/凭据隔离而非文本扫描阻断对业务库的访问。D1 当前在 Java 提交和 Node 执行入口均拒绝非 `NONE` 网络策略，B 环境还需实际强制隔离。
4. **结果登记**：C 阶段归集器验证结构化结果和独立校验，提交六项引用后调用 `registeredResult(scope, taskId, resultDatasetRef)`，将返回的 Dataset 加入当前 Analysis Loop 的数据集集合。其 `analysisContext` 已携带 `parentEvidenceIds`、任务 ID、行数及 Schema；原始 Dataset 与派生 Dataset 同时保留，不能覆盖源句柄。
5. **多轮引用**：Harness 每轮从 `prepared.sources()` 构造目录，并能索引新加入的 Dataset；D2 必须在后续轮次按受信的 Coding 完成事件更新该集合，并持久化原始/派生 reference、证据 bundle 与血缘。任务未提交或验证失败时不得把 stdout、模型总结或未完成文件登记成 Dataset。模型可以不调用 Coding，直接用原始证据完成分析。

## 数据不足与控制权

当前 Harness 的 `evidenceAssessment.missingEvidence` 是模型审查记录，`sourceCoverageObservations` 列出取数执行缺口；两者都不是授权或自动再次取数命令。D2 应把 Coding 返回的数据缺口规范化为包含 `datasetReference`、所需字段/期间、缺口原因、已验证证据引用和建议补数意图的结构化状态。Analysis Loop 自行决定是否发起下一轮原有资产发现/授权模板流程；Coding 不得调用该流程，也不得用自由 SQL 补数。不要把模型的业务判断硬编码成 Runtime 规则。

## 明确禁止与测试门槛

- 禁止在 Cline 工具列表中出现通用 `CALL_TOOL`、SQL 执行、数据库连接、宿主 Shell、Docker Socket 或任意文件读取。Metadata MCP 的成功响应不改变数据授权。
- 拒绝合同之外的 datasetReference、跨租户/跨 run 句柄、失效授权、篡改快照哈希、额外 Python 输入文件、任意网络连接和将模型文本伪装为结构化数据集。
- 验证原始模板路径的回归测试、模型不调用 Coding 的完成路径、数据不足返回 Analysis Loop 的路径、六项证据与原始/派生血缘、多轮恢复后两个句柄仍可读取。SQL Studio 授权测试独立于 Coding。
- D1 真实链路与上述快照绑定、证据回流测试通过前，D2 不得编码或宣称通过；D2 不改变 A1 公共契约语义，也不重构原有取数工作流。

当前缺口：可信快照导出/授权绑定及 Python 执行证据回流已在 D1 本地代码实现并通过定向测试，但没有可信的 Analysis Loop 提交入口，也未经过真实模型和生产跨进程验收；`HarnessToolAccess` factory/字段虽存在但不在现行 Harness 执行路径。D2 的最小接点应在 `AnalysisCoverageCoordinator` 与 Harness 的受控操作分派处，不在模板执行器或 SQL Studio 中。

## Java 控制面与 Node 执行面的职责边界

| 职责 | Java Runtime OS 保留 | Node Coding Agent 承担 |
| --- | --- | --- |
| 主分析循环 | `ModelNativeAnalysisHarness` 保存主模型的分析轮次、已交付 Dataset 目录、计划、取证结果和发布意图；模型自主决定是否需要 Coding。 | 只处理受委派的编程子任务，不修改分析目标，不决定报告发布。 |
| 数据与工具 | 原有 MCP 资产发现、授权模板、参数绑定、执行模板继续取数；Runtime 将选中的 DatasetHandle 固化为受限输入快照，校验工具白名单、身份、预算及 fencing。 | 在下发的工具列表内选择调用顺序和修复方法；不能直接读业务库、执行自由 SQL、访问宿主 Shell 或 Docker Socket。Metadata MCP 的元数据授权不增加数据授权。 |
| 上下文 | 保存可恢复的任务快照、合同哈希、预算、证据引用和 Analysis Loop 工作区；只交付当前任务必需的数据/口径。 | 管理 Coding Loop 的临时代码、错误诊断、Patch 和模型上下文；本地 SDK 会话不能成为权威任务状态。 |
| 执行与证据 | A2 管理状态、lease、fencing、累计预算与取消；B 的 Python Sandbox 执行代码；C 的归集器验证哈希、Schema、行数、执行回执和独立业务校验，登记派生 DatasetHandle。 | 生成代码、调用受控 Python 工具、根据技术错误重试；提交候选产物与事件，不自行发布业务 Claim 或更改证据。 |

现有 `CodingAgentSPI` 已覆盖 Coding 任务的提交、查询、取消、恢复和事件订阅，应先完成该适配器的验收。现有通用 `AgentRuntime` 负责 run 生命周期、工作流与观测；此时再引入一套通用 `AgentRuntimeProvider` 会与既有运行时和 A2 任务状态重叠。待第二种独立执行引擎进入时，再从已验证的公共语义提取更宽的 Provider SPI。

## 定向测试核对

执行 `mvn -pl chatchat-agents -am -Pbackend-only '-Dfrontend.skip=true' '-Dtest=ModelNativeAnalysisHarnessTest,DataWorkspaceOperationsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，日志为 `target/coding-d-data-compat-tests.log`。39 项中失败 26、错误 2；`DataWorkspaceOperationsTest` 为 9 项中 1 项失败，`ModelNativeAnalysisHarnessTest` 为 30 项中 25 项失败、2 项错误。这不能作为当前 Analysis Loop 回归通过的证据。

失败主要集中于旧测试桩调用 `ChatModel.chat(String)`，而现行 Harness 默认调用 `ChatModel.chat(ChatRequest)`；测试未设置 `harnessLegacyTextTransport`，模型返回空值后耗尽轮次。当前没有修改 Harness 或这些测试。D2 前应把测试桩改成与现行结构化模型调用一致，并重新确认模型自主完成、原始数据读取、派生结果和跨 run 拒绝等行为；若仍失败，再区分真实代码缺陷与测试过期。
