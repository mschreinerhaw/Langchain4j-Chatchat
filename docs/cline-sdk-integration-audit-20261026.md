# Runtime OS / Cline SDK 集成审查（2026-10-26）

审查对象：`feature/mcp-template-centric-permissions`，Git HEAD `e0271b164` **及当前未提交工作区**。此前稳定性整改仍有未提交修改，本报告也审查这些修改，不把它们视为已上线成果。本次新增能力核验测试和审查文档，没有删除生产逻辑、升级 SDK 或切换服务器流量。

## 结论

现有链路已经使用 Cline 原生 Agent Loop，Python Sandbox 已经是自定义 Tool。普通 Python 失败不会重新创建 Runtime 任务，也不会另建一个外部模型修复循环。应当在这个入口上收敛适配层。

主要重复实现是 `CodeHistory` 的上下文投影与私有恢复格式、模型无效响应修复协议、手工监控事件，以及已有 SDK Patch 能力之外的字符串替换协议。Java 的持久预算、权限、执行身份对账和结果验收属于平台职责，不能因 SDK 有 Session/Checkpoint 而删除。

不能把“安装了 @cline/sdk”当成“使用了完整 ClineCore Session”。当前只直接实例化 `@cline/agents.Agent`。建议先复用公开 `prepareTurn`、Hooks、事件和 Patch 扩展点；暂不整体迁移 ClineCore。后者公开配置以 provider/model 为入口，没有与当前 `Agent({ model: AgentModel })` 对等的直接自定义模型注入字段，需要验证 Java 模型桥、持久化和工作区边界后才可替换。

## 1. 版本与核验方法

`coding-agent-node/package.json`、锁文件和实际 `node_modules` 中五个包均为 **0.0.93**：`@cline/sdk`、`@cline/core`、`@cline/agents`、`@cline/llms`、`@cline/shared`。执行 `npm.cmd run verify:sdk` 成功。`@cline/sdk` 是 `@cline/core` 的公开别名；`Agent` 是 `AgentRuntime` 的别名。

依据是安装包的根导出、`.d.ts` 和实际发行 JS，未用其他版本或 VS Code 插件能力推断当前 SDK。发行包声明的上游仓库为 `https://github.com/cline/cline`，目录 `sdk/packages/*`。本报告不依赖仓库最新分支来证明 0.0.93 的行为。

可复查位置（均相对于 `coding-agent-node/node_modules`）：

- `@cline/agents/dist/agent-runtime.d.ts`、`dist/index.js`：Loop、restore、continue、abort、重试的实际实现。
- `@cline/shared/dist/agent.d.ts`：AgentModel、AgentTool、七个 Hooks、prepareTurn、事件与用量。
- `@cline/core/dist/index.d.ts`：公开根导出，防止把内部可见文件当成可导入 API。
- `@cline/core/dist/ClineCore.d.ts`、`cline-core/types.d.ts`、`runtime/host/runtime-host.d.ts`：Session、消息读取、恢复、取消、用量。
- `@cline/core/dist/types/config.d.ts`：compaction、checkpoint、extraTools、模型配置。
- `@cline/core/dist/extensions/tools/executors/apply-patch.d.ts`：Patch 执行器与路径约束。

版本锁定只证明本地安装状态，本次未重新核对服务器运行容器的安装包，也没有把本地审查当成部署验收。

## 2. 自研实现与原生能力对应表

