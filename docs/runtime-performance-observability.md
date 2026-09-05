# Runtime 性能观测

运行 Trace 增加 `modelUsage`，执行结果 metadata 中也保留同一份统计。旧 Trace 构造方式仍然有效；历史运行没有观测数据时返回空对象，不补造历史调用记录。

## 当前可观测范围

* `invocations`：进入 MeteredChatModel 的调用次数，包含失败尝试；预算在调用前拒绝的请求不计入。
* `calls`：逐次调用的开始/结束时间、调用位置 `nodeName`、状态、耗时及 Token。记录不包含 prompt、回答、异常正文。
* `queueTimeMs`：模型超时执行器从提交到实际开始的等待时间。未进入执行或未经过该执行器时为 null。
* `executionTimeMs`：实际开始到调用方收到返回/错误的时间。包含传输及服务端排队；超时后底层传输可能继续，不能解释为 GPU 计算时间。
* `modelLatencyMs`：调用耗时之和，包含本地排队。并行时不能当作 Run 墙钟时间。
* `inputTokensEstimated` / `outputTokensEstimated`：本地估算；失败调用输出未知，逐次记录为 null。
* `inputTokensReported` / `outputTokensReported`：有完整服务端 usage 的调用小计，覆盖次数见 `actualUsageCalls`。字符串模型接口可能无法获得服务端 usage。
* `largestInputTokensEstimated`：单次最大估算输入上下文。
* `peakConcurrentInvocations`：同时未返回的包装器调用数，包含排队，不是服务端实际并行数。
* `failedInvocations`：失败尝试数，不等同于 retry 次数。

每次调用结束会输出 `Runtime model call runId=...` 日志。`nodeName` 是实际 Java 调用位置，不是根据 prompt 内容推断的业务阶段。

本地 Worker 在已有进度事件中增加 `WORKER_EXECUTION_METRIC`，记录任务排队、执行耗时和配置并行度；成功、失败和取消均记录。工具的开始、结束与耗时沿用 Trace 的 `toolCalls`，不要把治理状态事件计为 MCP 调用。

## 判断边界

目前不是完整 DAG 节点性能契约：尚未关联所有代码阶段的父子跨度、模型调用与工具调用的节点归属、显式重试及依赖边。`criticalPathLlmCalls` 为 null，供应商内部重试不在覆盖范围。不能从界面事件数、调用时间总和或配置并行度推导关键路径。

截图中的 hypothesis / evidence_graph 是证据分析后的状态发布，不能据此声称各触发一次模型调用。图表规划与报告编排保持确定性代码，不增加模型调用。

已发现的排队风险是 DeadlineAwareChatModel 使用 2 个核心线程、256 个队列槽位，只有队列满后才扩容。先观察 queueTimeMs，再决定线程池容量；本次没有修改并发或模型配置。

## 排查顺序

1. 获取新运行的 Trace，用 Run latency、调用次数、最大输入及 calls 时间区间定位慢点。
2. 分开比较本地模型排队、模型执行和 toolCalls 耗时；重叠区间不能重复累加到 Run 墙钟时间。
3. 判断是大上下文、调用重复、Worker 等待还是服务端慢，再决定计划压缩或执行器调优。

尚未用截图对应的 ETF Run 做复测，不据此承诺 1～3 分钟目标，也不为历史 25 分 57 秒分配未经测量的原因比例。

## 40 条事件的源码核对

* `PlanExecutionObservationCoordinator` 按步骤发布已有的 template selection / evidence evaluation metadata；发布操作不调用模型，重复文案不能证明重复推理。
* `HierarchicalAnalysisReducer` 已对单切片直接使用上游内容，单输入组也不做模型归并。保留谱系、验收和对账不等于增加模型调用。
* 带限制分析原先共用了 `attempts_exhausted` 阶段标签，现改为 `completed_with_limitations`；重复调用抑制也有独立标签。该分支仍只进入一次最终综合，未发现标签本身触发第二次 fallback。这里修改的是综合阶段，未扩展公共 Run 状态枚举。
* 业务进度增加稳定的 `progressId`，同一任务的心跳与结束事件更新同一行；其他任务的新事件不再使其提前显示完成。完整后台事件仍保留。
* Worker 耗时事件单独作为 `runtime_execution_metric` 保存，避免覆盖已结束的业务任务状态。

本轮是状态与展示修正，不是已验证的模型调用压缩；不据此宣称 40 次模型调用降到了某个次数。
