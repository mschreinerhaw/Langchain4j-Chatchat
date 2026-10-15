# Runtime OS Coding Agent 公共契约实现审查与编码计划

日期：2026-10-15。适用基线：[Runtime OS Coding Agent 集成架构基线 v1.0](runtime-os-coding-agent-baseline-v1.md)。

架构职责和七个公共状态可以保留，但当前契约设计还不足以直接实现可恢复的异步 Coding 服务。首先补齐确定的 wire 类型、执行所有权、Sandbox 操作协议及证据提交协议，再用固定 Python 脚本打通执行和证据回流。Cline 接入依赖这条链路的验收结果。

本文是实施审查和编码任务清单，不表示异步执行、取消、持久化产物或 Coding SPI 已经实现。P0 为进入 Sandbox 联调前必须关闭的缺口；P1 为进入真实 Coding Agent PoC 前必须关闭的缺口。

A1 的类型与纯校验算法实现见 [A1 公共契约实现](runtime-os-coding-agent-a1-contracts.md)。SPI 接口声明已落地，但其异步执行实现、A2 和后续 Sandbox 接入不包含在 A1 内。

A2 后续交付见 [任务控制面实现](runtime-os-coding-agent-a2-implementation.md) 与 [G0 验收报告](runtime-os-coding-agent-g0-acceptance.md)；该交付不包含 B1 执行节点接入。

## 1 公共契约审查

| 编号 | 优先级 | 遗漏或冲突 | 实施决定 |
| --- | --- | --- | --- |
| C01 | P0 | objective、authorization、outputContract、usage 等只有自然语言说明，Java/JSON 类型、长度、集合排序、null 和必填条件尚未固定 | 在四项契约内定义具名嵌套值类型、Schema、有效/无效 JSON 样例和错误码；不能用任意 Map 替代这些核心字段 |
| C02 | P0 | submit 承诺异步持久化，却未定义任务领取、后台执行者和重启接管 | Runtime 任务日志为唯一权威；通过现有执行设施承载后台工作，领取使用租约和 fencing token；供应商 Adapter 不拥有独立任务状态机 |
| C03 | P0 | taskId、operationId、attempt 的幂等边界不够精确；恢复并发可导致重复启动 | 任务合同、执行意图、技术重试分别建键；先持久化启动意图和容器身份，再启动容器；MCP 节点按操作键去重，并在启动入口校验 fencing token |
| C04 | P0 | CANCELLED/BUDGET_EXCEEDED 依赖停止确认，但其他终态和发布竞态未限定 | 所有终态均要求相关执行已停止或已证明从未启动；完成提交同时验证取消标志、预算停止标志和租约；已接受停止请求阻止随后发布 COMPLETED |
| C05 | P0 | 预算将累计量与瞬时限制混合；停止原因只有 cancellationRequested | 累计 CPU/费用/调用次数与瞬时内存、磁盘分别计量；在 Outcome 的可选 executionControl 中表达停止意图、执行器确认、待核对原因；不增加任务状态 |
| C06 | P0 | contractSha256 缺少跨语言规范；完整 scope 含请求追踪字段，重复请求可能被误判新任务 | 固定任务身份为 tenant/user/run/task；requestId 等逐次追踪字段不改变任务身份。定义规范 JSON、十进制字符串、显式默认值与排除字段清单，并以 Java/Node 相同测试向量验证 |
| C07 | P0 | 六项引用没有对应的可解析存储契约；“已有 Evidence Bundle”不等于已有持久 BundleRef | 明确 manifest 的存储、权限解析、hash、保留和 GC；Bundle 使用现有证据存储提交可解析快照，不能仅把内存对象转成引用字符串 |
| C08 | P0 | stdout 与结果数据集没有独立 Schema，可能混入日志或被截断 | 日志、结果文件、验证文件分通道；结果按固定 Schema 导入，零行成功、缺失文件、解析错误、部分写入分别表达 |
| C09 | P0 | Python 按用户/资产隔离，弱于任务授权；输入仍可能被覆盖 | 只投影任务授权文件，启动前复制或使用不可变版本并核验指纹；工作区按任务/操作/尝试隔离，禁止挂载整个用户 uploads |
| C10 | P1 | subscribe 未定义异步失败、回放与实时切换、保留范围、慢消费者 | 以持久事件日志为源，回放采用高水位衔接；有界缓冲，溢出断开并携带最后游标，客户端重连；错误使用标准通道，禁止静默丢事件 |
| C11 | P1 | validationStatus 混合模型自检与独立验算；FULL 可复现的条件未完整定义 | 验证记录标明执行者/规则版本/可信来源，区分脚本声明与平台检查；FULL 必须覆盖不可变输入、环境、依赖及确定性条件 |
| C12 | P1 | 任务终态后仍会有迟到计费和证据核对，快照版本与状态不可逆可能冲突 | 状态终态不可逆；允许增加快照 revision 归集迟到用量和补充证据，旧 manifest 保持不可变；TASK_FINISHED 只提交一次，不能触发重新执行 |

