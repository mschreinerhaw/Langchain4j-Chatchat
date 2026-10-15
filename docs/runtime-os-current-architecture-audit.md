# Runtime OS 现有架构审查（多语言 Agent 执行架构改造评估 · T01）

日期：2026-10-15。性质：只读代码审查。本轮未修改任何 Java、Node、Python 实现。

配套文档：

- [目标架构](runtime-os-multilanguage-target-architecture.md)
- [可行性与方案对比](runtime-os-refactoring-feasibility.md)
- [分阶段路线](runtime-os-refactoring-roadmap.md)

## 0 审查方法与标记约定

- 证据来源：源码（类、方法、行号）、`pom.xml`、Compose/Dockerfile、迁移脚本，以及仓库中已有的阶段记录（A1/A2/B/C/D1、G0–G2）。阶段记录仅用于说明进度，结论以源码为准。
- 标记：**已实现**表示源码存在并有调用路径；**部分实现**表示代码存在但链路未贯通或默认关闭；**计划中**表示只出现在文档中；**不存在**表示源码中没有；**待验证**表示代码无法确认，需要运行环境或人工确认。
- 规模（`src/main` Java 文件数）：agents 508、mcp-server 435、common 363、chat 296、knowledge-base 227、api 168、runtime-news 122、enterprise 87、runtime-skill 63、integration 61、runtime-temporal 36。Node 端 `coding-agent-node/src` 共 3 个文件。Python 只有脚本和 Docker 镜像，没有常驻 Python Agent 服务。

## 1 总体结论（先给结论）

1. **Java 承担了全部 Agent 执行职责。** 规划、模型调用、工具选择、DAG 执行、自主分析循环、补证、答案评审都在同一个 JVM 中运行，集中在 `chatchat-agents` 的少数宿主类中：`InterpretationPlanRuntime` 9,370 行、`AgentOrchestrationEngine` 5,859 行、`ToolRuntimeService` 5,743 行、`AgentAnswerFinalizer` 1,854 行、`AgentPlanner` 1,830 行。治理决策与 Agent 内部决策在这些类中交织。仓库自身的 [职责审计](chatchat-agents-runtime-os-responsibility-audit.md) 也把它们登记为临时豁免的“migration host”。
2. **治理骨架已经比较完整，应当保留。** 已有的框架无关内核契约包括 `KernelDataScope`、`RuntimeOsKernel`、`McpRuntimeKernel`、`WorkflowRuntime`、`EvidenceStorePort`、`RuntimeExecutionCheckpointPort` 和 `RuntimeEvent`。它们由 `RuntimeOsArchitectureBoundaryTest` 中的约 20 条源码边界测试保护。`chatchat-common` 的 `src/main` 中没有任何 `dev.langchain4j` import。
3. **“执行引擎接入”抽象已有四套，而且彼此不连通。** 这是本次改造需要处理的首要结构问题（见 §6.1）。
4. **外部 Runtime 的雏形只有 Coding 链路。** A1 契约、A2 持久控制面、B 托管 Python 执行和 C 证据归集都已实现并有测试。D1 中 Node→Java 的工具回调只有模型相关端点，`execute-python`、`read-metadata`、`read-artifact` 尚未实现，端到端链路**未贯通**。
5. **MCP 受控取数链路是完整的单一入口。** 这个前提成立：Agent 侧所有 MCP 工具都经过 `ToolRuntimeService.execute`，再通过 gRPC `McpRuntimeKernel` 到达 `McpExecutionWorkflow`。但 API→MCP 的信任基于**一个共享内部凭据**，用户身份放在报文的 `context` 中，MCP 不再独立验证。外部 Runtime 绝不能接触该凭据或该信道（见 §4.4）。

## 2 Agent Loop 与调用链（T01-1/2）

### 2.1 Agent Chat 主调用链（已实现）