| 领域 | 当前真实实现及启用情况 | 0.0.93 原生公开能力 | 判断 |
| --- | --- | --- | --- |
| Agent Loop | `src/runner.js` 调用一次 `new Agent(config)`、`run/continue`；模型→工具→反馈由 SDK 循环推进 | `Agent.run/continue`、原生工具分发 | 已复用，保留入口，不另写循环 |
| Session 身份 | 自建 `sdkSessionId` UUID，当前只赋给 `agentId`；Node `runs` Map 保存活动实例 | Agent 有 `sessionId/conversationId/agentId`；Core 有 `ClineCore.start/send`、RuntimeHost | 名称混淆；不是 Core 持久 Session |
| Python 修复 | `python_execute.execute` 返回结构化 receipt；`afterTool` 将验收失败映射成 `isError`，SDK 下一轮接收结果 | 自定义 `AgentTool.execute`、工具异常转反馈、`afterTool` | 已在原生循环中，无需再封装第二层 Agent |
| 上下文 | `CodeHistory.messages()` 每次重建单条 user JSON，模型适配层替换消息；checkpoint 另存代码状态及占位消息 | `prepareTurn` / `beforeModel` 消息投影；Core `compaction.strategy=basic/agentic`、公开 `createContextCompactionPrepareTurn` | 明显可收敛；公开投影不会改写 canonical transcript |
| 检查点 | Java `sdkCheckpoint` CAS，Node 保存消息、ordinals 和 `codeLifecycle`；代码另有有效 hash/source | Agent `snapshot/restore`；Core `checkpoint.enabled/createCheckpoint`、`compareCheckpoint/restore` | Agent 恢复已用；Core Git checkpoint 未启用，且不是逐工具代码检查点 |
| Diff/Patch | `prepareProgram` 自研精确 oldText/newText 替换；CodeHistory 加父版本、hash、片段守卫、回滚 | 根导出 `computePatchChanges/createApplyPatchExecutor/createBuiltinTools` | 替换通用补丁算法，保留父版本与平台校验 |
| Hooks | 已启用 `beforeModel/afterModel/afterTool/onEvent/afterRun` | 七个 hook 还包括 `beforeRun/beforeTool`，支持 stop、消息投影、追加反馈 | 继续复用；没有启用 hook 文件/子进程插件体系 |
| 模型异常恢复 | Java 检查工具 JSON，Node 自定义 `runtime-model-feedback`、修复计数和 completionGuard | 原生无效工具参数反馈、截断/上下文溢出恢复、provider retry | 适配层屏蔽部分原生能力，需逐项替换 |
| 取消与 Deadline | Java 全局截止/预算/租约；Node timer 调 `agent.abort`，工具 signal 传给 HTTP/Sandbox | `Agent.abort`、工具/model signal；Core abort/stop | signal 已接入；SDK 不提供跨 Java/Python 全局事务 Deadline |
| 用量与日志 | afterModel settle，手工 MODEL/TOOL 事件，独立只读 monitor | `usage-updated`、模型 metrics、工具 durationMs、logger/telemetry；Core getAccumulatedUsage | 统计来源应收敛到原生；Java durable ledger 保留 |
| 数据供给 | Java input-context/data-request、Parquet/CSV 共享快照、授权 metadata | 自定义工具、tool policies | SDK 不替代业务权限和授权数据供给 |
| 产物验收 | Java Python callback/evidence/feedback 检查 Manifest、Schema、数据/证据引用、stop proof | Tool result/isError、completion tool lifecycle | SDK 可控制何时继续修复，最终成功仍由 Java 决定 |

相关历史：`f847b83a1` 引入响应拒绝恢复和发布模型连接；`153fabcdf` 修正长模型回调/租约；`57771a95a`、`eb571d577` 收敛 Java 推理和上下文职责。当前工作区又引入了更重的 CodeHistory 压缩与片段守卫，本次将它们列为收敛对象，不沿用“增加自研生命周期一定更好”的假设。

## 3. Python 失败、重试和恢复的实际链路

正常执行：Java `CodingAnalysisService` 接受任务并交付快照 → `ClineCodingAdapter` 启动 Node → 同一 Agent 生成 `python_execute` → Java prepare/execute → Sandbox → Node 在**同一个 operationId** 上轮询 → Java 验收并返回 executionFeedback → SDK 接收 tool-result → 在原会话继续编写/修复。

`runner.js` 中的 `while (!receipt.executorStopped)` 是进程状态查询，不是自研模型循环。`CodingAnalysisService` 的等待/resume 循环是任务状态协调，不承担代码规划。

只有 Node 实例丢失并且 Java 确认没有未决操作时，`ClineCodingAdapter.resume` 才用 Java 保存的 checkpoint 创建新 Agent，调用 `restore(messages)` 后 `continue()`。这是重建实例恢复同一平台任务，不是原进程 reconnect。若没有安全 checkpoint，不能保证自动继续，现有代码会对账/结束失败而不是任意重放。

`ClineCodingAdapter` 中“SDK 没有跨进程 reconnect API”的注释仅适用于当前裸 Agent 集成；完整 Core 有持久 Session/RuntimeHost API，不能把该注释推广成整个 SDK 没有恢复能力。Core 的 `restore({checkpointRunCount})` 默认恢复消息时创建 **fork**，也不能直接当成当前任务的原位恢复。

