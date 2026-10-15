# Runtime OS 多语言改造分阶段路线

日期：2026-10-15。前提：采纳 [可行性评估](runtime-os-refactoring-feasibility.md) 中的方案 B。本路线**不包含**一次性重写：Java 分析主链路始终保持可运行，所有新增能力默认关闭。

## 总体顺序与门槛

```text
P0 安全前提 ──▶ P1 完成 D1（首个 Provider 闭环）──▶ P2 注册/路由 ──▶ P3 观测收敛
                                                     │                 │
                                                     └──▶ P4 D2 分析 Loop 可选接入（依赖 P1+P3）
                                                     └──▶ P5 第二 SDK PoC（依赖 P2）
                                                     └──▶ P6 Python Agent Runtime（依赖 P2，且 D-3 决策为“需要”）
```

每个阶段通过验收后才进入下一阶段，沿用仓库现有的 G 门槛做法。阶段报告必须区分“确定性模型/固定脚本”验证与“真实模型/真实部署”验证。

## P0 安全前提

- **目标：** 在任何外部 Runtime 进入生产之前，关闭网络和凭据层面的绕过面。
- **涉及模块：** `deploy/docker/coding-agent/compose.yml`、`McpGrpcAuthorizationInterceptor`/`InternalCredentialProperties`（只增加启动校验）、`ClineCallbackAuthFilter` 的密钥来源、API 本地 `PythonRuntimeService`。
- **前置条件：** 确定部署拓扑（D-7）。
- **工作内容：**
  1. Runtime 容器出站只允许访问 Java 回调地址；DB、MCP gRPC/HTTP、Redis、OpenSearch 端口不可达。
  2. 外部 Runtime 开关打开时，MCP 内部凭据必须启用，否则启动失败。
  3. 每个 Provider 使用独立回调密钥，与 MCP 内部凭据分离。
  4. 把 `PythonRuntimeService.provision/execute` 标记为弃用，保留 ruff。
- **验收标准：** 在容器内对 DB、MCP 端口发起连接均失败（实测记录）；凭据缺失时启动失败（测试）；回调密钥轮换演练一次。
- **回滚：** 网络策略按 Compose 文件版本回退；启动校验只在外部 Runtime 开关打开时生效，关闭开关即回到现状。

## P1 完成 D1：首个 Provider 端到端闭环

- **目标：** Java→Node→（模型回调、统一工具回调）→MCP Python Sandbox→证据→权威终态，在真实 Docker 和确定性模型下贯通，再完成一次受控的真实模型任务。
- **涉及模块：** `ClineModelCallbackController`（新增统一 `tool`/`tool-cancel` 回调，或在同一网关中新增）、`CodingPythonExecutionBridge`、`CodingEvidenceCollector`、`DurableCodingTaskService`（只读接口不变）、`coding-agent-node/src/runner.js`（工具名映射）、DatasetHandle→输入快照导出（按 [兼容性审查](runtime-os-coding-agent-data-acquisition-compatibility-review.md) 的要求）。
- **前置条件：** P0；决策 D-2(a)（工具名取值约定）。
- **验收标准（沿用 D1/G3 定义）：** 生成→报错→修复→成功，六项引用齐全；运行中取消得到 Sandbox 停止证明后才进入 CANCELLED；预算预留与结算可对账；Node 重启后任务进入 `reconciliationRequired` 且不重放；跨租户、跨 run、篡改 hash、额外输入文件均被拒绝；真实模型任务一次。
- **回滚：** `chatchat.coding.cline.enabled=false` / `chatchat.coding.enabled=false`。保留检查点、意图、预留，不删除数据（A2 §8 回滚规则）。

## P2 Runtime 注册、能力声明与路由