```text
POST /api/v1/interactions/chat/stream  (InteractionController.streamChat, chatchat-api)
  └─ mode=AGENT_CHAT → AgentTaskService.submit(...)                 [chatchat-chat，DB 任务队列]
       └─ AgentRuntimeTaskScheduler（@Scheduled 轮询 250ms / 租约恢复 1s）
            └─ AgentTaskService（约 1193 行）→ InteractionOrchestrationService.chat
                 └─ InteractionWorkflowCoordinator → AgentChatModeHandler
                      └─ AgentRuntime.run(runRequest)  (AgentChatModeHandler:419)
                           └─ DefaultAgentRuntime：先写 AgentRunStore，再启动 "agent-run-v1"
                                └─ WorkflowRuntime（默认 LocalWorkflowRuntime；workflow-engine=temporal 时用 TemporalWorkflowRuntime）
                                     └─ AgentRunExecutor = AgentOrchestrationEngine（唯一实现）
                                          ├─ AgentPlanner / NativeToolCallingPlanner → InterpretationPlan
                                          ├─ InterpretationPlanRuntime（DAG 状态机、检查点）
                                          ├─ AgentToolExecutor.execute → ToolRuntimeService.execute → McpRuntimeKernel
                                          └─ AnalysisCoverageCoordinator.analyzeWithHarness → ModelNativeAnalysisHarness
  SSE 回推：AgentTaskEventStreamService.stream（轮询 AgentEventStore，cursor=afterSequence）
```

- 工作流引擎二选一：`AgentRuntimeExecutorConfig`（`workflow-engine=local`，`matchIfMissing=true`）与 `TemporalWorkflowConfiguration`（`havingValue="temporal"`）互斥。`application-dev.yml`、`application-prod.yml` 均配置为 `local`，即生产默认不使用 Temporal。
- 第二个入口是 `AgentRuntimeController`（`/api/v1/agent/runtime`），配合 `AgentRuntimeEventStreamService`，以 `AgentRunEvent` 做 SSE。第三个入口是 `AgentAnalysisController`（`/api/v1/agent/analysis`），走联邦 Agent 路径（§6.1）。

### 2.2 循环清单

| 循环 | 位置 | 停止条件 | 状态 | 评价 |
| --- | --- | --- | --- | --- |
| 模型自主分析循环 | `ModelNativeAnalysisHarness.execute`（`for turn ≤ maximumTurns`，第 76 行；构造器限制 1–64，第 25 行） | 轮次、取消（`guard.run()`）、截止时间、模型完成意图 | 进程内；跨轮元数据存于 `Map` | 真正的“模型→操作→模型”循环。`CALL_TOOL` 分支拒绝外部工具派发，只能操作已交付数据 |
| 规划 DAG 执行 | `InterpretationPlanRuntime`（`DeterministicPlanDagStateMachine`、`PlanCheckpointCoordinator`） | DAG 终止、节点状态、预算 | 有检查点，可在 InterpretationPlan 粒度恢复（`ResumableAgentRunExecutor`） | 由平台决定执行，模型只提出计划 |
| 规划重试 | `AgentPlanner`（第 241 行 `for attempt ≤ maxAttempts`）+ `NativeToolCallingPlanner` | 尝试次数 | 进程内 | 模型原生函数调用只用于产生计划 |
| 补证循环 | `EvidenceCompletionLoop`（第 28 行）、`DefaultAnalysisWorkflowRuntime`（第 218、332 行）、`AnalysisLoopCoordinator.decide` | 证据充分度和轮次上限（`AdaptiveAnalysisController.boundedRecoveryRounds`） | 轮次状态写入 `JdbcRuntimeEvidenceStore.start/...`（第 327–417 行） | 平台治理型循环，不属于 Agent 自主循环 |
| Skill 引擎 | `GoogleAdkSkillRuntimeAdapter`、`LangChain4jSkillRuntimeAdapter`、`OpenAiCompatibleSkillRuntimeAdapter`（`chatchat-chat`） | 由各框架决定 | 进程内 | 第三方框架自带循环，经 `AgentRuntimeAdapter` 接入 |
| 联邦 Agent | `EvidenceBoundAgentExecution`（第 36 行，最多 2 次）、`DefaultAgentComputeRuntime` | `REPLAN_REQUIRED`/`SUPPLEMENT_EVIDENCE` 有界重入 | 进程内；A2A Task 可 resume | 远程推理型 Agent |
| Coding Loop | Node `runner.js`（`@cline/agents@0.0.93` 的 `Agent`） | SDK 内部；Java 端预算/取消 | Node 进程内 `Map`（`server.js:88`），**无持久化** | D1 部分实现 |
| Studio 计划 | `StudioCodePlanService`（`model.chat(prompt)` 第 91 行）、`PythonCodeAssistantService` | 单次调用 + 步骤遍历 | 进程内 | 一次性生成，不构成循环 |

