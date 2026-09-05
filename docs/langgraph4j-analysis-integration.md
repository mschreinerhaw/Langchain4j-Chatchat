# LangGraph4j 分析执行接入

## 执行边界

分析链路现已接入实际入口：`GraphPlanningPort` 执行输入检查、既有 Planner 和计划产物检查；
`InterpretationAnalysisGraph` 接管 InterpretationPlan 的取数、分析、补证循环与收尾路由。
原引擎内的手写补证 for 循环已删除。`InterpretationAnalysisSession` 是每次请求独立的节点动作，
通过包内引擎适配调用既有权限、工具、预算、证据与发布服务；不是第二个调度器。

```text
binding_preflight → driver_planner → plan_product
  → PREPARE → INITIAL_DATA → INITIAL_ANALYSIS
      ├─ 满足要求 → FINAL_INITIAL → END
      └─ PREPARE_REFINEMENT → REFINEMENT_GATE
          ├─ 预算结束 → FINALIZE → END
          └─ REFINEMENT_PLAN → REFINEMENT_DATA → REFINEMENT_ANALYSIS
              ├─ 需要新证据 → REFINEMENT_GATE
              ├─ 满足要求 → FINAL_REFINED → END
              └─ 无进展或带限制结束 → FINALIZE → END
```

无效计划回到有预算约束的 gate；重复工具计划进入收尾。工具授权等待和 durable suspension
不进入分析与发布。节点异常保留原异常，图不增加模型调用或传输重试。
Planner 的 preflight 检查问题、模型与绑定标识；实际数据访问和参数权限仍由既有工具边界负责，
它不证明远程文件存在或数据足以回答问题。

通用对话的 ReAct 兼容入口已删除。所有新请求只进行一次顶层规划，校验通过的
`InterpretationPlan` 进入图执行；旧式 action/tool、action/final 和纯文本规划输出不能直接执行或发布。
Planner 内保留有预算的协议修复。修复后仍无有效计划，返回 FAILED，不调用工具兜底或答案 Reviewer。
原生 Function Calling 的受控参数结果也先编译、校验为 InterpretationPlan，再走同一入口。
图在取数前检查请求级步骤和工具调用预算；恢复已提交的执行结果跳过重复准入计数。
规划拒绝和预算拒绝通过 `planningAdmissionFailed` 投影为 FAILED，不能因为存在说明文字而标记成功。
LangGraph4j 管分析阶段，Runtime 管资源和证据，
Temporal 管持久执行与副作用；不另建图 checkpoint 与 Temporal 竞争恢复权。

## 补证与报告出口贯通

新增 `AnalysisState` 作为 LangGraph4j 的控制状态类型，节点显式更新 phase/status。
跨证据循环与最终综合的决策使用可序列化的 `AnalysisFlowState`（analysis_flow_state.v1）：
decision、iteration、loopClosed、stopReason。它不保存模型对象或原始数据；当前仍不支持图节点级 checkpoint 恢复。

`AnalysisLoopCoordinator` 每轮写入该状态。执行器关闭补证循环时，尚未完成的 RETRIEVE_MORE
必须按“不可继续探索”的边界重新判定：允许部分证据时进入带限制回答，严格无证据时停止，
授权阻塞保持阻塞。原始停止原因与轮次数保留，不重置预算。未知协议版本或非法状态拒绝进入最终综合。

最终综合 preflight 消费同一状态：RETRIEVE_MORE、NO_EVIDENCE、EXACT_RESULT_UNAVAILABLE、
BLOCKED_AUTHORIZATION 不调用 Judge、fallback 或后置 Reviewer，直接返回明确状态说明。
COMPLETE 和 ANALYZE_WITH_LIMITATIONS 才允许进入最终综合。旧运行缺少该状态时沿用兼容入口。

`AnalysisFinalizationPolicy` 将以下情况汇入确定性发布：

* preflight 已终止：发布状态说明，不能继续 Driver 修复或答案审核；
* 图已完成、最终产物已生成、Claim 选择已通过且存在非空 analytical_report.v1：直接发布，
  不再让旧答案 Reviewer 改写图文报告。

普通文本、未准入产物和失败产物仍保留原审核路径。此优化消除的是特定结构化报告的后置审核调用，
不表示所有请求的模型调用数都减少一次。报告逻辑和数值准入仍在原 Driver/Composer 中执行。

Planner 与补证路由已接入上述图入口。Temporal 重试和副作用执行沿用原实现；
模型修复预算仍由既有策略管理。质量与延迟仍需用相同真实数据快照验证。

## 已落地范围