### 1.1 四项契约的具体补全

`CodingAgentSPI` 的 submit/get/cancel/resume 职责保持。subscribe 的 `Consumer` 形式保留为本地便利方法；正式实现前需增加能表达 `onEvent/onError/onClosed` 的嵌套观察者签名，并验证既有事件流如何承载回放。Java 和 wire 均尚未发布，因此在首个契约提交中完成定型；若已发布则使用新版本或兼容重载，不直接替换旧签名。

SPI 的错误对象归属供应商无关边界，至少包含 code、retryable、taskId、operationId、correlationId 和已知状态引用。标准错误至少覆盖 CONTRACT_INVALID、VERSION_UNSUPPORTED、NOT_FOUND、AUTHORIZATION_DENIED、TASK_ID_CONFLICT、EXECUTION_OUTCOME_UNKNOWN、EVENT_CURSOR_EXPIRED、ADAPTER_UNAVAILABLE。响应传输失败可能发生在任务已接受之后；重试必须沿用 taskId。retryable 不代表可以重新执行代码。

`CodingTaskContract` 必须定义 Objective、Authorization、Budget、OutputContract 的具名结构。Objective 同时包含编程指令、交付清单及 acceptanceCriteriaRefs；OutputContract 固定结果/验证格式、文件清单、允许媒体类型、行/字节上限及必需引用。environmentRef、workspaceRef 等引用在接入门禁解析：无效引用拒绝任务；有效工作区引用可由 Runtime 预分配，创建目录由执行器完成，避免提交与工作区分配互相依赖。

`CodingTaskOutcome` 在实现前定型以下可选扩展：executionControl（stopReason、stopRequestedAt、executorStopped、reconciliationRequired）、parentTaskId、supersedesTaskId 和 perArtifactCompleteness。新增任务关联用于终态后继续工作，不能重置原任务预算后冒充恢复。总任务预算如需跨任务累计，由 Runtime 下发同一上层 budgetId 约束。

`CodingExecutionEvent` 增加可选 budget/evidence 更正信息即可承载迟到核对，不增加供应商事件类型。sequence 从 1 开始，afterSequence=0 从保留的首事件读取；若首事件已被清理，返回游标失效及可获取的最早序号。未知可选字段允许忽略；未知影响状态或证据解释的事件不能静默应用，应停止该事件投影并读取权威快照。

### 1.2 操作与租约

任务身份固定为 `(tenantId, userId, runId, taskId)`。执行意图由 Runtime 生成 operationId，同一意图的传输重试沿用该 ID。代码、输入、参数和环境发生变化必须使用新 operationId。attempt 只表示同一意图经确认可安全重试的执行尝试，不能用于绕过未知结果检查。

任务领取记录包含 owner、leaseUntil、递增 fencingToken；启动、预算预留、状态更新和证据提交均验证有效 token。旧 owner 恢复联网后不能继续启动操作或提交权威结果。已经运行的旧进程先核对/停止，不能仅因租约过期就启动替代进程。仅在 API 数据库使用 CAS 不足以阻止 MCP 节点重复启动，执行节点必须参与去重和 fencing。

取消和预算停止的优先级依据持久化事实：COMPLETED 提交前如果已接受停止请求，则禁止完成提交；完成先提交时，迟到停止返回既有终态。停止确认可保留先前有效结果，未知结果不标为零行。清理失败和无法定位执行器时，非终态携带待核对原因；运维可重试核对，不能伪造终态来清空队列。

### 1.3 预算可执行边界

maxModelTokens 明确为输入、输出及供应商报告的其他计费 token 的总计，报告细分计量；缓存 token 不重复计算。maxCpuMillis 为全部代码尝试累计 CPU 时间；maxMemoryBytes 为任务内活动进程的总瞬时内存上限。maxOutputBytes 为累计产物写入/接收上限，不得通过覆盖文件规避；另在执行环境定义磁盘容量和文件数上限。日志预览与完整日志的限额单独声明。

