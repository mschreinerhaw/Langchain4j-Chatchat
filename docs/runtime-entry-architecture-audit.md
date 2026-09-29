# Runtime 入口架构盘点与修复

## 范围与证据

本轮检查 API/异步任务 → Interaction → Agent/Role handler → 问题分析/专业 runtime → 结果投影的真实调用链，结合 2026-09-29 的三组运行日志。不是对所有业务模块、线上权限数据或模型质量的全面验收。

## 已确认的问题

| 问题 | 代码位置/现象 | 架构原因 | 修复原则 |
| --- | --- | --- | --- |
| 多个规划器争夺入口 | AgentChatModeHandler 在 ProblemAnalysisPlanner、直接问答、专业 runtime 之间增加多次提前返回 | 没有明确的规划职责契约 | 一次解析入口决策，选择一个规划执行主体 |
| 工具描述被当成执行准入 | 模型因缺少 DATA_FETCH 返回 NEEDS_CLARIFICATION；模板发现尚未执行 | 问题理解越权判断能力可执行性 | 原生工作流负责发现、绑定、专业计划与治理；通用规划只理解用户目标 |
| 无工具被当成缺少资源 | 未选择 MCP 的 Agent 被送入资产工作流 | 用户选择和注册/召回状态混淆 | 无选择直接回答；已选择但不可用不得冒充无选择 |
| 同一次请求重复读取能力 | 入口检查、问题规划、执行分别解析工具集合 | 没有入口能力快照；外部同步可能造成判断漂移 | 入口确定快照；执行阶段仍重新授权，不把快照当权限 |
| 收口不统一 | 专业路径直接返回，其他路径通过 CapabilityWorkflowRuntime；角色模式又单独返回 | 输出状态由不同层重复解释 | 保留执行器证据判断，统一投影结果；禁止凭回答文本宣告成功 |
| 阶段定义缺少对应执行记录 | runtimeLifecycle 返回包含 COMPLETE 的固定协议列表，无法说明当前请求走了哪些入口阶段 | 只有阶段定义，没有统一入口执行记录 | 分开定义与实际记录；父调用在子调用返回后才能收口 |
| 取消行为不一致 | 直接回答只在调用前检查中断，角色回答缺少统一检查 | 取消是散落的局部判断 | 所有拥有的模型/工作流调用前后检查取消，异常取消不改写成失败或成功 |
| 角色对话也被通用规划阻断 | RoleChatModeHandler 强制要求可执行问题计划，但不能执行该计划 | 规划契约与执行能力不匹配 | 角色模式保持上下文检索与单次对话，不调用工具工作流规划器 |

固定 30 秒父等待与独立四线程池已在前轮移除，本轮保留“父调用拥有并等待子调用”的约束，不恢复任何脱离父任务的超时回退。

额外的状态语义检查：`AgentOrchestrationEngine` 在必需步骤未调度或等待确认时也会设置 `mandatoryWorkflowPending`，这个字段描述证据/步骤尚未满足，不能证明有异步调用存活。当前 `DefaultAgentRuntime.run` 同步返回执行结果，持久化分析工作流通过 completion 等待；本轮等待约束作用于真实调用，不能将 pending 布尔值当作未来会自行完成的任务句柄。

## 目标职责

```text
Task / Interaction（任务身份、取消、最终持久化）
  → Entry decision（选择快照、规划职责）
  → InteractionWorkflowCoordinator（单一入口、等待、执行记录、统一收口）
       ├─ Direct conversation
       ├─ Native runtime → discovery / binding → InterpretationPlan → DAG / governance
       └─ Problem understanding → semantic workflow router → provider
  → WorkflowOutcome / response projection
```

`data_type` 与 `workflowContract` 是发布者声明的能力语义，不是业务关键词路由表。入口决策不使用 Agent ID、客户编号或模板名称；不注入假计划，不把缺失能力转成“问题不清楚”。

## 本轮落地

- `WorkflowEntryPlan`：不可变的入口职责和工具用途快照。`AgentToolPolicyResolver.planningSnapshot` 一次读取所选声明；删除分散的 `usesInterpretationPlanning`、`planningToolPurposes` 入口查询。
- `InteractionWorkflowCoordinator`：统一 Agent 入口分派，提供过的有效上下文计划、直接回答、专业 runtime、通用问题规划各有明确入口；保留实际执行阶段的权限解析。
- `InteractionExecution`：拥有同步子调用，子调用返回后才评估/收口；取消检查覆盖调用前后和包装异常；活动子调用、异常后的作用域及重复完成均不能通过完成检查。
- `ProblemAnalysisPlanner`：只接收调用方提供的能力快照，解除对工具策略解析器的依赖；通用问题理解不再承担专业工作流的前置准入。
- `CapabilityWorkflowRuntime`：提供共同结果规范化；专业 runtime 保留自己的结果与证据判断，缺少评估的文本不能自动升级为成功。
- `RoleChatModeHandler`：移除无法执行的通用计划门槛，保留角色、文档和领域技能上下文；采用同一执行作用域。

`runtimeLifecycleDefinition` 是静态协议定义；`runtimeLifecycle` / `runtimeExecution.phases` 是本次入口调用实际经过的阶段，专业 DAG 的细节继续由原 runtime 事件记录。阶段返回的 COMPLETED 与业务结果 SUCCESS 不等价，失败计划和澄清也可能是已返回的阶段结果。

## 明确保留的边界

- 现有专业 runtime 的模板依赖、授权、确认、InterpretationPlan 校验和执行治理不重写。
- 子工作流返回之后才评估；用户取消和真实失败仍可终止。不能用无限循环掩盖异常。
- 本轮不引入新的持久化 WAITING_INPUT 状态：目前澄清通过下一轮消息继续，尚无原任务恢复协议。不能仅改状态名就声称支持恢复。
- Skill、MCP、Document、Native、External 是能力提供方式，不等同于用户问题类型。

## 尚需部署验证的项目

线上工具发布契约与 Agent 绑定是否一致、授权交集是否正确、真实模型输出质量，以及跨进程恢复/重复投递需要各自的集成环境验证。本地测试不能证明这些线上条件已满足。

## 本地验证

Maven 离线 reactor 编译与定向回归通过：Agents 6 项，Chat 120 项，均无失败。覆盖专业计划路径解析与固定模板约束、能力声明选择、入口等待、直接回答、角色上下文、任务结果与事件桥接。

统一取消处理的最后清理重新编译后，额外复跑规划器、生命周期、执行作用域和协调器共 17 项，全部通过。

入口测试包含真实阻塞的子调用：通用问题规划和选中工作流分别释放前父调用不能返回；专业执行路径同样等待子调用，且不触发第二个前置规划器。慢模型测试等待超过原 30 秒阈值。取消回归验证迟到结果不能覆盖取消，包装的中断不会转成规划失败。

这些测试使用模拟模型和工具边界，不等同于真实模型生成完整客户分析报告的端到端验收。没有修改线上任务历史，也没有部署。