**结论：** 没有两个功能完全相同的 Agent Loop。但存在“同类职责多处实现”：

- 模型自主分析（Harness）与规划型 DAG（InterpretationPlanRuntime）是两条并行的数据分析路径，入口选择由 `InteractionWorkflowCoordinator`/`WorkflowEntryPlan` 完成。仓库的 [入口审计](runtime-entry-architecture-audit.md) 已说明这一点。
- 证据充分度判断分布在 `AnalysisLoopCoordinator`、`EvidenceCompletionLoop`、`DefaultAnalysisWorkflowRuntime` 和 `JdbcRuntimeEvidenceStore` 的轮次状态中。

### 2.3 查询分析、规划、模型路由、工具选择

| 职责 | 实现 | 归属判断 |
| --- | --- | --- |
| 查询分析 | `AnalysisQueryAnalyzer`（SPI，common）/ `StandardAnalysisQueryAnalyzer`、`AnalysisWorkflowRouter`（common/runtime/analysis/routing）、`ProblemAnalysisPlanner`（chat） | 语义理解，可视为 Agent 内部能力 |
| 入口决策 | `InteractionWorkflowCoordinator`、`WorkflowEntryPlan`、`AgentToolPolicyResolver.planningSnapshot` | 平台路由（选择执行主体） |
| 规划 | `AgentPlanner`、`AgentPlannerPromptBuilder`、`PlannerEnvelopeParser`、`InterpretationPlanValidator`、`AgentPlanCandidateScorer` | 由模型提议、平台校验，属于混合职责 |
| 模型解析 | `ConfigurableChatModelFactory.create(modelName, timeout, maxTokens)` → `ModelResourceRegistry.require` → `ModelEndpoint.resolve`（DashScope native 或 OpenAI 兼容）；`AgentChatModelResolver` 加上 `CapacityGovernedChatModel`/`DeadlineAwareChatModel`/`MeteredChatModel` 装饰 | 凭据、配额、计量属于平台；协议适配属于 Provider |
| 工具选择 | `McpToolRouter`（`asset_discovery`/`template_discovery` 能力常量）、`AgentToolPolicyResolver` | 候选集合由平台授权，模型在集合内选择 |
| 工具执行 | `AgentToolExecutor.execute` → `ToolRuntimeService.execute(ToolRuntimeRequest)`（第 343 行）：Schema、租户策略、确认（`confirmationRequiredExecution`，第 1683 行）、限流、审计 → `executeThroughRuntimeKernel`（第 2367 行）把 `tenantId/userId` 写入 `context` | **平台网关**，必须保留在 Java |
| 参数绑定 | `SchemaDrivenTemplateParameterBinder`、`ModelAssistedContextParameterBridge` | 由模型辅助抽取、平台编译，绑定结果以平台为准 |

## 3 工作流与调度（T01-4）

