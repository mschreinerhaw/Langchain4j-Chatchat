# Runtime OS 多语言目标架构（T02/T03/T04/T05/T06）

日期：2026-10-15。本文是设计方案，不代表已经实现。事实依据见 [现有架构审查](runtime-os-current-architecture-audit.md)（下称“审查”）。

## 1 设计原则（对齐到本仓库）

1. **一个控制面，一套任务状态。** 外部 Runtime 的任务状态**直接复用** `runtime_os.coding.v1` 的七状态机和 `DurableCodingTaskService` 控制面，不新增第二套执行状态体系。
2. **按职责而非语言划分模块。** Java 内建的分析 Loop 保留为一种 Runtime Provider（“Java 内建 Provider”），不迁移。TypeScript/Python 只承接 Java 中**不存在或不适合**的执行能力：编码 Agent、长时代码/文件/构建工作、Python 数据科学生态。
3. **数据只经 Java 网关流动。** 外部 Runtime 不持有 MCP 内部凭据、数据库凭据或业务网络路由。取数只能通过 Java 的任务绑定回调，回调内部复用 `ToolRuntimeService.execute`。
4. **契约框架无关。** 公共契约中不出现 Cline、Claude、OpenAI、LangGraph 的类型或会话 ID。供应商差异由 Adapter（Node/Python 进程内）吸收。
5. **复用优先于新增抽象。** 注册与能力声明复用 `AgentDescriptor`/`AgentRegistryPort`；任务语义复用 `CodingAgentSPI`；证据复用 `EvidenceStorePort` 和 C 阶段归集器；事件对外接入 `RuntimeEvent`。

## 2 分层与职责边界

