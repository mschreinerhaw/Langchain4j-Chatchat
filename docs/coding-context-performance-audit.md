# Coding 上下文与模型性能源码审查

审查日期：2026-11-05。基线 Git：`3f3b9fd029337f07be10a85a6ddf79a20770c3f7`。
工作分支：`feature/cline-context-performance-optimization`。开始时工作区已有上一任务的引用授权修复与探索图合并改动，本专项不将它们混入提交。

## 已核实的 SDK 能力

依据仓库实际安装的 `@cline/{sdk,core,agents}` **0.0.93** 的 JS 与类型声明，而非其他版本文档：

- `createContextCompactionPrepareTurn` 支持 `basic` / `agentic`，默认策略是 agentic，但本项目明确选 basic。basic 是确定性投影，不调用额外 LLM；不能将其称为业务语义摘要。
- 原生自动触发比例常量为 **0.9**。`previousRequestInputTokens` 校准字符估算；`overflowRecovery` 强制 basic。候选 0.65 不是当前 SDK 的公开自动阈值参数，不能直接传一个未支持的配置来宣称生效。
- `createCompactionStateAwarePrepareTurn` 使用规范历史前缀哈希，将已压缩前缀与新消息合成投影，并通过 getState/saveState 保存状态。前缀变化时放弃旧投影。不会替换规范历史。
- Agent `prepareTurn` 仅改变请求投影。当前 checkpoint 保存规范消息和代码生命周期，但未保存压缩状态；恢复后会重新压缩同一历史。
- SDK 原生支持 usage 的 input/output/cacheRead/cacheWrite。当前 Java bridge 可取得缓存和 reasoning 用量，但 Worker 的 Java 事件过滤器只保留 input/output，监控也丢掉缓存字段。

## 逐模块发现与优先级

| 优先级 | 位置 | 现状、影响与整改方向 |
|---|---|---|
| P0 | sdk-adapter.js / runner.js | 每轮重注入 `currentSource`，历史 `python_execute.input.source`、`code_read.output.source` 也保留相同代码；精确修复版本检查必须保留，但应在有授权 code_read 的模式中按需取源。没有 code_read 的模式必须保留完整源代码。 |
| P0 | sdk-adapter.js | 未使用 SDK state-aware 投影；每轮处理完整历史，且 checkpoint 无压缩状态。使用原生 prefix-hash 状态，不创建第二套 Agent Loop。 |
| P0 | request-context.js | 已有 UTF8/4 分类估算，缺每轮重复字节、重传内容、压缩前后与计数。部分分类依赖首消息位置，无法直接证明每轮内容为何重复。增加仅哈希和体积诊断，不记录 prompt。 |
| P0 | runtime-model.js / server.js / Java bridge | 名为 stream 的 Node 模型实际等待整个 Java JSON 返回；callbackFirstEventMs 不是供应商 TTFT。Node HTTP 客户端未记录连接、响应头、响应体分段，失败也缺稳定分类。 |
| P1 | runner.js | 完整 report 已自动 artifact_read，确认时又返回同份 report；基础压缩删掉报告时仍需保留最新完整报告供语义复核，不能盲目截断。阶段性保留策略应先建立实际计量。 |
| P1 | checkpoint | 有界 checkpoint（普通262144字符，文件分析750000）可能因附加投影而接近上限；不得增加另一套全历史或抬高上限掩盖。需要长历史与恢复定向验证。 |
| P1 | Skills | 已是目录描述初始投递、skill_read 按需加载；不需要重新实现技能选择，不引入 Java 关键词匹配。 |
| P1 | Java 模型链 | `ToolExecutionModels.independent(...,0)` 保留零重试和取消/截止约束；父 planner 仍独立于 Coding，不能在缺少同任务证据时一概删除。 |
| P2 | 模型上下文窗口 | softInputTokenBudget=总Token预算/调用数，16384..128000，不是供应商物理窗口。缺真实窗口时明确未知，不冒称128k已核实。 |

## 已有的正确保护

Java 继续拥有租约、fence、实际模型预算、权限、不可变证据和发布验证。Node 使用原生 Agent 顺序工具调用，错误响应不可自动盲重试。Python 修复按当前代码哈希和版本执行，未知执行结果先对账。报告必须是当前完整不可变结果；业务发现与技术校验分开。

`AgentOrchestrationEngine` 的 Coding-owned 非候选发现工具路径已经使用 `CODING_DETERMINISTIC_ADMISSION`、`toolResultReviewModelCalls=0`；已治理模板执行也绕过独立模型审核。候选资产/模板选择仍可能使用模型，但已返回的有效选择可复用。不能把普通路径的 `tool_result_review` 日志搜索结果误读为“每轮 Cline 都被 Java LLM 审查”，本批未再新增审核模型。

模型配置解析位于 `ConfigurableChatModelFactory`：超时取发布配置与剩余任务预算的较小值；独立 Coding 调用关闭SDK/provider重试，现有 `RuntimeStreamingChatModel` 是可选路径。开启streaming可观测首事件，但供应商内部队列仍需其遥测支持；本次实际模型对照保持原非流式配置，未偷偷打开全局flag。Node model回调使用任务截止而非普通控制回调超时，取消信号与任务期限仍有效；不能再用90秒控制超时解释300秒模型等待。

## 线上基线与边界

`coding-performance-baseline.json` 是只读线上监控导出：256个保留任务，1550次调用，4次记录耗时>300秒；供应商 firstTokenMs 已知数量为0。记录混合任务、模型、成功失败和重启恢复状态，不是固定任务对照组，不用于宣称P95降低。

Java `queueTimeMs` 是本地执行器等待，不是供应商队列；非流式 provider.chat 无首Token/生成阶段事件。Node/Java整体时间差也不能直接解释为纯网络时间。未知值必须保持 null/UNAVAILABLE。

## 变更前审查结论

先补观测和重复源码治理，再接原生压缩状态；不默认启用 agentic 摘要（会额外调用模型并需要另行预算接入）。不降低输出上限、不更换模型、不增加超时、不跳过权限或报告复核。真实30%Token/P95目标必须等同模型同数据的足够样本完成后验收。
