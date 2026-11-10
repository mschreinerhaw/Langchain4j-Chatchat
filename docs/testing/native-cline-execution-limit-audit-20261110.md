# Native Cline 执行限制源码审计（2026-11-10）

目标分支 `feature/runtime-cline-cli-native-20261107`，基线 `aa33f77c9e1b675bb0c0a82409001cc93e4d9de3`。本文件记录限制来源及整改；真实能力结论须另看对照执行证据，确定性测试不能代替真实模型验收。

`unlimited` 是显式语义，内部兼容值为 `-1`；实际消耗计数始终为非负有限数。未用巨大有限数字代替无限。有限配置仍兼容，用测试实例覆盖启用，不更改共享模型、服务商、默认 Coding Agent Node 或 Core。

| 组件／源码（路径相对仓库根目录） | 原配置／默认行为 | 生效及终止路径 | 整改或保留原因 |
|---|---|---|---|
| API `analysis/CodingAnalysisProperties.java` | 模型 12、工具 24、代码执行 12、累计 Token 65536 | `CodingAnalysisService` 生成不可变 Budget，账本 admission 拒绝超额 | 四项支持 `unlimited`，显式配置转换器；有限默认保留兼容 |
| Common `CodingTaskContract.Budget` | 数值不允许负数，费用必须十进制 | 创建／反序列化／哈希校验 | 仅限额支持 `unlimited`／兼容 -1；消耗 Counters 禁止负数，有限契约 JSON 不变 |
| Agents `CodingBudgetMath.within` | 所有累计消耗与限额比较 | `DurableCodingTaskService.reserve` 返回 BUDGET_EXCEEDED | 对显式无限维度不做次数比较；消耗、保留及未知用量继续存储 |
| Agents `DurableCodingTaskService.allocateScript` | 脚本版本不得超过代码执行预算 | 修改源码前拒绝分配新版本 | 显式无限时继续分配；版本、Hash 和来源留存 |
| Agents `DurableCodingTaskService` | operations 256／privateState 128／workerEvents 1024 | 超过记录数量后拒绝后续操作 | 无限次数契约不再使用这三个隐含次数上限；有限旧契约兼容 |
| Agents `CodingCompletionBudget` | 按剩余次数／Token 预留完成预算 | 低预算阶段提示 COMPLETION | 无限维度不再变成零剩余额度，不强制进入完成阶段；时间和已有交付状态仍有效 |
| API `cline/CodingBudgetShare` | 按固定调用次数切分累计 CPU／输出／费用 | 未知计量保留份额耗尽后后续 admission 被拒绝 | 无限累计维度不预留虚假份额；次数无限但钱有限时仍保留费用保护，不能伪造价格 |
| API `cline/ClineModelCallbackController` | maxTokens 对累计限额校验；model ID 六位边界 | reserve-model／settle-model | 支持无限累计 Token；操作序号不再有 999999 次边界；幂等和回执验证保留 |
| API `cline/ClinePythonCallbackController` | 必须 maxCodeExecutions、maxToolCalls >0 | reserve-python／执行授权 | 使用限额语义判定；无限通过，零仍明确禁止执行 |
| API `execution/CodingExecutionFeedbackService` | max-auto-repairs 默认 3，最高钳制 31 | 错误反馈 STOP／再次提交准入 | 支持无限，去除 31 次钳制；继承契约和任务已有策略，不能偷偷扩大有限旧任务 |
| API `cline/ClineRuntimeModelBridge` | max-model-response-repairs 默认 2，最高钳制 8 | MODEL_OUTPUT_TRUNCATED／MODEL_TOOL_ARGUMENTS_INVALID 修复授权 | 支持无限，去除 8 次钳制，实际次数留存；只授权当前错误的修复 |
| API `workspace/CodingWorkspaceService` | 元数据模型／工具次数、SQL 修复次数，原 32／256 钳制 | workspace 元数据工具、校验再提交 | 独立支持无限；execution-count-limit=unlimited 覆盖简单路径固定 8／16；SQL 工作区代码执行仍为零，维持授权边界 |
| API `cline/ClineCodingAdapter` | 恢复前以 >0 等数值判断剩余次数 | SDK 原任务恢复 admission | 使用无限语义，保留取消、原操作停止证明及幂等校验 |
| Node `src/runner.js` | SDK maxIterations 至多 32；模型回调和响应修复次数比较 | SDK run 循环及 Runtime bridge | 无限时向官方 SDK 传 `undefined`，已核对安装的官方循环条件；有限契约不再被额外钳到 32 |
| Node `src/execution-limits.js` | 新增跨适配层限额语义 | runner／completion／响应修复 | 只接收明确无限或非负 safe integer，不使用 Infinity 或无条件递归 |
| Node `src/completion-context.js` | 剩余数值 <=0 提示停止探索 | 模型系统提示 | 识别无限；未完成的未知操作仍要求先对账 |
| Native `src/business/native-policy.mjs`、`model-protocol.mjs` | 响应修复只能正整数且 <=8 | 再次模型 broker 准入 | 无限授权贯通，修复次数照记；适配层不发明重试、不自动重派失败任务 |
| Native `src/server.mjs` | 不可恢复 model.error 只发事件，状态可能一直 running | Core 不可恢复错误事件 | 同步投影 failed；普通工具执行报错不据此终止任务 |
| Common `ExecutionFailureClassifier` | 普通 RuntimeError 归为 UNKNOWN | Java 反馈 STOP | RuntimeError 属 PROGRAM_ERROR；确认停止的非零退出 UNKNOWN 分类也按程序错误返回；在途 UNKNOWN 不放开 |
| API `execution/CodingPythonExecutionBridge` | 摘要截断 stderr 8192 字符 | Native 修复只见摘要 | 返回授权任务已归档的完整捕获 stderr；不新增其他任务读取权限，stdout 仍通过原归档接口查看 |
| Node `src/completion-evaluation.js` | 请求次数／Token 预算影响成功评分 | 验收聚合 | 成功仅依据 Runtime COMPLETED、要求验证及有效产物；缺失计量仍明确 UNKNOWN |
| Monitor `monitor/metrics.mjs`、`backend.mjs`、`public/app.js` | 调用数、输入／输出 Token，缺少完整指标 | 只读观测 | 展示请求、Token、费用、工具、修复、耗时、阶段、错误、归档文件数及产物验证；未定价不显示零费用 |

