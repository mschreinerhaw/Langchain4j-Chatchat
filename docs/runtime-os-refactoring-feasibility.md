# Runtime OS 多语言改造可行性评估（方案对比、风险、迁移矩阵、工作量）

日期：2026-10-15。依据：[现有架构审查](runtime-os-current-architecture-audit.md)、[目标架构](runtime-os-multilanguage-target-architecture.md)。本文只做评估，不改代码。

## 1 方案定义

| 方案 | 内容 |
| --- | --- |
| **A：保留 Java Runtime，局部接入** | 只完成 D1 的 Cline 链路，Node/Python 作为点状集成；不建注册表、不统一回调、不处理四套接入抽象 |
| **B：Java Control Plane + 多语言 Runtime Provider（渐进）** | 以 A2 控制面 + `runtime_os.coding.v1` 作为唯一的任务型 Provider 契约；用 `AgentDescriptor`/`AgentRegistryPort` 做注册和能力声明；建立统一回调网关（复用 `ToolRuntimeService`）；Java 分析 Loop 作为内建 Provider 保留不迁移 |
| **C：独立 Agent Execution Platform** | 把执行面拆成独立集群/服务，供多个应用共享，自带调度、状态、多租户和计量 |

## 2 对比

| 维度 | A | B（推荐） | C |
| --- | --- | --- | --- |
| 与现有架构兼容性 | 高，但会把 Cline 专用路径固化（`/internal/coding/cline/*`） | 高：复用 A1/A2/B/C、`ToolRuntimeService`、`AgentDescriptor`；不改分析主链路 | 低：需要把 A2 控制面、预算、证据迁出或复制，与“一套状态”原则冲突 |
| 开发/迁移工作量 | 低（D1 剩余约 3–5 人周） | 中（D1 + 约 8–12 人周，见 §6） | 高（6 个月以上，含平台化运维） |
| 现有代码复用率 | 高 | 高（契约、控制面、Sandbox、证据全部复用） | 低到中 |
| 新框架接入成本 | 高：每个 SDK 都会复制一套回调和路由 | 低：新增一个 Adapter 进程 + 一条 descriptor | 低（建成之后） |
| 运维复杂度 | 低到中 | 中：多一个 Node 服务（已部署），可选 Python 服务 | 高：独立集群、跨应用租户、独立数据库 |
| 企业权限治理 | 中：每个集成点各自鉴权，容易出现缺口 | 高：单一回调网关 + 任务绑定身份 + 统一审计 | 中：权限要跨平台同步，回调链路更长 |
| 故障隔离与恢复 | 中 | 高：执行面崩溃不影响 Java 权威状态，恢复语义沿用 A2 | 高 |
| 未来扩展空间 | 低 | 中到高（日后可演进到 C） | 高 |
| 长期维护成本 | 中到高（重复集成） | 中 | 高（团队与运维投入） |

### 推荐：方案 B（渐进式）

依据：

1. 方案 B 的核心部件都**已经存在**：契约（A1）、持久控制面（A2）、托管执行（B）、证据（C）、注册描述（`AgentDescriptor`）、受控工具网关（`ToolRuntimeService`）。B 主要是**接线和收敛**，不是新建。
2. 它能解决审查中最主要的结构风险，即四套接入抽象和重复回调（审查 §6.1），而且不需要迁移任何正在生产运行的分析链路。
3. 每一步都可以独立开关和回滚：A2 默认关闭，Cline 有独立开关。

不选 A：A 在接入第二个 SDK 时就会复制 Cline 专用回调、授权和事件转换，形成“每个 SDK 一个 Runtime”的分叉。A 也不处理容器出站、内部凭据等安全前提。

不选 C：当前只有一个外部执行引擎，而且 D1 尚未验收。为多应用共享而平台化缺少需求证据。C 还要求把预算、证据、租约迁出 Java，或者在两处维护，这直接违背“不允许两套执行状态”的约束。B 的契约和注册表设计不妨碍日后演进到 C。

## 3 技术可行性要点

| 问题 | 可行性 | 依据/条件 |
| --- | --- | --- |
| 用 `CodingAgentSPI` 承载非 Cline Runtime | 可行 | SPI 与合同不含供应商字段；`ClineCodingAdapter` 的 HTTP 调用只是 `/v1/runs*` |
| 用 `AgentDescriptor` 注册任务型 Runtime | 可行，需要小改 | 增加 `Protocol` 枚举值；`CapabilityId` 支持任意命名空间；构造器已强制远程 Agent 使用 HTTPS（loopback 例外）和 `RUNTIME_MANAGED` |
| 统一回调复用 `ToolRuntimeService.execute` | 可行，需要设计 | 需要用任务 scope 构造 `ToolRuntimeRequest`，并为外部任务定义确认策略（`ASK_BEFORE_EXECUTE` 在无人值守任务中应转为 BLOCKED，不能等待） |
| Python Agent Runtime 复用契约 | 可行 | 需要第三份规范化哈希实现 + 同一组测试向量；Schema 校验使用 Python `jsonschema` draft 2020-12 |
| 代码工程型交付 | **v1 不可行** | COMPLETED 的六项引用要求（审查 §7）。需要 v2 或独立 profile |
| Node SDK 会话恢复 | 部分可行 | v1 只支持对账（RECONCILE_ONLY）；快照恢复取决于各 SDK 的能力（待验证） |
| 第三方 SDK 替换模型传输 | 待验证 | Cline 已验证（确定性模型）；Claude/OpenAI SDK 需要各自 PoC |

