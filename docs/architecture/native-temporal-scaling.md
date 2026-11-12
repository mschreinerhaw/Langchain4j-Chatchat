# Native Cline / Temporal 集成审查与验收约束

## 基线审查（2026-11-12）

开发基线为 `feature/runtime-cline-cli-native-20261107` 的 `9d6a420a39ab7f8070f1c5d6ee4b69878a00c4da`，与远端一致。部署的 API / Studio 源码镜像 revision 为 `283ff32566b7dc36db0f2de50fd310f4f59dbafb`，之后提交只有验收证据。新分支为 `feature/runtime-cline-temporal-scaling-20261112`，使用独立工作树，未移动或提交原工作区的静态构建文件。

实际链路：用户授权/业务供数 → NativeSiloTaskService 固定输入与模型/工具授权 → NativeAwareCodingAdapter → NativeS3CodingAdapter → NativeS3NodeClient → governance-server → 受限 Executor → 官方 Cline 4.1.26 Core → 原生 Shell/Python、Skills、Java 授权 MCP → Silo → Java 完整性/交付验收。SQL/Python Studio 使用另外的原生工作区兼容入口，本次不替换其提案/解析流程。

审查发现：

1. NativeS3CodingAdapter 当前在 API 内使用 2 个本地控制线程、128 个内存候选队列和 15 秒恢复扫描；NativeS3Settings 固定一个 Node。当前不是多节点调度。
2. 仓库已经使用官方 Temporal Java SDK 1.37.0，并有 chatchat-runtime-temporal 和独立 Temporal Compose。现有 Workflow 主要包装旧 Agent/DAG 阶段，不应将它套到 Cline 每次模型或工具请求上。
3. DurableCodingTaskService 已有持久化任务、租约、递增 fencingToken、幂等原生事件和实际用量。它继续作为授权/资源/交付治理账本，不再在 Temporal 模式中启动本地调度。
4. SiloCodingWorkspaceService 已保存不可变输入快照、对象版本、SHA-256、输出计划、上传回执与最终交付。当前 workspace epoch 同时绑定输入对象根，不能在故障重派时随意改变它而使输入授权失效。
5. 当前 Native 绑定、事件游标和证据键假定单一 Session。跨节点重派必须新增独立的 executionEpoch，隔离 Session/事件，并在任何正式交付变更前检查当前 fence。旧节点只能写其已经签发的旧输出对象版本，不能登记新结果。
6. 现有 Native 执行取消会关闭任务交付空间，并要求真实 Executor 停止证明；Java 退出只中断控制，不把进程退出冒充取消。必须保留该语义。

## 增量设计

- 使用独立 AnalysisTaskWorkflow v1 和整个分析任务 Activity；Workflow History 仅保存身份、任务/快照引用和小型状态，不保存代码、日志或模型响应。
- API 负责授权与持久化输入；Temporal 负责标准队列、领取、Activity 重试、超时与取消。每个 Worker Adapter 消费相同队列，配置独立的本地 Native 节点和 Activity 并发槽。原生 Agent loop 不变。
- Temporal 模式禁用 NativeS3CodingAdapter 的本地线程派发和恢复扫描。原有调度模式保留为显式回滚开关，不能同时消费相同任务。
- SDK Activity 心跳表示执行适配器仍存活；Node 访问使用短请求/原有 Session 查询，不保持整个用户任务的 HTTP 请求。
- 每个 Activity attempt 使用独立控制身份。重试先遵守现有租约 fence；原 Session 可达时继续观察该真实 Session，不重复发消息。原节点失联时，以新 executionEpoch 在可用节点重新执行不可变输入，明确表示重新执行而非迁移 Cline 内存。
- 旧 Session 的原生事件、模型计量和未决调用保留。新 Session 使用独立事件游标、证据键和交付版本。所有交付登记与最终完成仍经 Java 账本、Silo SHA-256/版本核对及当前执行 fence。
- 取消必须传递到 Native，并依据实际进程停止证明确认；无法证明停止时保留待协调状态。

## 强制验收门槛

### 扩容有效

使用同一源码/镜像、输入快照、任务集和模型配置，分别测量一个 Worker 与两个 Worker。至少两个不同已授权账户的真实分析执行时间区间必须重叠，执行节点 ID 必须不同。单节点任务达到槽位上限时，其余任务必须留在 Temporal 队列。

记录成功/失败总数、完整 wall time、吞吐量（通过独立数值校验的任务数 / wall time）、排队和执行时间的均值与 P95、节点分布、CPU/内存、真实模型请求与 Token。仅容器在线或任务被接收不算通过。吞吐没有提高时必须解释瓶颈并标记扩容验收失败，不虚报。

### 故障恢复正确

强制终止真实 Node / Worker，保留失败历史。证明新执行使用原授权输入及新执行标识；业务数值独立验算通过；同一业务任务只登记一份有效最终交付。恢复旧节点并实际重放旧提交/重复回调，必须被拒绝或返回既有幂等结果，不能替换新结果。核对最终对象版本、SHA-256、有效交付计数以及模型/配额没有重复记账。

其余必须项包括：真实 Python 修复、Skills/MCP/Web Search 回归、Silo 传输失败、跨用户/跨租户拒绝、取消与进程清理、Temporal Server/Worker 重启、历史查询和监控关联。Mock/单元测试只证明局部逻辑；未实际验证的指标一律不标记通过。

官方依据：[Activity 失效检测与心跳](https://docs.temporal.io/encyclopedia/detecting-activity-failures)、[Java Workflow 取消](https://github.com/temporalio/documentation/blob/main/docs/develop/java/workflows/cancellation.mdx)。