| 机制 | 位置 | 用途 | 关系 |
| --- | --- | --- | --- |
| `WorkflowRuntime` SPI | common/runtime/workflow | Agent Run 的执行宿主 | Local 与 Temporal 互斥实现 |
| `LocalWorkflowRuntime` | agents/runtime/execution | 默认、生产使用 | 状态由 `AgentRunStore` 保存 |
| `TemporalWorkflowRuntime` 及 Activity | chatchat-runtime-temporal | 可选（真实 Temporal SDK） | Plan DAG / Tool Activity 适配器 |
| DB 任务队列 | `AgentTaskService` + `AgentRuntimeTaskScheduler`（chat） | 异步任务、claim/租约、配额对账 | 位于 `WorkflowRuntime` **之上**，与之叠加而非竞争 |
| 子工作流 / Staged | `AbstractStagedExecutionWorkflow`、`McpExecutionWorkflow`（`execute.mcp-tool.v1`）、SQL/API 模板工作流 | 单次 MCP 调用的生命周期 | MCP 节点内部 |
| News 调度 | chatchat-runtime-news | 新闻领域 | 独立领域，不涉及本次改造 |
| Coding 任务 | `DurableCodingTaskService`（claim/lease/fencing，检查点 `coding.tasks.v1`） | 外部 Coding 执行 | 复用 Evidence 检查点，**不进入** DB 任务队列；当前**没有全库 Coding 扫描 worker**（A2 文档 §7） |

**问题：** 一个 Agent Chat 请求在 Java 内经过“DB 任务 → Agent Run → Workflow → Plan DAG → MCP Workflow”五层状态，各层都有自己的状态字段（§5）。新增外部 Runtime 时如果再加一层自有状态，就会出现状态分裂。

## 4 MCP、数据访问与权限（T01-3，T03 前提）

### 4.1 受控取数链路（已实现）

```text
Agent 侧：ToolRuntimeService.execute（Schema/租户策略/确认/限流/审计）
  → executeThroughRuntimeKernel → GrpcBackedMcpRuntimeKernel.transport.invoke(call)
  → GrpcMcpRuntimeTransportClient（gzip，deadline，******
  ⇢ gRPC McpRuntimeService.Invoke（mcp_runtime.proto，JsonRequest → stream PayloadChunk，带分块 sha256）
  → McpGrpcAuthorizationInterceptor（常量时间比对内部凭据）
  → McpRuntimeGrpcService → kernel.execute → McpExecutionWorkflow（模板查找→绑定校验→契约预检→Provider→后检）
  → McpInvocationContext.openCall(call)（ThreadLocal）→ McpAuthorizationService.authorize(serviceId, toolName, arguments)
  → 模板执行：TemplateQueryMcpToolPublisher / TemplateQueryBindingService / ExecutionTargetRouter / DatabaseQueryInvokeService …
```

- 资产与模板发布：`templatepublication/*`（catalog、binding、publisher、policy、retrieval）和 `TemplateDiscoveryMcpToolPublisher`。参数校验：`TemplateParameterValidator`；准入规则见 [MCP 工作流执行架构 §4](runtime-os-workflow-execution-architecture.md)。
- 授权：`McpAuthorizationService` 使用从 API 同步的角色（`McpSynchronizedRole`），通过 `callerAllows`/`roleAllows`/`authorizedAssetDomains` 判断。
- 架构测试 `apiToMcpSouthboundTransportUsesVersionedStreamingGrpc`、`agentMcpExecutionUsesKernelInsteadOfTransportAdapter` 强制 Agent 只经 Kernel 访问 MCP。

### 4.2 SQL 执行面

- `sql_query_execute` 是通用 SQL 能力。按 [联邦计算文档](federated-agent-compute.md)，它是 `confirm_required`，不用于自动取证。`sql_template_analysis_execute` 只接受已发布并列入白名单的模板。
- SQL Studio（`SqlStudioController`，`/api/v1/data-science/sql`）**没有执行业务 SQL 的端点**。生成走 Agent 任务（`prepare`/`bind`），并由 `SqlStudioGenerationToolPolicy.resolve` 限定为只读元数据：拒绝 `DATA_FETCH`/`ACTION_EXECUTION`/`TEMPLATE_QUERY`，且要求声明 `executesBusinessSql=false`。
- `NativeQueryDatasource` 的凭据传递方式（HTTP Basic）属于 MCP 到外部数据源的连接方式，是否始终使用 HTTPS 尚**待验证**。它与本次改造无直接关系，仅登记。

### 4.3 Python Docker 执行（两条路径）