费用、token 和 CPU 的硬上限需要执行器或网关能强制执行或安全预留；监控后 abort 只能提供近似上限。PoC 环境不支持的强制限制应在接入门禁拒绝或显式降级，并将降级纳入任务限制，不能以 Docker 的 --cpus 配额冒充累计 CPU 时间预算。

## 2 Python Sandbox 实际调用链与缺口

当前 Python Studio 测试链路是：

```text
PythonDataScienceService.testScript
  -> McpPythonControlPlanePort.preview
  -> McpPythonControlPlaneClient
  -> PythonAdminController.preview
  -> PythonCapabilityService
  -> PythonDockerRuntime.execute
  -> PythonExecution / API PythonExecutionEntity
```

| 源码 | 现状与实际影响 |
| --- | --- |
| [控制面接口](../chatchat-common/src/main/java/com/chatchat/common/mcp/python/McpPythonControlPlanePort.java) | preview 是同步接口，ExecutionResult 只有状态、日志、退出码和耗时；缺少 task/operation/run、查询、取消及产物引用 |
| [控制面客户端](../chatchat-integration/src/main/java/com/chatchat/integration/mcp/service/client/McpPythonControlPlaneClient.java) | HTTP 调用使用 timeout().block()；连接超时不能证明远端执行停止，返回 DTO 无法恢复查询 |
| [MCP 执行服务](../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/python/PythonCapabilityService.java) | 执行路径同步等待容器；当前调用方法包含 @Transactional 边界，需避免长事务包住容器运行，并确保启动身份在启动前对其他事务可见 |
| [MCP 执行实体](../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/python/PythonExecution.java) | containerId 在 runtime.execute 返回后保存；启动后崩溃时缺乏已提交的稳定操作身份和启动核对依据 |
| [Docker 执行器](../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/python/PythonDockerRuntime.java) | 同资产相同脚本名写入共享目录，会产生并发覆盖；挂载整个 owner uploads；没有可公开查询/取消的持久执行句柄 |
| 同上 | docker run --rm，output 使用 tmpfs，finally 再 rm -f；进程结束后再 docker cp 不能保证取回产物，需调整输出介质和清理顺序 |
| 同上 | 超时先杀 Docker CLI，finally 清理容器，但清理异常被忽略；不能把 CLI 退出当作容器停止确认 |
| [API 归档逻辑](../chatchat-api/src/main/java/com/chatchat/api/datascience/python/PythonDataScienceService.java) | recordExecution 把 stdout 原样保存为 resultJson；没有结构化结果验证、完整性和六项引用 |
| [现有证据类型](../chatchat-common/src/main/java/com/chatchat/common/runtime/analysis/evidence/ComputationEvidence.java) | 可以承载计算 lineage；EvidenceBundle 是值对象，持久 BundleRef 及资源解析仍需显式实现 |
| [工作区结果登记](../chatchat-agents/src/main/java/com/chatchat/agents/orchestration/analysis/graph/DataWorkspaceOperations.java) | 已有 SpillDatasetHandle.capture、来源指纹和结果检查点；retain 是私有方法，需提取可复用登记职责，不能从 integration 直接耦合 graph 私有实现 |

Coding 路径复用同一 MCP 执行器与证据设施，通过新控制面操作补齐能力。旧 preview/executeTemplate 在首轮保持兼容，不能直接替换其 DTO 必填字段或把模板发布作为代码任务完成的隐式步骤。

## 3 优先交付的执行与证据闭环

```text
固定任务与授权输入
 -> 持久化 operation / 预算预留 / 执行身份
 -> 生成任务专属输入和代码快照
 -> 启动受控容器，查询或取消
 -> 停止确认，固化结果文件和日志
 -> 校验 manifest、哈希、Schema、行数和完整性
 -> 登记 DatasetHandle、验证记录及 Evidence Bundle 快照
 -> CAS 提交可解析引用与任务快照
 -> 清理容器及已无引用的暂存资源
```

输出采用任务专属、受配额约束的持久暂存目录/卷，容器仅对该输出目录可写。输入与代码只读。若节点不能提供所声明磁盘/累计输出限制，执行门禁拒绝该环境，不能只在结束后检查大小。首轮不依赖退出后读取 tmpfs。