## 保留的真实资源和授权约束

| 限制来源 | 范围和行为 | 保留原因 |
|---|---|---|
| 契约 deadlineAt；analysis timeout-seconds 默认 300（服务器测试原为 1200） | 任务时间到期，可取消并要求停止证明 | 用户允许资源保护；不是次数预算 |
| analysis maxCpuMillis 默认 300000、maxMemoryBytes 512 MiB、maxResultBytes 4 MiB、maxInputRows 100000、maxInputBytes 16 MiB | Sandbox 单进程／输入／单次产物保护 | 新增 cumulative CPU／output 独立配置，测试仅取消累计配额，单进程隔离保留 |
| Durable journal 64 MiB／owner task 数 64／事件历史环 8192 | 存储、并发和历史保留 | 不是单任务调用次数；不以环形历史长度作为终止条件 |
| Native 90 秒启动、10 秒权限 ACK、180 秒权限审批有效期 | 启动和授权有效性 | 不得移除用户授权；非普通代码修复预算 |
| Native autoApprovalSettings.maxRequests=100 | Core 自动批准设置；本适配层所有自动批准 actions=false，每次实际工具走授权桥 | 没有将其视作任务次数预算；实际权限、输入 Hash、epoch 和审批继续校验 |
| Gateway maxWorkers=2 | 同时运行任务数 | 保护服务器容量，不限制同一会话执行次数 |
| 模型 max-output-tokens 默认 8192；fast path／workspace 单响应上限 | 每次模型输出、上下文窗口、SDK compaction | 单响应模型能力和资源保护，区别于累计 Token／请求次数；能力对照须验证实际请求参数一致 |
| provider 请求超时／有界 HTTP 传输重试 | 单次已知可恢复网络错误；不等同修复任务循环 | 不自动重派在途未知执行；原始回执保留 |
| model usage 缺失或价格未知；finite maxCostAmount | 缺失用量／未知费用保留，不释放为已知零 | 未定价且有限费用的任务可能提前耗尽保留，测试显式 max-cost-amount=unlimited；生产仍可费用熔断 |
| Tool Reviewer／资源授权／Python 网络和文件隔离 | 只允许已授权工具、数据、路径、网络模式 | 用户明确禁止改变这些边界 |
| Workflow maxSteps、MCP workflow max_steps（默认 0=未设置）、父 Agent maxToolCalls | 父编排步骤／父工具准入 | 与 Native 内部循环不同；`ToolExecutionModels.independent(..., 0)` 不将 Native 模型请求计入父模型次数。父任务取消／截止仍传入子任务 |
| UNKNOWN 在途、取消、权限拒绝、OOM／timeout | 错误分类区分于已停止的普通程序异常 | 原操作对账证明之前禁止新派发；没有以 UNKNOWN 分类放开所有未知执行 |