| 路径 | 代码 | 隔离参数 | 状态 |
| --- | --- | --- | --- |
| MCP 托管执行（Studio 测试/模板与 Coding） | `PythonDockerRuntime`（mcp-server）第 53–63 行 `docker create`：`--cpus/--memory/--pids-limit 256/--read-only/--cap-drop ALL/no-new-privileges/--user/--tmpfs /tmp`；挂载 `scripts:ro`、`input:ro`、`output:rw`；`NONE` 时 `--network none`，否则 `--network <env.networkName>`。第 161–170 行为模板 `docker run --rm` | 每个操作一个容器，镜像解析为内容 ID，fencing，归档 manifest | 已实现（B1–B4，G1） |
| API 本地 `PythonRuntimeService`（chatchat-api） | `provision` 创建**常驻**容器 `sh -c "while true; sleep"`，整个资产 workspace 以读写方式挂载；`networkEnabled` 时不加 `--network none`；`execute` 通过 `docker exec` 执行 | 无 `--read-only`，无按操作隔离 | 当前只有 `PythonDataScienceService.codeTool` 调用 `ruff`；`execute` 方法**没有调用方**（静态检索结果，反射调用待验证）。属于遗留的第二条 Docker 路径 |

Studio 脚本执行：`PythonDataScienceController.execute` → `PythonDataScienceService.testScript` → `McpPythonControlPlanePort.preview`（经 MCP）。模板执行同样经 MCP（`executeTemplate`）。

### 4.4 身份与信任边界

| 边界 | 机制 | 弱点 |
| --- | --- | --- |
| 浏览器 → API | 会话/Token，`ApiPermissionAuthorizationFilter` | — |
| API → MCP gRPC | `InternalCredentialProperties` 共享 Bearer；身份放在 `McpServiceCall.context`（tenantId/userId） | MCP 信任 API 提供的身份；`credentials.isEnabled()=false` 时拦截器**直接放行**（`McpGrpcAuthorizationInterceptor`）。持有该凭据即可冒充任意用户 |
| API → MCP Python 托管操作 | HTTP + `PythonManagedInternalAuthFilter`（只接受内部 Runtime 账户令牌） | 同上，预算引用不是签名能力凭证（B 文档残余风险） |
| Node → Java 回调 | `ClineCallbackAuthFilter`（HMAC-SHA256 + 时间戳 + nonce，来源地址白名单，默认 loopback） | 共享密钥同时用于 Java→Node 与 Node→Java；任务身份由报文携带，再由 Java 用 lease/fencing 和持久合同 hash 复核 |
| Node 容器网络 | `deploy/docker/coding-agent/compose.yml`：`coding_internal` **不是** `internal: true`（联调记录：internal 网络会阻断回调，因此移除）；`extra_hosts: host.docker.internal` | 容器可以访问宿主机 8080，并且**出站不受限**。如果宿主机暴露 DB/MCP 端口，容器在网络层可以连到。待补出站白名单 |

## 5 会话、事件、状态模型（T01-8）

### 5.1 状态枚举盘点