- **目标：** 外部任务型 Runtime 通过 descriptor 注册；Java 按能力、健康和版本精确路由，不做隐式回退。
- **涉及模块：** `AgentDescriptor`（`Protocol.RUNTIME_TASK_V1`）、`AgentRegistryPort`/`JpaAgentRegistry`、新增的薄路由组件（在 api 的 runtime 包中）、`ClineCodingAdapter`（泛化为按 descriptor 实例化）、Node `healthz`（能力和版本声明）、A2 任务记录中的 `providerId` 字段（检查点内部 storageVersion 升级，需要兼容旧版本读取）。
- **前置条件：** P1 验收。
- **验收标准：** 只注册 Cline Provider 时行为与 P1 一致（回归）；声明版本不匹配时拒绝路由；Provider 不健康时 submit 返回 `ADAPTER_UNAVAILABLE` 且不创建执行；恢复时回到原 Provider。
- **回滚：** 路由组件只有一个注册项时等价于直连。可以回退为 P1 中硬配置的 Adapter Bean（`ClineCodingConfiguration`）。

## P3 观测与事件收敛

- **目标：** 用户能在 Agent Chat/任务页看到外部任务进度；全链路可以用同一组关联键排查问题。
- **涉及模块：** Coding 事件 → `AgentEvent`（chat）只读投影；`CodingExecutionEvent` 适配为 `RuntimeEvent`；回调日志、MCP 审计、Node 日志统一使用 `tenant/user/run/task/operationId`。
- **前置条件：** P1。
- **验收标准：** 断线后按 `afterSequence` 重连能补齐事件；投影事件不能改变权威状态（测试）；能用一个 `taskId` 检索到 Java、Node、MCP 三端日志。
- **回滚：** 投影是只读旁路，关闭即可。

## P4 D2：Analysis Loop 可选提交外部任务

- **目标：** Harness 在受控操作分派处可以提出“提交 Coding 任务”的意图；结果以派生 `DatasetHandle` 返回；模型可以不使用该意图直接完成分析。
- **涉及模块：** `AnalysisCoverageCoordinator`、`ModelNativeAnalysisHarness`（受控操作分派）、`CodingEvidenceCollector.registeredResult`。不修改模板执行器和 SQL Studio。
- **前置条件：** P1、P3；先修复 Harness 测试中的过期桩（兼容性审查中的定向测试结果）；决策 D-4。
- **验收标准：** 原有模板取数回归通过；模型不调用 Coding 的路径通过；数据不足以结构化缺口回到 Analysis Loop；原始和派生句柄在多轮及恢复后都可读取；对照组的金融数据故障注入（按 D 阶段原计划）。
- **回滚：** 意图开关关闭后，Harness 回到现有的操作集合。

## P5 第二个 Adapter PoC（契约中立性验证）

- **目标：** 用 Claude Agent SDK 或 OpenAI Agents SDK（D-6）在 Node 中实现同一协议，**不修改** Java 控制面和公共契约。
- **涉及模块：** `coding-agent-node`（新增 Adapter 模块，或新建镜像）、descriptor。
- **验收标准：** 复用 P1 的确定性模型测试集全部通过；如果需要修改 Java 或契约，记录为契约缺陷，并按 A1 版本规则处理。
- **回滚：** 删除 descriptor 即可停用。

## P6 Python Agent Runtime（按需）

- **目标：** 承接必须由 Python 原生 Agent 完成的场景（取决于 D-3）。
- **涉及模块：** 新建 Python 服务（镜像、规范化哈希第三实现、`coding.schema.json` 校验、回调客户端）；Sandbox 仍由 MCP 管理。
- **前置条件：** P2；D-3 决策为“需要”。
- **验收标准：** 对照 `canonical-vector.json` 的哈希一致性测试；与 P1 相同的安全、取消、恢复测试；容器出站策略与 P0 一致。
- **回滚：** 删除 descriptor 并停止服务。

## 明确不做（本轮及可见阶段内）

- 不迁移 `AgentOrchestrationEngine`、`InterpretationPlanRuntime`、`ModelNativeAnalysisHarness` 到其他语言。
- 不合并 `AgentEventStore`、`AgentRunStore`、Coding 检查点的存储，只做投影。
- 不改变 MCP 资产发布、授权模板、参数绑定、执行模板流程。
- 不新增通用执行 SPI、通用 Evidence Store 或第二套任务队列。
- 不把 LangChain4j 从 agents 全面剥离；只保证模型回调协议框架无关。
- 不启动方案 C 的平台化建设。

## 工作量汇总

见 [可行性评估 §6](runtime-os-refactoring-feasibility.md)：必要阶段（P0–P4）约 11–17 人周，含 P5/P6 约 16–25 人周（粗估）。