## 4. 已实测的 SDK 边界

新增 `coding-agent-node/test/sdk-native-capabilities.test.js`，使用真实安装 SDK 和本地模型/工具 fixture：

1. 根导出中存在 Session、compaction、Patch 扩展点，Core 的 start/restore/compareCheckpoint/subscribe 方法存在。`readMessages/send/abort` 是实例绑定字段，不能用 prototype 缺失判定 API 不存在。
2. 工具抛错后，SDK 第二轮模型收到 `isError:true` 和错误；全程一个原生 runId。
3. `prepareTurn` 修改请求投影，`AgentRunResult.messages` 保留原始 canonical transcript。
4. `restore` 保留消息，但清空 SDK 累计 usage。跨重启总用量不能只读新 Agent snapshot。
5. `errorRetryable:false` 确实阻止 provider 自动重试。
6. 普通 provider retry 会再次调用 beforeModel；但 max-token 压缩恢复中的 prepared-request retry 会复用已经准备的请求，**不再调用 beforeModel**。实测分别为 2 streams/2 hooks 和 3 streams/2 hooks。现有注释把全部 retry 都说成绕过 beforeModel 不准确。
7. 裸 AgentTool 设置 `timeoutMs:1`，执行 25ms 的工具仍正常完成。这一层不能靠该字段实现强制超时；也不应假定工具 retry 元数据会产生自动工具重试。
8. `Agent.abort` 将取消 signal 传到正在执行的自定义工具；工具必须主动处理信号。无法据此推断远端 Python 已停止。

这不是 ClineCore 的真实会话持久化端到端测试，也不是实际外部模型性能测试。Core 的恢复、Git checkpoint、原生 Patch 在本工程工作区/容器内的行为仍需集成验证。

## 5. 当前问题与影响范围

### P1：模型适配损失 SDK 所需的信息

`ClineRuntimeModelBridge` 返回 finishReason，但 `runtime-model.js` 总是输出 `stop/tool-calls`，并在 Java 拒绝工具参数时走私有反馈消息。SDK 收不到真实 max-tokens/content-filter 等结束语义；原生截断恢复及错误分类也因此不能完整工作。Java 保留工具授权、JSON/大小校验；把 provider 状态无损投影为公开 AgentModelEvent，再让 SDK 控制恢复。

这类重复恢复属于自身集成设计问题；外部模型超时、限流和截断是独立触发因素。不能依据这些机制推断每一次线上失败的根因，仍需具体 taskId/requestId 日志。

### P1：上下文与恢复格式重复且存在容量冲突

`CodeHistory.messages` 手工丢弃中间消息并重建单条 user JSON，另存 initialPrompt/observations/源码，SDK 自己仍维护完整 transcript。恢复依赖 codeLifecycle 私有结构，不再仅依赖 SDK 消息。

Java checkpoint 上限是 262144 **字符**，Node Python source 上限是 256000 **UTF-8 字节**；代码本身接近上限时，再加原始请求、诊断、观察和 JSON 转义就可能拒绝保存。这是静态边界冲突，尚未在真实最大任务上复现。应持久化源码 artifact 引用/hash，保留受限诊断与 canonical checkpoint，不每次复制完整代码历史。

### P1：把文本片段保护称为回归断言过强

CodeHistory 当前 contains/excludes 守卫只能防止某些字面修改回退，无法证明 Python 行为不退化；也可能禁止合法重构。SDK Git checkpoint 同样不提供语义回归证明。保留真实任务契约验收/可执行测试，把字面守卫从“正确性保证”降为可移除策略；在测试覆盖到位后移除其通用框架。

### P1：隐藏尝试与计费准入不应绑定同一个 beforeModel 假设

Java 模型账本目前依赖 Node `beforeModel` reserve / `afterModel` settle，安全起见禁用了 provider retry。开启 SDK 原生恢复前，须把**每次实际 transport attempt** 的预算、operationId、signal、settle 接入 AgentModel.stream/Java 模型入口，并验证一次尝试只记一次账。保留 SDK 的循环与恢复；不在 Java 再加独立 retry loop。不要仅删掉 `errorRetryable:false`。

### P2：过多同步回调与统计重复