| 模型 | 位置 | 值 |
| --- | --- | --- |
| `AgentExecutionState` | chat/task/core | SUBMITTED…DELIVERING、SUCCEEDED、PARTIAL、FAILED、CANCELLED、REJECTED（14 个），另有 LEGACY 字符串映射（PENDING、RUNNING、CLAIMED…） |
| 任务字符串状态 | `AgentTaskService` 等 | `PENDING/RUNNING/WAIT_TOOL/WAIT_MODEL/WAIT_CONFIRMATION/WAITING_CONFIRM`… |
| `AgentRunStatus` | agents/runtime/run | PENDING、RUNNING、WAITING_CONFIRMATION、COMPLETED、FAILED、CANCELLED |
| `WorkflowExecutionStatus` | common/runtime/workflow | PENDING、RUNNING、COMPLETED、FAILED、CANCELLED |
| 节点生命周期 | 文档定义；代码中为字符串，迁移中 | PLANNED…SUCCEEDED/EMPTY/PARTIAL/BLOCKED/FAILED/TIMED_OUT/CANCELLED/NOT_EXECUTED |
| `AgentExecutionOutcome.Status` | common/runtime/agent | COMPLETED、PARTIAL、INPUT_REQUIRED、SUPPLEMENT_EVIDENCE、REPLAN_REQUIRED、BLOCKED、FAILED、TIMED_OUT、CANCELLED |
| `AnalysisExecutionOutcome`（v1 common / v2 agents） | 两处 | v2：NEEDS_REANALYSIS…EXECUTION_FAILED，外加 PhaseStatus 与 FailureCategory |
| `CodingTaskOutcome.Status` | common/runtime/coding | CREATED、RUNNING、COMPLETED、PARTIAL、FAILED、CANCELLED、BUDGET_EXCEEDED |
| Python 托管操作 | mcp-server | ACCEPTED、STARTING、RUNNING、UNKNOWN、SUCCEEDED、FAILED、TIMED_OUT、CANCELLED、FENCED |
| `RuntimeAgentExecutionResult.status` | runtime-skill | 自由字符串（`ENGINE_REQUIRED`、`ENGINE_NOT_REGISTERED`…） |

这些模型分属不同层级（任务、运行、节点、操作），**多数并非真正重复**。真正的问题有两个：(a) 字符串状态和 LEGACY 映射仍在使用；(b) 外部执行有三种终态语义：`AgentExecutionOutcome.Status`、`CodingTaskOutcome.Status`、`RuntimeAgentExecutionResult.status`，三者之间没有映射。

### 5.2 事件与持久化

| 存储 | 位置 | 事件类型 | 是否实现 `RuntimeEvent` |
| --- | --- | --- | --- |
| `AgentEventStore`（InMemory/Database/RocksDb） | chat/task/event | `AgentEvent`（任务级，UI SSE 来源） | **否** |
| `AgentRunStore extends RuntimeEventJournal<AgentRunEvent>`（InMemory/RocksDb；chat 中有 `DatabaseAgentRunStore`） | agents/runtime/store | `AgentRunEvent` | 是 |
| Coding 检查点 `coding.tasks.v1` | `JdbcRuntimeEvidenceStore` 分区 | `CodingExecutionEvent`（10 种，保留最近 128 条） | **否** |
| Evidence | `runtime_evidence_partition/entry` | `EvidenceRecord`/`EvidenceSnapshot`/`EvidenceLineage` | — |
| 会话记忆 | `ConversationMemoryService`（chat） | 消息快照 | — |
| 分析溢出 | `SpillStore`（RocksDB） | 临时 | — |
| Node 会话 | `server.js` 内的 `runs = new Map()` | 进程内 | — |

**问题：** 同一用户可见的执行过程有三条事件流：`AgentEvent`、`AgentRunEvent`、`CodingExecutionEvent`。其中两条没有接入通用的 `RuntimeEvent` 接口。Coding 事件目前没有投影到 Agent Chat 的 SSE。

## 6 架构问题清单（含证据）

### 6.1 四套互不相通的“执行引擎接入”抽象（高）

| 抽象 | 契约 | 实现 | 语义 |
| --- | --- | --- | --- |
| 联邦 Agent | `AgentProvider`、`AgentGatewayPort`（`runtime_os.agent_gateway.v1`）、`AgentComputeRuntimePort`、`LocalAgentExecutionPort`、`AgentRegistryPort` + `AgentDescriptor`（Origin、Protocol、TrustLevel、`DataAccessMode.RUNTIME_MANAGED`、`credentialRef`、`supportedExecutionModes`） | `DefaultAgentComputeRuntime`、`InMemoryAgentRegistry`、`JpaAgentRegistry`、`A2aHttpJsonAgentGateway`、`SkillCatalogAgentComputeAdapter` | 同步请求/响应，可 resume；只接受 Runtime 投影证据 |
| Skill 引擎 | `AgentRuntimeAdapter`/`ExternalAgentEngine`（runtime-skill），`DefaultAgentRuntimeDispatcher` 按 `engine` 精确分派 | LangChain4j / Google ADK / OpenAI 兼容 / External | 同步，结果是自由字符串状态 |
| Coding | `CodingAgentSPI`（`runtime_os.coding.v1`） | `DurableCodingTaskService`、`ClineCodingAdapter` | 异步、持久、租约、预算、事件回放 |
| 工作流宿主 | `WorkflowRuntime` | Local / Temporal | 平台内部执行宿主 |