```text
┌──────────────────────────── Java Control Plane（chatchat-api / chat / agents / common / mcp-server）───────────────────────────┐
│ 身份与授权：ApiPermissionAuthorizationFilter、McpAuthorizationService、CodingTaskAccess/BoundCodingTaskAccess                     │
│ 资产与模板：templatepublication/*、TemplateParameterValidator、McpExecutionWorkflow（不变）                                     │
│ 编排与调度：AgentTaskService + AgentRuntimeTaskScheduler、WorkflowRuntime(Local/Temporal)、InterpretationPlanRuntime（DAG）      │
│ 执行控制：DurableCodingTaskService（任务/租约/fencing/预算/取消/事件）← 所有外部 Runtime 共用                                   │
│ Runtime 注册与路由：AgentRegistryPort + AgentDescriptor（扩展协议值）、Runtime Router（薄）                                     │
│ 受控工具网关：Runtime Callback Gateway → ToolRuntimeService.execute → McpRuntimeKernel（gRPC）                                   │
│ 模型网关：ClineRuntimeModelBridge 泛化 → ModelResourceRegistry / ConfigurableChatModelFactory（凭据只在 Java）                   │
│ 证据与验收：CodingEvidenceCollector、JdbcRuntimeEvidenceStore、ValidationStatus、发布治理                                        │
│ 内建 Provider：Java Analysis Loop（AgentOrchestrationEngine / ModelNativeAnalysisHarness）——保留                                 │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
          │ runtime_os.coding.v1（task/outcome/event/error JSON + 规范化哈希）  ▲ HMAC 签名回调（模型/工具/数据/事件/完成）
          ▼                                                                    │
┌──────── Agent Execution Plane ─────────────────────────────────────────────────────────────────────┐
│ Node Runtime（coding-agent-node）：SDK Adapter（Cline 首个；可替换为 Claude Agent SDK / OpenAI Agents SDK）│
│ Python Agent Runtime（新建，按需）：LangGraph / Pandas / SQLGlot 等 Python 原生 Agent                    │
│ Python Sandbox（已存在，MCP 托管）：PythonManagedExecutionService + PythonDockerRuntime                   │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

需要特别区分：**Python Sandbox 不是 Agent Runtime。** 它是受控工具（代码执行器），由 MCP 节点管理，Agent 只能经 Java 回调调用。“Python Agent Runtime”是一个新的、有自主循环的执行引擎，只有在出现明确需求时才建（见可行性文档的决策点 D-3）。

## 3 模块归属矩阵（T02）

| 能力 | 归属 | 依据 |
| --- | --- | --- |
| Agent Loop（数据分析） | **保留 Java 内建 Provider** | Harness 与 InterpretationPlan 已与授权数据集、证据、补证深度绑定（审查 §2）。迁移收益低、风险高 |
| Agent Loop（编码/文件/构建） | **下沉 TS Runtime** | Java 中不存在，相关 SDK 生态在 Node 侧 |
| Agent Loop（Python 原生 Agent） | **下沉 Python Runtime（按需）** | 仅在需要 LangGraph(Py) / Pandas 交互式推理时建设。纯代码执行继续使用 Sandbox 工具 |
| Planning（Agent 内部） | 随 Provider 归属 | Agent 内部的自主决策 |
| Planning（平台：入口选择、是否提交外部任务、DAG 校验） | **保留 Java** | `InteractionWorkflowCoordinator`、`InterpretationPlanValidator` |
| Model Routing | **分层**：Java 管模型资源、凭据、配额、计量、允许集合；Runtime 在允许集合内选择 | 见 §7 Q6 |
| Tool Calling | Runtime **发起**，Java **授权并执行**，执行者是 MCP/Sandbox | 见 §5 |
| MCP Gateway | **保留 Java**（`ToolRuntimeService` + `McpRuntimeKernel`） | 唯一的数据入口 |
| Workflow / 调度 | **保留 Java** | 外部任务作为工作流的一个节点或子任务 |
| Context Management | 会话上下文由 Java 持久化（`ConversationMemoryService`）；Runtime 内的 SDK 消息历史属于 Runtime 内部状态，不进入公共契约 | — |
| Memory（长期） | **保留 Java** | 涉及租户数据和权限 |
| Evidence Management | **保留 Java** | C 阶段归集器 + Evidence Store |
| Execution Outcome（权威） | **保留 Java**（`commitOutcome` 由账本重建，忽略提议方字段） | A2 §5 |
| 补证机制 | 分层：Runtime 只能**提出**结构化数据缺口；Java 决定是否重走资产发现/模板流程 | 兼容性审查“数据不足与控制权” |
| Data Access | **保留 Java/MCP** | T03 |
| Skill Management | **保留 Java**（runtime-skill、Skill catalog）；Skill 执行引擎可以登记为 Provider | — |
| Workspace（任务级文件） | 执行面持有临时副本，Java 持有权威归档与引用（B4 archive + Evidence） | — |
| 公共契约 | common（A1，不变）+ 新增 Provider 能力声明（复用 `AgentDescriptor`） | §4 |
| `AgentRuntimeAdapter`（Skill 引擎） | **暂不调整** | 同步 Skill 场景运行正常。远期并入注册表描述，不改接口 |
| `AgentGatewayPort`（A2A 联邦） | **暂不调整** | 面向远程推理型 Agent，语义与任务型 Runtime 不同 |
| API 本地 `PythonRuntimeService` | **收敛**：只保留 ruff，`provision/execute` 弃用 | 审查 §4.3 |

## 4 Runtime Provider 契约设计（T04）

### 4.1 三类“引擎”，三种契约，一个注册表

| 类别 | 契约 | 适用 |
| --- | --- | --- |
| 同步推理 Agent | `AgentGatewayPort` / `AgentProvider`（已有） | A2A、外部领域 Agent |
| 同步 Skill 引擎 | `AgentRuntimeAdapter`（已有） | LangChain4j/ADK Skill |
| **持久任务型 Runtime** | **`CodingAgentSPI` / `runtime_os.coding.v1`（已有）** | Node 编码 Agent、Python Agent、未来的 Claude/OpenAI SDK |

不新建 `RuntimeProviderSPI` 接口。在 Java 内，每个外部任务型 Runtime 是一个 `CodingAgentSPI` 实现。`ClineCodingAdapter` 是第一个；后续把它泛化为“HTTP 任务型 Runtime Adapter”，**协议无关于 SDK**（见 §4.4 的线协议）。

### 4.2 注册与能力声明

复用 `AgentDescriptor` + `AgentRegistryPort`（`JpaAgentRegistry` 已持久化），只做最小扩展：

- `Protocol` 枚举增加 `RUNTIME_TASK_V1`（表示 Java 以 `runtime_os.coding.v1` 线协议驱动该端点）。这是 Java 内部枚举，不影响 A1 wire。
- `capabilities` 使用已有的 `CapabilityId` 表达，例如 `CODE_EXECUTION`、`WORKSPACE_EDIT`、`DATA_COMPUTATION`。具体取值待决策。
- `metadata.supportedExecutionModes` 包含 `AGENTIC_EXECUTION`；`dataAccessMode` 固定为 `RUNTIME_MANAGED`（构造器已强制远程 Agent 必须如此）。
- `credentialRef` 指向回调签名密钥；构造器已拒绝在 metadata 中放置密钥。
- Runtime 健康端点返回声明：`protocolVersions`（如 `["runtime_os.coding.v1"]`）、`adapter`（如 `cline@0.0.93`，只用于审计）、`toolCapabilities`、`maxConcurrentTasks`、`resumeSupport`（`NONE`/`RECONCILE_ONLY`/`SNAPSHOT`）。Java 在路由前比对声明与 descriptor，不一致则拒绝路由。

### 4.3 路由与选择

- 路由键在合同**之外**：由 Java 在 submit 前根据 `capability + tenant policy + health + version` 选择 descriptor，并把所选 `providerId` 写入 A2 任务记录。这是 Java 侧的存储字段，不进入合同 hash。
- 不做隐式回退，与 `DefaultAgentRuntimeDispatcher` 的“exact dispatch, no implicit fallback”原则一致。同一任务在恢复时必须回到原 Provider，否则以 `ADAPTER_UNAVAILABLE` 失败。

### 4.4 线协议选择

| 机制 | 适用性 | 结论 |
| --- | --- | --- |
| HTTP/JSON（命令）+ 回调 | 已实现（`/v1/runs`、`/v1/runs/get`、`/v1/runs/cancel` 和 `/internal/coding/cline/*`）；与 A1 JSON Schema、HMAC 签名、Compose 部署一致 | **采用**作为 v1 |
| SSE（事件流） | 事件权威副本已存在于 Java 检查点，前端也已使用 SSE + `afterSequence`。Runtime→Java 的事件可以继续用签名 POST（`/event`），Java→前端用 SSE | **采用**于 Java→UI 方向；Runtime→Java 不需要长连接 |
| WebSocket | 双向，但需要额外的鉴权与重连协议，现有前端已弃用 WS | 不采用 |
| gRPC 流 | API→MCP 已在使用（`mcp_runtime.proto`）。Node/Python 也能用，但需要 proto 工具链，且与 A1 JSON Schema 重复 | v1 不采用。在吞吐和大载荷成为瓶颈后再评估。大文件通过 artifact 通道流式下载解决 |

**方向原则：** Java→Runtime 只发“开始/查询/取消”命令；Runtime→Java 发“请求授权动作（模型、工具、数据）”和“上报事件、提议结果”。权威状态只在 Java 端写入。

### 4.5 命令与回调清单（v1）

Java → Runtime（已有，泛化为非 Cline 专用路径）：`POST /v1/runs`（contract、contractHash、lease/fencing、callback 凭据引用）、`/v1/runs/get`、`/v1/runs/cancel`、`GET /healthz`（附加能力声明）。

Runtime → Java（统一前缀，建议 `/internal/runtime/{providerId}/…`；Cline 现有路径保留为别名）：

| 回调 | Java 处理 | 状态 |
| --- | --- | --- |
| `reserve-model` / `model` / `settle-model` | `ClineRuntimeModelBridge` + A2 `reserve/settle` | 已实现 |
| `tool`（统一工具调用） | 校验任务身份、lease/fencing、合同 `allowedTools` → 映射到平台工具 → `ToolRuntimeService.execute` 或 `CodingPythonExecutionBridge` | **新增**（替代 `execute-python`/`read-metadata`/`read-artifact`） |
| `tool-cancel` | `CodingPythonExecutionBridge.cancel` | 新增 |
| `event` | 只接受 Runtime 侧的观测事件，写入审计/进度投影，不改变权威状态 | 已实现（需收紧） |
| `finish`（提议结果） | `commitOutcome` 按账本重建；证据经 `CodingEvidenceCollector` | 部分实现 |

### 4.6 会话、恢复、幂等

| 状态 | 持久化方 | 说明 |
| --- | --- | --- |
| 任务合同、hash、状态、revision、事件序号 | Java（`coding.tasks.v1` 检查点） | 权威 |
| Lease/fencing、操作账本（intent、预留、句柄、停止证明） | Java | 权威。每个操作有 `operationId`，执行节点去重 |
| 预算累计 | Java | 权威 |
| 证据、产物（代码、输入、结果、校验、回执、bundle） | Java Evidence + MCP 归档 | 权威 |
| SDK 会话、消息历史、计划草稿、中间推理 | Runtime 内部 | 非权威，可丢失 |
| SDK 会话快照（可选） | Runtime 写入 Java 提供的 artifact 通道，由 Java 记录引用 | 计划中，`resumeSupport=SNAPSHOT` 才需要 |

恢复语义（沿用 A2，不新增）：Runtime 或容器重启后，Java 查询发现未知 → 任务标记 `reconciliationRequired`，不重放已启动的代码执行；只有在停止证明成立后才能结束或以新 lease 继续。“继续推理”只在 `resumeSupport=SNAPSHOT` 时允许，并消耗同一个 budgetId。

### 4.7 取消与超时

链路：Java `cancel` 持久化停止意图 → Runtime `/v1/runs/cancel`（中断 SDK 和模型请求）→ Java 对所有已派发操作调用 `CodingPythonExecutionBridge.cancel` → 收到 Sandbox 停止证明 → `CANCELLED`。截止时间由 Java 在 claim、预留、启动和终态路径上复核（A2 §4）。Runtime 本地超时只是加速手段，不作为停止证据。

### 4.8 错误契约与版本

- 错误使用 `CodingErrorCode` 17 个值 + `CodingContractException`，不增加供应商错误码。供应商错误映射为 `EXECUTION_FAILED`/`ADAPTER_UNAVAILABLE`，原始信息放入审计 artifact。
- 版本：沿用 A1 规则（v1 只增加可选字段；必填或语义变化需升主版本）。Runtime 在 `healthz` 中声明支持的主版本，Java 拒绝未知版本。

### 4.9 Artifact 交付

- 代码与数据产物：沿用 B4（`output/results|validation|logs` → archive → manifest → 授权通道读取）。
- 编码工程型产物（文件 diff、工程文件）：v1 中**没有**合法的 COMPLETED 表达（审查 §7）。最小修复见 §6。

## 5 数据访问与安全边界（T03）

### 5.1 不变量

1. 资产、模板、参数 Schema、授权决定只在 Java/MCP 中定义和存储。合同只携带**引用**（`allowedDataRefs`、`metadataRefs`、`inputSnapshotRef`、`decisionRef`）。
2. Agent 可以在 `allowedTools`（平台工具能力名）范围内**选择**取数操作。每次调用都由 Java 用**当前**权限复核（权限收紧立即生效）。
3. 合同 hash 固定授权上限；扩权必须创建新任务（A1/baseline）。
4. 外部 Runtime 不存在任何直连 DB、MCP gRPC 或 MCP Python 管理端点的路径（网络和凭据两方面）。
5. 模型产生的文本不能登记为数据集，只有经 C 阶段验证的结果才能成为 `DatasetHandle`。

### 5.2 身份绑定与验证

```text
用户请求 → Java 鉴权（tenant/user） → A2 submit：scope 来自可信身份（BoundCodingTaskAccess）
  → claim：lease(owner, fencingToken)
  → /v1/runs：contract + contractHash + taskKey + fencingToken（HMAC 签名）
Runtime 回调：HMAC(timestamp, nonce, body) + taskKey + contractHash + fencingToken
  → ClineCallbackAuthFilter（签名、重放、来源地址）
  → Java 读取持久任务：hash 一致、lease 有效、fencing 匹配、未停止
  → 以任务 scope 构造 ToolRuntimeRequest（tenantId/userId 来自任务记录，不取自报文）
  → ToolRuntimeService.execute（含确认、限流、审计）→ McpRuntimeKernel → MCP 授权复核
  → 结果登记为 Evidence，返回给 Runtime 的是引用或受限投影
```

关键点：**Runtime 报文中的身份字段只用于定位任务，不作为授权依据。** 授权依据是 Java 持久化的任务 scope 加上当前权限。

### 5.3 容器绕过防护（需新增的控制项）

| 绕过面 | 现状 | 要求 |
| --- | --- | --- |
| 凭据 | Node 只有回调共享密钥；Sandbox 无凭据（审查 §4.3） | Runtime 容器禁止注入 MCP 内部凭据、DB 凭据、模型 API Key（ClineRuntimeModelBridge 已让 Key 留在 Java）。为每个 Provider 使用独立密钥（`credentialRef`），不与 MCP 内部凭据共用 |
| 网络 | `coding_internal` 非 internal，出站不受限（审查 §4.4） | 出站只允许访问 Java 回调地址（iptables/egress 代理或专用网络 + 网关容器）。禁止路由到 DB、MCP gRPC、MCP HTTP 端口。Sandbox 继续 `--network none` |
| 文件挂载 | Node 只读根目录 + tmpfs；Sandbox 按操作挂载 | 保持现状。禁止挂载 uploads 根目录和 Docker Socket |
| 内部信道 | MCP gRPC 拦截器在凭据未启用时放行 | 生产启用外部 Runtime 前，强制启用 MCP 内部凭据（启动校验），并确认 MCP 端口不暴露到 Runtime 网络 |
| Docker 权限 | MCP 节点调用 docker CLI（具备宿主 root 等效权限） | 外部 Runtime 不得获得 Docker 访问。Sandbox 的创建只由 MCP 节点执行 |

## 6 公共契约兼容性（T06）

| 契约要素 | 能否直接复用 | 说明 |
| --- | --- | --- |
| 七状态 + `validateSuccessor` | **是** | 适用于所有任务型 Runtime |
| 停止条件（`ExecutionControl`、不可撤销停止意图、停止证明） | **是** | — |
| `CodingErrorCode` | **是** | 已足够通用 |
| 证据绑定（六项引用、`validateAgainst`） | **对数据计算型是；对代码工程型否** | COMPLETED 要求六项引用和 JSONL 结果 |
| `CodingTaskOutcome` | 是（同上） | — |
| JSON Schema（`coding.schema.json`） | 是 | 新的 Runtime 必须使用同一个 bundle 做入口校验 |
| Java/Node 规范化哈希 | 是 | Python Runtime 需要第三份实现，并对照同一组测试向量（`canonical-vector.json`） |
| `CodingExecutionEvent` | 是 | UI 进度需要通过 `RuntimeEvent` 投影，不扩充公共事件 |
| `AgentExecutionOutcome`（联邦） | 不合并 | 用于同步推理，属于另一层 |

**最小修复建议（需要决策，见可行性 D-2）：**

1. **工具名去供应商化（v1 兼容）：** 规定 `allowedTools` 取值为平台工具能力名（例如 `runtime.python.execute`、`runtime.metadata.read`、`runtime.artifact.read`），Node Adapter 内部再映射为 SDK 工具名。这是取值约定，不是 Schema 变化。由于 D1 尚未上线，现在调整没有存量数据迁移成本。
2. **代码工程型交付（需要 v2 或新的 OutputContract 枚举值）：** 增加 `ResultFormat`/`RequiredEvidence` 的新取值（如 `WORKSPACE_PATCH`）会改变 COMPLETED 的验证语义。按 A1 规则必须升主版本，或作为 `runtime_os.coding.v1` 下的独立 profile 先在 Java 端拒绝。**在出现真实的工程型需求之前，不实施这一项。** 当前 SQL Studio 生成仍走 Agent 任务和文件 apply 流程。

## 7 核心架构问题的明确回答

1. **Java 是否承担过多 Agent 执行职责？** 是，但只限于“数据分析”这一类，而这正是平台的核心价值所在。问题不在 Java 执行了 Agent，而在于治理和执行混在少数宿主类中（审查 §6.2）。编码类执行**确实**不应放在 Java 中，目前也没有放在 Java 中。
2. **哪些 Java 模块属于不必要的重复？** 四套引擎接入抽象之间缺少统一注册（审查 §6.1）；API 本地 `PythonRuntimeService.provision/execute` 是 Docker 路径的重复（审查 §4.3）；三条执行事件流没有统一投影（审查 §5.2）；证据充分度判断有多处实现。规划和执行 Loop **不算重复**。
3. **哪些 Java Agent 能力应保留？** Harness 自主分析、InterpretationPlan DAG、补证循环、答案评审、联邦 Agent 调度、Skill 引擎适配、A2 控制面、B/C 托管执行与证据归集。
4. **是否需要单独抽象 Runtime Provider？** 需要“注册 + 路由 + 统一回调网关”。**不需要**新的执行 SPI：`CodingAgentSPI` 就是任务型 Provider SPI，`AgentDescriptor` 就是注册描述。
5. **工作流与 Agent Loop 如何分离？** 工作流（Java）决定是否启动外部任务、提供什么输入和授权、预算多少、结果是否进入下一节点。外部 Agent Loop 只在合同内自主运行。外部任务在工作流中表现为一个节点，其终态按合同映射：COMPLETED/PARTIAL → SUCCEEDED/PARTIAL，BUDGET_EXCEEDED → BLOCKED，其余同名映射。映射表放在 Java 投影层，不修改任何一方的状态机。
6. **模型路由放在哪里？** 分层。Java 负责模型资源、凭据、配额、计量、允许集合和默认模型（`ModelResourceRegistry`、`MeteredChatModel`、A2 预算）；Runtime 只能在合同允许集合内按步骤选择（例如便宜模型做规划、强模型做修复）。Runtime **不直连**模型供应商，所有调用都经过 `/model` 回调。
7. **工具调用由谁发起、谁授权、谁执行？** 发起方是 Runtime 中的模型；授权方是 Java（合同上限 + 当前权限 + 确认 + 预算）；执行方是 MCP 节点（模板/取数）或 MCP Python Sandbox（代码）。Runtime 本地只允许无副作用的 SDK 内部操作，例如它自己的 tmpfs 草稿。
8. **Evidence Bundle 和结果治理位于哪一层？** Java 控制面（C 阶段归集器 + Evidence Store + `commitOutcome`）。Runtime 只**提议**结果。
9. **如何确保 Python/Node 无法绕过授权？** 依靠 §5 的五条不变量和 §5.3 的控制项。核心是让它们在网络和凭据层面**到不了**数据源，而不是依赖代码审查。
10. **A1/A2/B/C 成果能否复用？** 可以全部复用。A1 需要做取值约定（工具名）；工程型交付留待 v2。D1 继续推进，并作为第一个 Provider 实例。
11. **是否需要把 LangChain4j 从公共 Runtime 核心中解耦？** common 已经解耦。agents 核心编排类对 `ChatModel` 的直接依赖**不是**本次多语言改造的前置条件，因为外部 Runtime 通过 Java 模型网关调用模型，不受影响。建议仅在模型网关处（`ClineRuntimeModelBridge` 泛化）引入框架无关的模型请求 DTO，避免 LangChain4j 类型进入回调协议。全面解耦 agents 不在本次范围内。
12. **改造能否降低长期复杂度？** 对编码类和 Python 原生 Agent 能降低复杂度：避免在 Java 中重写 SDK 能力，而且这些执行本来就必须远程隔离。对数据分析主链路，如果迁移则只会增加远程调用，**因此不迁移**。净效果取决于是否严格做到“一套任务状态、一个注册表、一个回调网关”。如果做不到，就只是多了一层。

## 8 关键时序

### 8.1 提交与执行

```text
Java Workflow/Analysis Loop     A2 DurableCodingTaskService     Runtime Router     Node Runtime        Java Callback GW       MCP
  │ submit(contract) ─────────────▶ 持久 CREATED + TASK_CREATED
  │                                  claim(lease, fencing)
  │                                  ──────────────── select(provider) ─▶
  │                                  ────────────────────────────── POST /v1/runs ─▶ 启动 SDK
  │                                                                                │ model → reserve/model/settle ─▶ A2 预算
  │                                                                                │ tool(python.execute) ─▶ 校验身份/fencing/授权
  │                                                                                │                        ─ startIntent ─▶ A2
  │                                                                                │                        ─ CodingPythonExecutionBridge.start ─▶ MCP Sandbox
  │                                                                                │ event ─▶ 审计/进度投影
  │                                                                                │ finish(proposal) ─▶ 证据归集 → commitOutcome
  │ ◀─ subscribe(afterSequence)：TASK_FINISHED（权威）
```

### 8.2 受控取数（Runtime 需要更多数据）

```text
Runtime 模型判断缺数据 → finish(PARTIAL, missingEvidence=[结构化缺口]) 或 event(DATA_GAP)
Java Analysis Loop 读取缺口 → 走原有资产发现/授权模板/参数绑定/执行（不经 Runtime）
→ 新结果 DatasetHandle → 新的不可变输入快照 → 新任务（新合同 hash）
```

v1 **不允许**正在运行的 Runtime 任务自行触发模板执行，以免运行中扩权。若业务要求“运行中按授权模板取数”，可以把**已授权的模板工具**列入合同 `allowedTools`，并经统一 `tool` 回调由 `ToolRuntimeService` 执行。前提是授权决定（`decisionRef`）在提交时已覆盖这些模板，且参数仍由 MCP 校验。是否开放见可行性 D-4。

### 8.3 取消

```text
用户/预算/截止 → A2 cancel（持久停止意图）→ Router → Node /v1/runs/cancel（abort）
→ 对每个已派发 operation：Bridge.cancel → MCP docker stop + inspect → 停止回执
→ McpPythonExecutionProofVerifier → A2 结算 → CANCELLED（否则保持 RUNNING + reconciliationRequired）
```

## 9 第三方框架接入方式（T05）

| 框架 | 运行位置 | 接入方式 | 差异与风险 |
| --- | --- | --- | --- |
| Cline `@cline/agents` | Node | 已有 `runner.js` + `runtime-model.js`；SDK 工具重试关闭 | 实验性版本 0.0.93，存在依赖告警 |
| Claude Agent SDK（TS/Py） | Node 或 Python | 同一个 `/v1/runs` 协议。模型通过自定义 provider/代理指向 Java `/model`；必须禁用内置 Bash/文件写工具，或把它们映射到受控工具 | SDK 默认偏向直连 Anthropic API 并使用本地工具，需要验证能否完全替换为回调（**待验证**） |
| OpenAI Agents SDK（TS/Py） | Node 或 Python | `Model` 接口可以自定义为 Java 回调；工具定义为回调 function | handoff/guardrail 只在 Runtime 内部使用，对外不暴露 |
| LangGraph（Python） | Python Agent Runtime | 节点内的 LLM 调用和工具调用都走回调；checkpointer 可选写入 Java artifact 通道 | 与 Java 侧 langgraph4j 无关，不共享状态 |
| Google ADK / LangChain4j Skill | Java（已有） | 继续使用 `AgentRuntimeAdapter` | 同步语义 |

**统一适配是可行的。** 所有框架都可以归约为“模型回调 + 工具回调 + 事件上报 + 提议结果”。差异只在 Adapter 内部：工具声明格式、流式事件、内置工具禁用、会话快照。前提是框架允许替换模型传输层并禁用内置的宿主工具。这需要按框架逐一做 PoC 验证，没有通过的框架不接入。