一轮模型除 model 请求，还顺序执行 checkpoint、reserve-model、settle-model 和多次 event 回调；每次 Python 又有 prepare/execute/status/event。`forward` 直接 await 监控事件 HTTP，因此观测服务失败也可能影响执行链路；源码注释“observability 不干扰执行”只对本地 observe 回调成立。

可在同一次 Java 模型入口原子完成 reserve/start，在返回前保存 receipt/settle；兼容期间不得两边同时记账。原生 tool-finished 自带 durationMs，usage-updated 自带增量聚合，可统一投影、按事件批量上传，关键执行证据继续同步持久化。250ms 的 Python 状态轮询可改为剩余预算内长轮询/有上限退避，不增加另一套调度器。

没有证据显示每次修复都会重查全量数据：`readInputContext` 仅新任务读取；数据实际走共享快照，DataSupply 有 requestId/hash 幂等。但新 requestId 的语义相同检索仍需具体 trace 判断。重复传输已能静态确认的是每次回调携带完整 contract、重复 system/tools/context 以及 checkpoint 中的源码/上下文。不能将必要的模型历史输入全部称为“重复模型调用”。

## 6. 删除、替换、保留清单

下面是**建议及删除门槛**，不是已完成的生产修改。

### 删除（验证替代生效后）

| 对象 | 删除范围 | 门槛 |
| --- | --- | --- |
| CodeHistory.messages 与占位 checkpoint 消息 | 去除独立的对话压缩/重建协议，不删除代码 hash/版本证据 | 原生 prepareTurn/compaction 和恢复测试覆盖原任务指令、工具反馈、最新源码 |
| protectedFragments/forbiddenFragments 通用文本守卫 | 去除将字符串当成行为回归的策略及重复快照数据 | 可执行回归、父 hash 校验和有效版本恢复通过 |
| 私有 runtime-model-feedback 修复循环状态 | 去掉能由原生无效参数/截断反馈覆盖的 completionGuard、计数和 checkpoint 字段 | provider finish/error/usage 无损映射；超预算和授权拒绝仍不可自动恢复 |
| 手工重复 MODEL/TOOL 时间聚合 | 去掉与原生事件重复的计时/计数，保留平台事件投影 | 原生事件与 Java账本可按 task/attempt/toolCall/requestId 对齐且无重复 |
| 旧 checkpoint 重建兼容分支 | 最终去掉通过历史 tool-call 拼接 previousProgram 的旧格式恢复 | 明确旧任务迁移/耗尽窗口后删除；当前不能直接移除 |

### 替换（优先公开 API）

| 当前实现 | 最小替换方式 | 必须处理的边界 |
| --- | --- | --- |
| 在 model.stream 内覆盖上下文 | 使用 Agent.prepareTurn；优先公开 createContextCompactionPrepareTurn 的 basic 策略 | 两层 context 类型不同：映射 signal→abortSignal、messages→apiMessages，并补齐模型信息；不能直接无适配赋值；basic 不新增摘要模型调用 |
| prepareProgram 的字符串补丁算法 | 复用 computePatchChanges/createApplyPatchExecutor 的解析/计算，平台 wrapper 校验父版本后保存新版本 | 原生执行器会写磁盘；绝对路径按声明可直接接受，不能仅依赖 restrictToCwd。必须先限制相对路径、realpath/链接、读写范围；Node 对共享输入只读，不开放宿主 shell |
| sdkSessionId 只填 agentId | 明确传递 taskId 对应的 sessionId；agentId/conversationId 分别记录 | 字段赋值不会自动启用 Core 持久化，不宣称已经有 Session store |
| beforeModel 代表每次 provider attempt | stream/Java transport 边界做准入及证据，SDK hooks 做观察和任务边界检查 | SDK prepared-request retry 同样计费；检查压缩产生的额外模型调用；未知用量不能变成 0 |
| 源码内联私有 checkpoint | SDK canonical 消息/投影状态 + 已有私有 artifact 引用、hash、版本摘要 | 不能暴露源码到只读公共 monitor；任务绑定/CAS/fencing 保留 |
| 高频单条同步监控回调 | SDK onEvent/telemetry/logger 统一投影后批量上传，失败有可追溯降级 | 权限、预算、执行 start/stop 证据不是可丢失的遥测事件 |