前三者都在回答“把一段 Agent 执行交给某个引擎”这个问题，但注册、健康检查、能力声明、结果状态各自独立。`AgentDescriptor` 已有完整的注册和信任字段，`CodingAgentSPI` 却不使用；`AgentRuntimeAdapter.health` 与 `AgentDescriptor` 的健康准入也不相通。**这是新增 Runtime Provider 时最容易再造第五套抽象的位置。**

### 6.2 控制逻辑与执行逻辑耦合（高）

- `AgentOrchestrationEngine`（单一 `AgentRunExecutor` 实现）同时负责组合根、MCP 执行协调和数据集投影/归约。`InterpretationPlanRuntime` 同时负责 DAG 调度（平台）、检查点（平台）、模型重写（`InterpretationPlanRewriter` 使用 LangChain4j，Agent 内部）。`ToolRuntimeService` 中，平台网关与工具观测、批处理、修复逻辑交织。
- 因此 Java 内建分析链路**不能整体替换为外部 Runtime**，只能在已有端口（`AgentPlanningPort`、`AgentAnswerFinalizationPort`、Harness 的受控操作分派）处接入。

### 6.3 框架类型侵入（中）

- `dev.langchain4j` import 文件数：agents 42、chat 14、api 4、knowledge-base 4、common 0。涉及的不只是模型适配层，还包括 `AgentPlanner`、`AgentOrchestrationEngine`、`ModelNativeAnalysisHarness`、`AnalysisCoverageCoordinator`、`DataWorkspaceOperations`、`SchemaDrivenTemplateParameterBinder`、`AgentAnswerFinalizer` 等核心编排类，它们直接依赖 `ChatModel`/`ChatRequest`/`ToolSpecification`。
- 还有 langgraph4j（`AnalysisExecutionGraph`、`FindingAnalysisGraph`、`InterpretationAnalysisGraph`、`ClaimAcceptanceGraph`、`AnalysisState`）、Google ADK（chat/skills/runtime 4 个类）、`langchain4j-skills`（knowledge-base 2 个类）。
- 公共契约层（common）没有被侵入，已有架构测试保护（`commonCoreIsFrameworkNeutral…`、`skillRuntimeContractsAreFrameworkAndVendorNeutral`）。

### 6.4 硬编码（中）

- 能力和工具标识：`McpToolRouter.ASSET_DISCOVERY/TEMPLATE_DISCOVERY`、`AgentPlanner.DOCUMENT_SEARCH_TOOL`。`sql_query_execute` 在 mcp-server 中多处以字面量出现（`ExecutionTargetRouter:105/120`、`McpToolConcurrencyManager:417`、`StandardToolExecutionResultFactory:245/1233` 等）。
- 协议：`ModelEndpoint.resolve` 中的 `dashscope-*` 字面量。
- 轮次上限：Harness 的 64，A2 的 64 任务、256 操作、128 事件（适合首阶段，见 A2 §10）。
- Node 工具名 `python_execute`/`metadata_read`/`artifact_read` 写死在 `runner.js`，并直接成为合同 `allowedTools` 的取值，属于**供应商侧工具名进入合同取值空间**（见 §7）。
- `runtimeOsDoesNotHardCodeBusinessToolIdentities` 测试仅覆盖 agents 主源码中的业务工具标识，不覆盖 mcp-server。

### 6.5 第三方 SDK 扩展性（中）