精确默认值以部署时的有效配置、不可变契约及原始日志为准；兼容默认未冒充测试环境已生效。测试覆盖文件：`cline-agent-server/config/native-unlimited-test.yml`。部署须同时更新 common／agents／api 字节码及 Gateway 的 execution-limits.js 依赖，不能只替换配置。

## 已执行回归

- Maven：common 2、agents 11、API 168，共 181 项，0 失败、0 错误、0 跳过；Workspace 使用独立 SQLGlot 30.23.0 venv。
- 真实 H2 账本连续 300 次操作准入及超过旧 128 个 privateState，保留 consumed/reserved/unknown 计量状态。
- 安装的官方 SDK 同一 run：41 次模型迭代、40 次工具调用、8 次普通工具报错；这是确定性 fixture 回归，不是真实模型成功率。
- Node 相关回归 13 项；Native／Monitor 回归 65 项；包括无限修复授权超过旧 8 次，以及保留 deadline 和未定价 UNKNOWN 展示。
- Native 错误反馈回归验证超过 8192 字符完整 stderr 以及第 41 次修复，权限／超时／取消仍 STOP。

回归日志原始位置：本机 `target/native-unlimited-20261110-*-final.log`。真实 Desktop、Native、Runtime 的版本、运行和产物须单独归档，不将此前有 12 次模型代理上限的 Parquet 隔离批次计入本轮无限测试。

## 真实运行发现的补充路径

以下补充对应提交 `8187fd3fb`、`2bc064126`、`fbe34ba55`、`3bbc81f59`，不能只部署最初的 `2173ebc5f`。

| 位置 | 实际问题及终止行为 | 修改与验证 |
|---|---|---|
| `ClineRuntimeModelBridge` 消息数组校验 | 普通任务最多 64 条消息，SQL 最多 192 条；消息随循环增加，形成约 32 次请求的隐含上限。真实任务第 33–36 次尝试在供应商请求前被拒绝 | 删除消息条数上限，保留消息结构、单段文本长度及传输资源边界。501 条消息真实通过桥接进入 mock ChatModel 的回归通过 |
| `ClineModelReceiptStore`、`CodingModelCallTelemetry` | 另外的六位序号正则、int 计数和统一 SUCCEEDED 投影，令本地拒绝看似供应商零 Token 成功 | 移除六位序号边界，使用 long；区分 FAILED、REJECTED_LOCALLY、providerDispatched 和错误。超过百万序号、本地拒绝回归通过 |
| Common 四份 `runtime/coding/*.schema.json` | Java 支持 unlimited 后，Schema 仍可能拒绝 -1／unlimited，反馈次数仍有 31 上限 | 契约限额支持显式 unlimited／-1；实际消耗仍禁止负数。反馈策略允许完整错误详情，保留旧策略的短摘要限制。Schema 回归通过；部署包包含资源文件 |
| MCP `PythonExecutionLogBoundary.persist`、`PythonManagedExecutionService` | API 取回完整文件时，MCP 已将 stderr 覆盖为省略摘要；API 层取消截断不能还原错误 | 新增隔离环境显式 `chatchat.mcp.python.preserve-task-error-details=true`，保留该任务实际捕获的 stderr；默认 false 保持旧边界，stdout 仍省略，拒绝符号链接。保留长 stderr 的回归通过；旧运行丢弃的原文不可还原 |
| `CodingProtocol`、`CodingTaskContract.OutputContract`、Java 模型／Python回调、Node `contract-results.js` | D1 旧单 JSONL 门禁拒绝多产物；requiredFiles 规范排序后 analysis.py 排在 rows.jsonl 前，错误选择主结果 | 动态分析只接受契约声明的支持类型；独立选择 rows.jsonl／唯一 JSONL，不再依赖列表首项。保留旧 D1 单文件规则，不降低结果内容校验 |
| Monitor `metrics.mjs`、API 监控投影 | Managed Python 工具账本不等于 Native 所有工具；本地模型拒绝不等于真实模型请求 | 分开展示 Native 工具、受管执行、模型尝试、确认派发、明确本地拒绝；修复次数取实际反馈，费用计量不完整时为未知 |
| Runtime Worker 镜像 | 只更新 Gateway 不会更新独立 Worker 内的 Native Server，fatal model.error 仍可能悬挂至外层租约失败 | 专用 rootless Executor 使用新 Worker `afe24354076b877ee2cf9061cbf5e3210952b16d2f2d4fc5862618bd52f84c02`，仅替换 server.mjs。Core、runtime-manifest SHA 前后完全一致；原 Executor 不变 |