完整 ClineCore Session 是第二阶段可选替换，不是第一阶段先决条件。若迁移，使用 `backendMode:'local'`、持久存储卷、受控 extraTools/toolPolicies，禁用不需要的 shell/network/MCP/team/automation 能力；checkpoint 显式配置。`ClineCoreOptions.fetch` 是公开 transport 注入点，但不是即插即用的 AgentModel 接口，必须验证是否能复用 Java 模型连接/计费且无需重建一套 provider 协议。无法满足时继续使用裸 Agent 加现有持久 Runtime 即可。

### 保留（平台职责）

- Java DurableCodingTaskService/Journal：任务状态、权限边界、租约/fencing、不可扩展 Deadline、取消终态、预算及未知执行对账。SDK Session 是编码状态容器，不成为第二个任务调度权威。
- Java Runtime 模型连接快照、授权与凭证治理；现有 AgentModel 桥先保留，避免 SDK 各自选择模型或跳过费用治理。
- 授权数据供给、Parquet/CSV 快照、不可变输入引用、共享目录隔离；SDK 只获得授权引用和必要元数据。
- Python Sandbox 的隔离/资源限制、进程退出和 stop proof、结构化技术诊断、Manifest/必需文件/Schema 检查、空结果表达。
- Java 最终结果、证据、数据引用验收；exit 0 或 SDK completed 都不能独立认定平台任务成功。
- hash/父版本校验、不可变代码 artifact、最近有效版本引用、执行历史和必要的兼容窗口。原生 Git checkpoint 不取代这些执行绑定证据。
- 现有工作区 proposal/Diff 用户审核流程；分析执行与代码编辑提案契约不能为复用默认工具而合并。
- 部署回调 URL/认证外置、预检、连通性验收与日志；SDK 无法替代部署环境认证验证。

## 7. 推荐落地顺序及验收

1. 先修模型事件映射和 attempt 预算边界，保持原接口兼容。对 stop/tool-calls/max-tokens/content-filter/unknown/abort/auth/overflow 分别测试；同一请求重试既不能漏记账，也不能重复执行有副作用工具。
2. 把请求投影迁到公开 prepareTurn，接 basic compaction；保留 canonical transcript 和 artifact hash。压缩前后在同一任务执行失败→局部修复→再次执行，保证不丢授权上下文或重放已执行操作。
3. 以公开 Patch 能力替换通用字符串算法，缩小 CodeHistory 至版本证据适配。先测父 hash 不符、路径越界/链接、合法重构、回滚后重新验收，再删旧实现。
4. 原生事件统一投影并批量上报；合并可合并的准入/结算调用，保留失败原子性。比较每轮 RPC 数、checkpoint 字节、模型输入/输出 tokens，而非仅比较代码行数。
5. 只有第一阶段完成后，再以持久化、重启恢复、工具隔离和模型桥适配 PoC 判断是否值得迁移完整 Core；不同时维护两个生产执行入口。

回归至少覆盖：首次成功、Python 异常同会话修复、exit 0 无文件、空数据合法结果、补丁父版本冲突、取消与全局 Deadline、Node 重启、未决 Python 不重放、晚到结果不可改写终态、并行任务文件隔离、原工作区审核接口。

## 8. 本次验证与指标边界

本次新增 9 项真实 SDK 行为核验；测试只使用 fixture model/tool，未调用外部模型或真实 Python Sandbox。`npm.cmd test` 包含构建检查及完整 Node suite，实际结果 **70/70 通过，0 失败、0 跳过**；本地日志为 `.codex-tmp/cline-sdk-audit-node-tests.log`。本次不改 Java/Python 生产逻辑，未将此前其他整改的集成测试冒充本次 SDK 重构验收。

**尚未实施上述替换，因此没有本次改造前后生产性能差值。** 当前可验证的基线是：一次 Python 失败后 SDK 使用同一 runId，随后进行第二次模型调用；普通 retry 2 streams/2 beforeModel，prepared retry 3 streams/2 beforeModel。SDK 原生错误恢复不会凭空减少必要的修复调用。

后续同一数据快照、相同契约/预算、固定模型配置，分别统计端到端成功率/耗时 p50/p95、实际 provider attempts、修复次数、模型/工具/Java-Node RPC 耗时、tokens、CPU/RSS、checkpoint 大小和恢复重放次数。此前稳定性文档中的合成 CodeHistory 基准只验证特定片段回退用例，不能证明自研机制优于原生 compaction/Patch，更不能代表生产成功率提升。