- `AgentRuntimeAdapter.execute` 是同步接口，不支持事件流、取消、恢复，不适合接入 Claude Agent SDK、OpenAI Agents SDK 这类长时运行的流式 SDK。
- `AgentGatewayPort` 的 `cancel/resume` 默认抛出 `UnsupportedOperationException`。
- 只有 `CodingAgentSPI` 具备异步、订阅、取消、恢复语义，但它的输出契约绑定在“数据计算”上（§7）。

### 6.6 D1 链路未闭合（高，已知）

`ClineModelCallbackController` 只实现了 `reserve-model`、`model`、`settle-model`、`event`、`finish`。Node 声称会调用的 `execute-python`、`cancel-python`、`read-metadata`、`read-artifact` **不存在**。DatasetHandle→不可变输入快照的绑定**不存在**。Node 会话不持久化，Node 重启后由 Java 保守地不重放。

## 7 现有公共契约的已知缺陷（T06 输入）

| 缺陷 | 证据 | 影响 |
| --- | --- | --- |
| COMPLETED 要求六项引用齐全（含 `RESULT_DATASET`），`ResultFormat` 只有 `JSON_LINES` | `CodingTaskContract.RequiredEvidence`、`ResultFormat`；A1 §4 | 可以用于“数据计算型”任务。“代码工程型”任务（改文件、生成 SQL 工程、构建）无法合法地以 COMPLETED 结束，只能以 PARTIAL 加 limitations 结束 |
| 合同中没有任务种类/Runtime 档案字段 | `CodingTaskContract` 字段表 | 路由只能在合同外完成。这本身合理（合同保持供应商无关），但需要另有路由键 |
| `allowedTools` 是自由字符串，并直接使用 Node 侧工具名 | `runner.js`、`CodingTaskContract.Authorization` | 更换 SDK 或 Runtime 时，合同 hash 的取值空间随供应商变化。应改为平台工具能力名，再由 Adapter 映射 |
| 事件没有模型/工具调用级粒度，也没有面向用户的进度文本 | `CodingExecutionEvent.Type`（10 种） | 工具调用审计在 A2 操作账本中（MODEL/TOOL/CODE），可以满足审计；UI 进度需要另做投影 |
| 身份长度规则不一致 | A2 §10：JDBC 分区按 `String.length ≤ 128`，A1 按 Unicode scalar 计数 | 只影响极端字符，已登记 |
| 事件只保留 128 条 | A2 §6 | 长任务审计需要归档 |

## 8 前端与后端交互（T01-9）

| 页面 | 前端 | 后端 | 方式 |
| --- | --- | --- | --- |
| Agent Chat | `chatchat-api/web-app/src/views/ChatAssistantView.vue` | `InteractionController.streamChat` → 任务 SSE | SSE（DB 轮询） |
| Agent Runtime | `AgentRuntimeView.vue` | `AgentRuntimeController` + `AgentRuntimeEventStreamService` | SSE（`afterSequence` 游标，最长 30 分钟） |
| SQL Studio | `SqlStudioView.vue`、`services/sqlStudioApi.js` | `SqlStudioController`（项目/文件/生成；生成绑定 Agent 任务） | REST + 任务事件 |
| Python Studio | `DataScienceView.vue`、`services/api.js` | `PythonDataScienceController`（脚本、执行、发布、assist/plan） | REST，同步 |
| 旧 WebSocket | `ChatWebSocketHandler` | — | 已标记弃用（待验证是否仍有调用方） |

## 9 证据有效性边界

- 生产配置是否开启 `chatchat.coding.enabled`/`chatchat.coding.cline.enabled`、MCP 内部凭据是否在所有环境启用，均**待验证**（需要查看部署配置）。
- `PythonRuntimeService.execute` 是否完全无调用方，以静态检索为准，反射/脚本调用**待验证**。
- 真实 MySQL/PostgreSQL 下 A2 的并发语义**待验证**（A2 §10）。
- 本文未运行全量测试。[数据获取兼容性审查](runtime-os-coding-agent-data-acquisition-compatibility-review.md) 记录了 `ModelNativeAnalysisHarnessTest` 等测试存在过期桩导致的失败，这一状态本轮未复核。