锁定 `org.bsc.langgraph4j:langgraph4j-core:1.8.20`，仅在 agents 模块引入 core。
Java API 已对照 [官方入门文档](https://langgraph4j.github.io/langgraph4j/main/getting-started/) 和下载的版本验证。

`AnalysisExecutionGraph` 提供调用内隔离的 StateGraph：节点返回显式状态，只有 READY 可以进入下一节点；澄清、阻塞、取消、失败及完成全部进入 END。图不创建模型、不执行隐式重试，不配置 checkpoint saver。实际重试与持久恢复仍由既有 Runtime / Temporal 负责。

实际接入点：

```text
AnalysisDatasetWorker（Local 与 durable analyzeAssigned 共用）
  preflight → chunk_plan → worker_analysis → reconcile → END

AnalysisSynthesisCoordinator
  preflight
    ├─ NEEDS_CLARIFICATION / confirmationRequired → END
    └─ READY → judge_and_compose → publication_result → END
```

Worker 的分析节点仍保留分片、checkpoint、局部重试和必要归并。图内校验不替代公共 DataAnalysisParticipant 入口的隔离校验。

最终综合的准入与发布路由位于 `FinalAnalysisGraph`，Judge、受控修复与 Composer 由既有协作者执行，节点名为 `judge_and_compose`。图表数据仍由确定性执行器产生。无产物不会标为完成；存在覆盖/来源/证据不完整或带限制分析决定时，产物状态为 COMPLETED_WITH_LIMITATIONS。异常保留并上抛，清除本次未完成的结构化报告。

`NEEDS_CLARIFICATION` 分支消费已有 Runtime `executionStatus`，不会靠匹配用户文本或工具日志猜测缺文件。缺文件检测与入口澄清状态的完整生产链路仍须在数据绑定层接入；不能把此分支测试等同于已完成全链路前置检查。

## 上下文压缩

Driver 已有归并输入时，pipeline context 的 Worker assignments 只传 reportId 和 scope；完整分析内容由归并输入提供。原始 Worker 产物留在 Runtime 中，未删除证据或计算结果。没有归并输入时保留兼容投影。

测试验证重复 Worker 内容不会重新进入该投影，投影长度降低且身份引用保留。这不是全量 prompt 压缩完成的声明：request.prompt、完整 lineage 和其他治理上下文仍需要后续测量。

## 观测与持久性

Planner 记录 `analysisPlanningNodes`，分析主图记录 `analysisPipelinePhase/Status/Nodes`，
每个阶段记录后继、执行状态与耗时。此处 SUCCEEDED 只代表节点执行成功，业务完成状态仍读取
`analysisGraphStatus` 和 `AnalysisFlowState`。最终综合记录 `analysisGraphNodes`；
Worker 的图节点信息作为 WORKER_EXECUTION_METRIC 发布。节点历史不含原始数据或模型提示词。

图 State 只携带控制状态；任务、模型和产物存在本次调用的会话中，不能用该实现做节点级图 checkpoint 恢复。
Temporal 在取数边界保存 `AgentPlanPipelineContinuation`，恢复已提交步骤、证据历史与补证次数，
跳过已完成的初始规划；Worker 继续使用既有分片 checkpoint。未改变 Temporal Workflow 命令顺序，
也没有建立第二份持久状态。节点耗时记录用于诊断，不参与确定性 Workflow 分支。

## 验收与剩余工作

回归覆盖条件短路、受限完成、错误传播、取消、无终态拒绝、最终报告治理、并行 Worker、局部失败、分片重试和 Temporal 分析调度。真实 ETF 数据与模型服务尚未跑端到端对照，不能宣称分析质量已经达标或固定时长已实现。

代码迁移验收包括 Planner 单次调用、非法边短路、补证回路只发布一次、durable 暂停原样传播、
原编排行为、最终产物准入和跨模块架构检查。上线验收还需对同一数据快照比较结论质量、
证据覆盖、模型调用数、最大输入与端到端延迟；更换编排框架本身不能证明这些指标达标。

本次验证：`mvn -pl chatchat-runtime-temporal -am test` 通过，包含 agents 1211 项、
Temporal 35 项和 common 101 项测试。跨模块架构、API 编译及前端生产构建通过。
另补充图预算损坏时的有限退出回归。真实外部模型和 ETF 数据接口没有参与这些测试。

移除旧入口后的全量回归：agents 1213 项、Temporal 35 项、common 101 项通过。
旧协议拒绝、纯文本拒绝、正式计划发布、预算准入与确认恢复均进入测试；
原生工具参数生成验证会形成有效计划及 final_answer 依赖。