补充回归：完整 Node 套件 262/262；Native／Monitor 66/66；Node 构建通过；最新 Maven 选择回归 BUILD SUCCESS（API 100 项，另有 Common、Agents、MCP 模块，避免重复汇总不同批次）。日志：`target/native-unlimited-node-all-schema-final.log`、`target/native-unlimited-node-build-final.log`、`target/native-unlimited-native-monitor-provider-final.log`、`target/native-unlimited-message-error-schema-tests-final.log`。早期失败测试日志同样保留。

`ToolExecutionModels.independent(model, remaining, cancelled, 0)` 的第四参数实际为 maxCallMillis，不是调用次数预算；独立模型解包 MeteredChatModel，从而避免把 Native 内部调用扣到父模型次数中，仍继承父截止时间、取消与容量治理。外层 `AgentOrchestrationEngine.DEFAULT_MAX_STEPS=3`、`AgentRuntimeGuard.MAX_CONFIGURABLE_STEPS=50` 属于父编排。`maxConsecutiveRejectedTasks=2` 是 RUN_CODING 新任务的拒绝后再派发保护，不是同一 Cline Session 的代码修复次数。保留这些边界，没有用循环新建任务模拟自主执行。

隔离 rootless Executor 继承 `allowUnenforcedPrototype=true`：宿主 rootless cgroup 条件不能证明 Worker 的 Docker 资源参数全部获得硬执行，报告必须保留这一限制。受管 Python 的原隔离授权和进程资源保护继续生效；未转为 rootful Worker 或扩大授权来规避问题。

2026-11-10 用户确认三端工具按功能等价口径比较，完整记录权限及环境差异。此确认不自动消除模型参数、实际 Core 哈希或结果正确性的差异，不能据此宣称严格能力对齐通过。

## 捕获层与真实请求的最终补充

- `chatchat-mcp-server/src/main/resources/python/runtime_capture.py` 原先不落盘原始 stderr，仅保留 64 KiB 内存尾部用于固定错误分类。实测证明，Java 的 preserve 开关不能保存尚未捕获的内容。提交 `1bacaaceb` 将同一隔离节点开关贯通 `PythonDockerRuntime.createManaged` 到捕获进程，在程序启动前打开不跟随符号链接的 stderr 文件；默认不变，stdout 不返回模型，4 MiB 边界显式记录截断和原始计量／Hash。归档策略只在捕获器实际声明详细捕获且存在真实文件时标注 TASK_ERROR_DETAIL_V2，避免旧捕获器被误标。
- 最新真实 `runtime-repair-1bac-1` 的第一条错误回执包含原始 traceback 和 `RuntimeError: ISOLATION_REPAIR_PROBE_20261110`，同一 Session 后续已继续修复并成功执行。其最终业务状态另见对照报告，不能只据此宣称整任务成功。
- `cline-agent-server/src/native-model-settings.mjs` 为可选的 Session 配置适配，缺省保留 Core 默认值；支持固定本轮对照的 8192 输出 Token／thinking／low，不改共享服务商和 Core。SDK provider 记录必须包含 updatedAt／tokenSource，否则迁移会丢弃推理设置。真实 HTTP 已验证最终 Native 与 Desktop 均带 `max_tokens=8192, reasoning_effort=low`；此前未生效的三次参数试验保留为诊断。Native 全套回归现为 69 项。
- 真实 `runtime-parquet-3bbc-1` 完成 37 次供应商请求，33–37 次均有成功回执，后因 1200 秒截止停止；`runtime-repair-3bbc-1` 达到 40 次模型请求／5 次 Python 执行后为 PARTIAL。说明已跨过次数上限，但不代表数据报告、业务复核或最终交付自动通过。
- 新捕获器 Python 回归 14 项通过；最新 MCP 边界 Maven 回归 BUILD SUCCESS；独立报告复算器回归 3 项通过。真实报告验算发现数值误写与辅助列混入源字段等失败，保留在 [能力对照记录](native-cline-execution-alignment-20261110.md)。