## 4 风险评估（T07）

| # | 风险 | 严重程度 | 发生条件 | 影响范围 | 建议措施 |
| --- | --- | --- | --- | --- | --- |
| R1 | 多语言运维复杂度 | 中 | 新增 Node（已有）、Python 服务；离线镜像（联调记录：Docker Hub 不可达） | 部署、升级、监控 | 每种 Runtime 一个镜像，固定 LTS 基础镜像，离线哈希校验沿用 D1 做法；健康检查纳入现有监控；Python Agent 只在需求确认后引入 |
| R2 | Java 与 Node/Python 状态不同步 | 高 | Runtime 自行维护任务状态并对外报告 | 用户看到的结果与权威状态冲突 | 权威状态只由 Java 写入；`event` 回调只作观测；`commitOutcome` 由账本重建（A2 已实现）；UI 只读 Java 投影 |
| R3 | 模型工具调用越权 | **严重** | 合同 `allowedTools` 过宽；回调使用报文中的身份；容器能直连数据源 | 数据泄露 | 任务绑定身份（目标架构 §5.2）；平台工具能力名；每次调用复核当前权限；容器出站白名单；MCP 内部凭据强制启用 |
| R4 | 分布式取消/重试/重复执行 | 高 | 网络超时后重发、Runtime 重启、SDK 内置重试 | 重复执行代码、重复计费 | `operationId` 去重（B1 节点已实现）；SDK 重试关闭（D1 已做）；未知结果进入 `reconciliationRequired` 且不重放（A2） |
| R5 | 流式事件乱序与断线 | 中 | 回调并发、Node 重启、SSE 断开 | UI 显示错乱 | 权威序号来自 Java 检查点；前端按 `afterSequence` 重连（已有）；Runtime 事件只作为观测，不参与排序 |
| R6 | 长任务与容器生命周期 | 中 | 任务时长超过租约（最长 5 分钟，需续租）、Node 重建 | 任务卡在 RUNNING | 续租心跳；deadline 复核需要接入现有调度器（A2 §4 待接入）；未知状态由人工或自动对账 |
| R7 | 工作流状态与 Agent 内部状态不一致 | 中 | 工作流节点超时而外部任务仍在运行 | 迟到结果覆盖 | 工作流节点超时 → A2 cancel（持久停止意图）；迟到完成按 A2 规则不能覆盖已接受的停止 |
| R8 | 跨 Runtime 日志关联 | 中 | 三条事件流 + Node 日志 + MCP 审计 | 故障定位困难 | 统一关联键：`tenant/user/run/task/operationId` + `requestId`；回调与 MCP 审计记录同一 `operationId`；Coding 事件投影到 `RuntimeEvent` |
| R9 | SDK 升级破坏 Adapter | 高 | `@cline/agents` 为 0.x 实验版本 | Node Runtime 不可用 | 锁定版本；Adapter 契约测试（确定性模型）作为升级门禁；Provider 声明 `adapter` 版本；失败时不路由（无隐式回退） |
| R10 | 现有业务回归 | 中 | 改动 `ToolRuntimeService`、`AgentDescriptor`、Harness 分派点 | Agent Chat、SQL/Python Studio | 分析主链路不改；改动点限于新增代码路径和开关；按仓库 Release gates 运行全量测试；Harness 过期测试桩需要先修复（兼容性审查定向测试结果） |
| R11 | 内部凭据被禁用时 MCP 放行 | 高 | `InternalCredentialProperties.enabled=false` | 网络可达者可以冒充 API | 启用任何外部 Runtime 前，加启动校验并做网络隔离（路线图 P0） |
| R12 | 遗留 Docker 路径 | 中 | `PythonRuntimeService.provision/execute` 被重新启用 | 隔离性弱于 B 阶段 | 弃用并限制为只用于 ruff |

## 5 模块迁移矩阵

