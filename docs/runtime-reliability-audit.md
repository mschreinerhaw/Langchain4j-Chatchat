# Runtime 可靠性场景审计

日期：2026-09-29。基线提交：`d5fb77a6`。本轮不能给出“架构已可靠”的结论。

## 真实故障与控制边界

复现任务：`86f02ddc-8e0e-404d-8267-5aa40742ab5e`，Agent：`data_asset_operation_management`。
问题为“净值比对分析主要分析哪些内容，适用什么场景”。

日志与只读接口共同确认：

1. 模型生成三节点计划；资产查询 → 模板查询 → 回答。依赖契约声明 `onFailure=replan`。
2. 资产查询在传输层成功，但模型拒绝返回的候选。候选不能作为后续绑定依据，这个限制必须保留。
3. 调度器把 `replan` 降成 `stop`；上层仅承认新的 evidence nextActions，没有收到执行恢复信号，未启动重规划。
4. 必需模板节点未执行。收口时把“节点尚未执行”视为“工作仍在运行”，同步入口却已经返回。
5. Task 为 `NO_PRESENTABLE_RESULT`；对应 Runtime 为 `RUNNING` 且 `finishedAt=null`。这是跨层状态不一致，不能用回答文本掩盖。

本次修改的是通用控制契约，不按工具名称、金融术语或问题关键词分支：

- DAG 把显式 `replan` 转为 `DAG_REWRITE_REQUESTED`，保留失败节点、失败原因和剩余节点。
- 分析协调器区分执行恢复与证据扩展；显式依赖恢复不要求模型先提出新工具，仍受计划预算与全局次数上限约束。零预算不得突破；拒绝候选不得被自动接受。
- fatal block 投影为终态失败。错误说明文本不算业务成功；未执行节点不等于仍有活动子任务。
- 生命周期边界拒绝同步操作返回没有 continuation 的 `RUNNING`。真正持久化挂起继续通过 `AgentPlanSuspendedException` 移交控制权。

## 场景矩阵

| 边界 | 场景 | 必须检查的性质 | 自动化入口 |
|---|---|---|---|
| 入口 | 未选工具、固定工作流、通用规划失败、原有 InterpretationPlan | 一个规划主体；无工具可直接回答；不得重复规划 | `AgentToolPolicyResolverTest`、`InteractionWorkflowCoordinatorTest`、`AgentChatModeHandlerTest` |
| 父子流程 | 模型延迟、子流程尚未返回、子流程异常、取消后的迟到结果 | 子流程结束前父流程不能 COMPLETE；取消不能转成功 | `ProblemAnalysisLifecycleTest`、`InteractionExecutionTest` |
| 依赖恢复 | 传输失败、传输成功但候选被拒绝；stop/replan | 拒绝证据不流入依赖节点；执行失败策略传到上层恢复协调器 | `DependencyRecoveryScenarioTest` |
| 恢复预算 | 0 次、1 次、2 次预算；次数耗尽；没有 nextActions | 明确的执行恢复契约可进入重规划；预算耗尽必须收口 | `InterpretationRefinementAdmissionTest`、`AnalysisRefinementCoordinatorTest` |
| DAG 调度 | 串行、并行、独立分支、非法模型选点、并发请求 | 只执行就绪节点；不得放大工具调用或跨请求混用状态 | `InterpretationPlanSchedulingStressTest`、`InterpretationPlanRuntimeTest` |
| 检查点 | 恢复提交边界、令牌篡改、输入变化、并行提交波次 | 不能复用失效证据；已提交节点不重复执行 | `InterpretationPlanRecoveryExecutionTest`、`InterpretationPlanCheckpointIdentityTest`、增量恢复测试 |
| 权限 | 超管、普通角色、伪造身份、跨租户、停用用户／工具 | 超管语义与身份来源一致；普通授权限制继续有效 | API/MCP/CandidateRetriever 权限测试 |
| MCP 协议 | 分页、空结果、错误信封、动态子工具绑定 | 原始失败不可修复成成功；游标与能力元数据一致 | `TemplateQueryMcpToolPublisherTest`、`BoundTemplateCandidateRetrieverTest`、`StandardMcpResultRepairerTest` |
| 结果收口 | 必需节点未开始、fatal block、真实挂起、预算耗尽、取消 | Runtime/Task/展示状态一致；诊断文本不等于成功结果 | `AgentOutcomeProjectionTest`、`AgentRunLifecycleCoordinatorTest`、`CapabilityWorkflowRuntimeTest` |
| 应用集成 | 完整 Spring 上下文、真实身份表与资源授权表、JPA 持久化 | 测试配置不污染生产扫描；必须运行到断言 | `ApiUserRoleScheduleMcpAuthorizationIntegrationTest`、`SkillProtocolPersistenceTest` |