执行器确认进程停止后才固化输出，拒绝符号链接、目录穿越、非普通文件和超出清单的文件。Manifest 使用相对路径、媒体类型、字节数、SHA-256、结果行数及完整性；扫描和复制须防止链接替换及路径检查后的替换。日志采用有界排空和结构化截断标记，日志截断不影响结果文件完整性声明。

PoC 首轮固定 UTF-8 JSON Lines 结果及单独 JSON 验证文件；结果 Schema 明确 decimal 为字符串、时间与时区、null 和字段类型。零行结果提交合法的空文件和零行 manifest；缺文件不是零行。后续大规模 Arrow/Parquet 单独扩展，不阻塞第一条链路。

证据按任务作用域解析，归档状态使用现有检查点中的内部阶段 `STAGED -> VERIFIED -> COMMITTED`，不扩展 Coding 公共任务状态。每个阶段允许幂等重入；公开引用仅指向 COMMITTED 内容。Sandbox 停止且回执已保全后，归档失败可以 PARTIAL/FAILED 并给出证据缺口，不能声明 COMPLETED。容器清理与持久产物 GC 分开，仍被有效检查点/Bundle 引用的内容不可回收。

## 4 分阶段编码任务

以下为可独立评审的提交单元。所有任务均复用已有模块，不创建平行 Runtime、Gateway 或证据数据库。新增持久字段/索引同时提供 H2、MySQL、PostgreSQL 迁移；真实 Docker 验收与单元测试分开报告。

### P0 A 公共契约与执行所有权

**A1 实现四项公共契约和 Schema。** 在 common 的 `runtime/coding` 包实现四项公共类型及嵌套值类型，附 wire 样例、错误码、状态转换和 hash 规范。依赖 C01/C04/C06 的决定。测试 Java JSON 往返、未知版本拒绝、精度/时间/null、规范 hash、门禁错误和终态条件；Node 对照测试在 Adapter 阶段复用同一向量。

**A2 实现任务日志和预算领取。** 在 agents 维护任务生命周期，API 持久化适配复用 RuntimeExecutionCheckpointPort/JdbcRuntimeEvidenceStore，集成现有执行/恢复设施。新增任务/操作命名空间、租约、fencing、预算预留结算和同合同重复提交。验收两个执行者竞争只启动一个意图、取消与完成竞态、租约交接不重置预算、已终态任务只读返回。CAS 如需多键原子性，采用单任务聚合记录或扩展事务适配，不假设多个 CAS 自动组成事务。

### P0 B Python Sandbox 控制与产物保全

**B1 补齐异步 MCP 执行控制。** 在现有 McpPythonControlPlanePort 增加具名请求的 start/get/cancel 操作及内部执行 DTO，更新 client、PythonAdminController、PythonCapabilityService、PythonExecution/Repository。内部执行协议可独立版本化，不暴露到 Coding Outcome。请求携带可信 scope、taskId、operationId、fencingToken、代码/输入指纹、截止时间和预算预留引用。保留旧同步方法签名；新方法不使用重复启动的默认降级。依赖 A1/A2。

启动前用短事务提交操作身份、预定容器名称和启动意图，再由后台执行器启动；运行期间不持有数据库长事务。start 响应丢失时，按 operationId 查询。验收启动前/后崩溃、重复 start、失效 fencing、超时重连及权限拒绝；请求重试不得产生第二个容器。

**B2 隔离输入与代码。** 修改 PythonDockerRuntime/PythonDataFileService 的 Coding 路径，使用 task/operation/attempt 工作区，只投影授权文件，固定 input/code hash 和解析后镜像 digest。所有同用户同资产任务也必须互相隔离；旧文件参数通过受控映射进入新工作区。依赖 B1。验收并发同名脚本不串改、同 owner 非授权文件不可见、输入原文件变化不改变快照、路径/符号链接拒绝。

**B3 取消、超时和资源停止。** 将 Docker CLI 生命周期与容器生命周期分开；取消先提交停止意图，再定向停止/强杀容器，inspect/wait 确认停止，记录退出原因、OOM、用量和确认时间。恢复处理已有停止请求，finally 清理失败形成可核对记录。依赖 B1/B2。真实容器测试使用会持续写入和产生子进程的脚本；终态后无新写入，取消/预算/完成竞态符合 C04。累计 CPU/内存等仅在执行器可强制时宣称硬限制。