| 模块/类 | 当前位置 | 决策 | 动作 |
| --- | --- | --- | --- |
| `CodingAgentSPI`、`CodingTaskContract/Outcome/Event`、`coding.schema.json`、`CodingCanonicalHash` | common | 保留，定位为任务型 Provider 公共契约 | 文档更名定位；工具名取值约定 |
| `DurableCodingTaskService`、`CodingJournal`、`CodingBudgetMath`、`CodingTaskAccess` | agents | 保留（Java 控制面） | 增加 `providerId` 存储字段（不进 hash）；接入调度器做全库扫描和截止检查 |
| `ClineCodingAdapter` | api | 泛化为 HTTP 任务型 Runtime Adapter | 去除 Cline 命名依赖；按 descriptor 实例化 |
| `ClineModelCallbackController`、`ClineCallbackAuthFilter`、`ClineRuntimeModelBridge`、`ClineModelReceiptStore` | api | 泛化为 Runtime Callback Gateway | 统一前缀和 `tool` 回调；Cline 路径保留为别名 |
| `CodingPythonExecutionBridge`、`McpPythonExecutionProofVerifier`、`CodingEvidenceCollector` | api | 保留 | 作为统一 `tool` 回调的执行后端 |
| `PythonManagedExecutionService`、`PythonDockerRuntime`、`PythonOperationWorkspace` | mcp-server | 保留（Sandbox） | 不变 |
| `ToolRuntimeService` | agents | 保留（网关） | 增加“外部任务调用方”请求构造；确认策略转 BLOCKED |
| `AgentDescriptor`/`AgentRegistryPort`/`JpaAgentRegistry` | common/enterprise | 保留并扩展 | 增加 `Protocol.RUNTIME_TASK_V1` |
| `AgentGatewayPort`/`A2aHttpJsonAgentGateway`/`DefaultAgentComputeRuntime` | common/integration/agents | 暂不调整 | — |
| `AgentRuntimeAdapter` 及其 4 个实现 | runtime-skill/chat | 暂不调整 | 远期把 engine 登记为 descriptor |
| `AgentOrchestrationEngine`、`InterpretationPlanRuntime`、`AgentPlanner`、`AgentAnswerFinalizer` | agents | 保留为 Java 内建 Provider，暂不调整 | 继续执行仓库已有的 ratchet 迁移，与本改造无依赖 |
| `ModelNativeAnalysisHarness`、`AnalysisCoverageCoordinator` | agents | 保留 | D2：在受控操作分派处增加“提交外部任务”意图（已有计划） |
| `AgentEventStore`/`AgentRunStore`/Coding 事件 | chat/agents | 不合并存储 | 增加 Coding 事件 → `AgentEvent`/`RuntimeEvent` 的只读投影 |
| API `PythonRuntimeService` | api | 收敛 | `provision/execute` 弃用 |
| `coding-agent-node` | Node | 保留并扩展 | 统一 `tool` 回调；工具名映射；`healthz` 能力声明 |
| Python Agent Runtime | 不存在 | 新建（按需） | 第三份哈希、Schema 校验、回调客户端 |
| LangChain4j 在 agents 中的使用 | agents/chat | 暂不调整 | 只在模型网关回调协议处使用框架无关 DTO |

## 6 工作量评估（方案 B，人周，单人当量，粗估）

| 阶段 | 内容 | 估算 |
| --- | --- | --- |
| P0 安全前提 | 容器出站白名单、MCP 内部凭据强制启用、Provider 独立密钥 | 1–2 |
| P1 完成 D1（第一个 Provider 闭环） | 统一 `tool` 回调（python execute/cancel、metadata、artifact）、可信 `finish`、数据集快照绑定、真实链路测试 | 4–6 |
| P2 注册与路由 | `AgentDescriptor` 扩展、Router、`healthz` 能力声明与版本协商、`providerId` 持久化 | 2–3 |
| P3 观测收敛 | Coding 事件投影到 Agent Chat SSE/`RuntimeEvent`、关联键 | 1–2 |
| P4 D2 分析 Loop 接入（可选意图） | 按兼容性审查的最小接点实施 | 3–4 |
| P5 第二个 Adapter PoC（Claude 或 OpenAI SDK，Node） | 验证契约的供应商无关性 | 2–3 |
| P6 Python Agent Runtime（按需） | 哈希/Schema/回调客户端 + 一个 LangGraph PoC | 3–5 |
| 合计 | P0–P4 为必要部分；P5/P6 视决策 | **必要部分 11–17；全部 16–25** |

估算不包括真实 MySQL/PostgreSQL 并发验收、生产灰度周期和第三方 SDK 漏洞修复的等待时间。

## 7 需要决策确认的问题

- **D-1 推荐方案：** 是否采纳方案 B，并确认“Java 分析 Loop 不迁移、保留为内建 Provider”。
- **D-2 契约演进：** (a) 是否在 D1 上线前把 `allowedTools` 改为平台工具能力名（推荐：是）；(b) 代码工程型交付是否现在启动 v2（推荐：否，等出现真实需求）。
- **D-3 Python Agent Runtime：** 是否有必须由 Python 原生 Agent（而非 Python Sandbox 工具）完成的场景。没有则不建。
- **D-4 运行中取数：** 是否允许外部任务在运行中调用**已授权模板**取数（经统一 `tool` 回调和 `ToolRuntimeService`），还是只允许通过 Analysis Loop 补数后建新任务（推荐先采用后者）。
- **D-5 确认型工具：** 外部任务遇到 `confirm_required` 的工具时，是直接 BLOCKED（推荐），还是挂起等待用户确认（需要新的持久 WAITING 状态，现有 A1 七状态中没有）。
- **D-6 第二个 SDK：** 选择 Claude Agent SDK 还是 OpenAI Agents SDK 作为契约中立性验证对象。
- **D-7 部署拓扑：** Node/Python Runtime 与 Java 同机部署（当前联调采用 `host.docker.internal`）还是独立节点；后者需要 HTTPS 和独立出站策略。