## 对照与门禁

在独立 worktree 对原提交运行相同失败测试，已复现 **132 个原有失败／错误**：

| 测试类 | 断言失败 | 执行错误 |
|---|---:|---:|
| AgentOrchestratorTest | 34 | 28 |
| AgentWorkflowStateTrackerTest | 1 | 0 |
| InterpretationPlanOptimizerTest | 0 | 1 |
| InterpretationPlanRuntimeArchitectureTest | 1 | 0 |
| InterpretationPlanRuntimeTest | 59 | 8 |

这些是独立对照结果，不是根据“以前没改过”推测。逐项名称见 [基线失败清单](runtime-reliability-baseline.json)。它们不能被过滤或改断言后宣称通过。
初步分类：旧夹具缺少必填模型名；能力注册表快照／元数据与旧名称推断测试不一致；资产目标和模板参数链路断言失败；Runtime 已超出架构体积上限。每组仍需逐项判定是失效夹具还是实现缺陷。

上次未启动的 API 集成测试已能启动并执行断言；进一步暴露普通用户夹具只写 domain ACL、没有 shared resource grant。修正夹具以覆盖先拒绝、再双重授权放行，不放宽生产授权策略。JPA 测试配置通过专用 profile 隔离，生产扫描使用 Spring Boot 自带的排除规则。

运行：

```powershell
powershell -NoProfile -File scripts/test-runtime-reliability.ps1 -Offline
```

脚本跨模块收集失败，输出 `target/runtime-reliability/summary.json` 与 `maven.log`。即使 Maven 在 `test.failure.ignore` 下输出 BUILD SUCCESS，只要存在失败、错误、跳过、没有报告或构建失败，脚本仍以失败退出。

本轮完整矩阵执行了 **746 项自动化用例**，无跳过。完整运行最初有 134 项失败／错误，其中 132 项与原版本逐项对应，另 2 项是新增模拟注册表夹具未枚举能力名称，未进入预期的语义拒绝分支。修正夹具后专门复跑控制链路，并保留完整矩阵原始失败报告；没有通过跳过测试或降低断言消除失败。

最终控制链路复跑 **27/27 通过**，含显式恢复预算压制先前证据扩展预算的情形。按最新套件报告去重汇总为 **614 通过、95 断言失败、37 执行错误、0 跳过**；132 个未通过用例全部与原版本清单匹配。汇总保存在 `target/runtime-reliability/summary-latest.json`；它是完整运行加定向复跑的最新结果，不代表一次完整绿灯运行。

上次阻塞的应用启动和持久化测试本轮已通过真实数据库断言。模拟调度压力测试覆盖 32 并发请求下的普通 DAG、语义分支及非法模型选点，不能据此推导真实模型／数据库的服务容量。

## 尚不能宣称通过的验收

- 原有 132 项失败未清零，不能把本次修复的定向通过数量代替架构验收。
- 当前新日志场景尚未在部署了本轮修改的服务上完成真实模型复测；本轮未改线上数据或部署版本。
- 真实模型多次重复运行、混合意图、长会话、进程中断恢复、网络故障注入与跨服务负载仍需独立验收。已有模拟并发测试不代表生产容量结论。
- 路由语义、候选相关性与最终回答质量不能仅凭 HTTP 200、模型给出的 satisfied 或 toolTraceCount 判断。必须同时核对计划、事件、实际工具参数、选中证据和公开终态。