**B4 输出 manifest 与保全。** 输出改为受限持久暂存，停止后固化允许的文件、日志及原始回执；执行器与客户端支持授权流式下载和校验，避免把文件 base64 塞进单个 JSON/byte[] 大响应。依赖 B2/B3。验收正常/零行/缺失/超限/损坏/超时残留结果、清理前后可读取、断点重试 hash 一致和无文件越界。输出未保全不得清理其唯一副本。

### P0 C 证据回流

**C1 CodingEvidenceCollector。** 在 agents 提供纯归集逻辑，integration 负责从 MCP 获取受控产物，API 适配既有证据持久化与资源读取。实现输入、代码、执行尝试、结果、验证、Bundle 六项引用的解析和 lineage。提取 DataWorkspaceOperations 可复用的结果登记职责，输出进入现有 SpillStore/DatasetHandle；使用 ComputationEvidence/StructuredDataEvidence，禁止生成虚构业务 Claim。依赖 B4/A2。

**C2 证据提交与恢复。** 实现 STAGED/VERIFIED/COMMITTED 检查点、引用发布、重复导入去重、授权资源读取与保留规则。先保存内容再发布引用；跨 API/MCP 不承诺分布式事务，靠幂等阶段与对账恢复。验收在下载、校验、Dataset 登记、Bundle 登记、快照发布各点崩溃后恢复，不产生悬空公开引用或重复业务执行。迟到核对只增加 revision，不修改已提交 manifest。

**C3 固定脚本闭环验收。** 使用授权金融 fixture，固定脚本输出结果与验证记录，由独立确定性验算器检查金额、收益和行数。分别覆盖正确数据、正常零行、业务校验失败、技术失败、取消与归档失败。验收六项引用按 scope 可解析、任务状态和 validationStatus 独立、完整结果可分页读取。依赖 C1/C2，**不调用任何模型或 Cline SDK**。

### P1 D Coding Agent 及 Analysis Loop

**D1 ClineCodingAdapter。** C3 通过后锁定 SDK 版本，在 integration 与 Node 服务实现私有会话映射、受控工具、订阅及用量转换。工具仅调用 B/C 能力，不直接取得 Docker socket、宿主命令或任意数据访问。关闭叠加重试，共用 Runtime budgetId；凭证注入沿既有受控链路，Node 对外回传公共契约。验收 Java/Node 同 hash、故障恢复、预算累计、取消贯穿模型和容器。

**D2 分析循环接入。** 在 ModelNativeAnalysisHarness 增加明确 Coding 操作，支持提交、读取状态和消费证据；结果未就绪时通过现有任务调度设施继续，不在模型轮次中无限轮询，也不假设现有 WAIT 已实现持久唤醒。发布仍由 Runtime 控制。复核 HarnessToolAccess 的只读限制，保持原资产发现/模板绑定边界，不开放通用 CALL_TOOL。

**D3 故障注入 PoC。** 实现字段类型、缺失交易日、重复记录、金额精度和收益对账五类独立/组合样例及未注入对照。编码目标明确允许的修复规则，验算器答案隔离；验证技术修复、异常检出、误报、预算、耗时及证据链。正常样例通过或单次 COMPLETED 不能替代该验收。

## 5 阶段门槛与首批提交

| 门槛 | 必须达到 | 允许进入 |
| --- | --- | --- |
| G0 | A1 契约、错误、状态竞态和规范 hash 定型；A2 执行所有权及预算验证通过 | B 系列联调 |
| G1 | B1–B4 真实 Docker 验收通过，取消可确认、输出可持久读取、未知结果不重放 | C 系列端到端验收 |
| G2 | C1–C3 固定脚本完整链路通过，所有引用可解析，崩溃恢复和跨租户拒绝通过 | D1 Cline 集成 |
| G3 | D1–D3 故障注入、预算与取消贯穿验证通过，Runtime 控制结果消费/发布 | 讨论生产试点 |

首批编码提交按 A1、A2、B1、B2、B3、B4、C1/C2、C3 顺序推进。C1 的纯解析/归集代码可基于 B4 manifest 样例提前实现，但真实链路验收不能跳过 G1。每个提交包含对应最小必要测试和迁移检查；没有真实 Docker 环境时明确标记 G1 未通过，不能用 mock 替代进程停止和产物持久性验证。

## 6 本轮审查范围

本轮完成公共契约与 Python 调用链的静态审查，并形成实施任务；未修改业务源码、运行 Sandbox 或执行故障注入。SDK API 和生产部署参数在 D1 实现前锁定核验。本轮不重新评估供应商，也不将契约设计冻结表述为可运行接口已交付。
