# Native Coding 执行限制与终止路径审查（未完成交付）

后续通用 S3 节点和独立真实执行／取消验证见 [最新实施记录](native-s3-gateway-20261111.md)。正式 Runtime SPI 路由的审计缺口仍保留，不将独立节点的成功外推为所有旧业务入口都已迁移。

分支：`feature/runtime-cline-cli-native-20261107`。本轮真实运行采用统一镜像的直接 Native 路径和 Java/Silo 服务夹具；**没有通过正式 Coding SPI 或旧 Workflow 的模型准入链，不能据此声明 Runtime 全路径限制已解除。**

## 已核对的终止条件

| 位置 / 配置来源 | 源码默认或固定值 | 生效路径及行为 | 当前状态 / 处理 |
|---|---|---|---|
| `CodingAnalysisProperties`：`chatchat.coding.analysis.max-model-calls` | 12 | `CodingAnalysisService.budget` → 持久合同 → `CodingBudgetMath`，预算耗尽阻止新请求 | 已有 `CodingLimit` 支持 `unlimited`；新服务夹具不经过该准入链，正式新节点尚待接入验证 |
| 同上 `max-tool-calls` / `max-code-executions` | 24 / 12 | 持久预算及代码版本分配，限制工具/执行次数 | 同上；不能把独立 Native 请求超过 12 次当成此路径验收 |
| 同上 `max-model-tokens` | 65536 | 累计准入预留与实际用量结算 | 支持 `unlimited`；累计计量与费用记录必须保留 |
| 同上 `timeout-seconds` | 300 秒 | 固定任务 deadline；恢复不刷新 deadline | 属资源保护。独立对照固定为 1200 秒，未改变生产默认 |
| 同上 `max-cost-amount` | USD 10 | 费用预算准入 | 保留可配置熔断；本轮 provider 回执未提供可信费用，记录 null，未编造金额 |
| 同上 `max-concurrent-tasks` / `max-consecutive-rejected-tasks` | 2 / 2 | 工作容量、连续被拒任务策略 | 容量不是单次 Agent 迭代数；正式 Workflow 路由仍待验证，不能忽略等价终止路径 |
| `ClineRuntimeModelBridge`：`chatchat.coding.cline.max-model-response-repairs` | 2 | 实际无效工具参数 / 输出截断回执 → `native-policy.mjs` → 下一模型请求前拒绝 | 已支持 `unlimited`。这不是 Python 代码修复计数；新直接 Native 夹具不经过该 Java 回执适配 |
| 同上 `max-output-tokens` | 8192 | 单次模型输出上限，影响代码生成及截断 | 保留单次输出约束。当前 Native/CLI 对照固定共同支持的 provider-default 策略；正式 Java 模型桥仍有此差异，尚未完成三端同参验收 |
| `CodingExecutionFeedbackService`：`chatchat.coding.max-auto-repairs` | 3 | 与合同代码执行预算、首次持久修复策略取最小值；STOP 回执被业务网关执行 | 已支持 `unlimited`。旧 Sandbox 适配仍存在；新单镜像正常执行应绕过该必经链，而非宣称旧路径已移除 |
| 同上 UNKNOWN / 普通异常分类 | 基于已停止进程回执 | 已停止、失败、有退出码及 manifest 的 UNKNOWN 转 `PROGRAM_ERROR`；未知外部执行仍需核对 | 现有源码已区分分类未知和执行未知；Native 本地 Python traceback 已真实返回 Core，未由 Java 因普通异常自动终止 |
| `DurableCodingTaskService.MAX_OPERATIONS` | 256 | 有限预算任务的操作记录容量；`unlimitedCounts(task)` 为真时绕过此容量门槛 | 已有绕过逻辑，不能仅看到常量就误报为 unlimited 的隐蔽请求上限 |
| 同上 `MAX_TASKS` / `EVENT_HISTORY` | 64 / 8192 | 每个 owner journal 任务容量 / 内存事件历史窗口 | 不是单任务模型次数上限；事件归档完整性仍需新应用接入验证，不可只依赖窗口内容 |
| `coding-agent-node/src/execution-limits.js` | 有限值按合同；unlimited → `undefined` | 旧 SDK `maxIterations` 适配 | 旧 SDK Worker 保留供原路径使用；新单镜像入口不启动它，未创建第二套 Agent Loop |
| 官方 `cline-session-factory.ts` | `maxIterations: undefined` | 官方 Standalone Agent Runtime | 保持 Core / Agent Loop；连续异常循环保护不改写为 Java 修复策略 |
| Native Server `autoApprovalSettings.maxRequests` | 100；读写/命令自动批准均 false | 原生权限行为，Host 按冻结工具授权逐次回答 | 不等同于模型请求硬上限。真实 Native 7 已发出 130 次请求；没有因 100 次退出，最终是外部 deadline |
| Native Server 权限 / 启动 / RPC 超时 | 180 / 90 / 60 秒 | 单次授权有效期、工作进程就绪、控制 RPC | 保留资源/协议保护；均非正常修复次数限制 |
| Node/Silo 传输 `retry` | 每个网络分段最多 3 次 | 不完整分段、HTTP 失败后重试；哈希或取消拒绝交付 | 是传输资源保护，不是模型/代码修复循环。真实 Silo 组件测试已验证有界重试与安全失败 |
| 真实测试容器 | 3 GiB / 2 CPU / 256 PID / 1200 秒 | Docker 强制资源、任务 deadline、真实停止 | 所有模型和工具次数不限；失败不自动新建 Session 或重启任务 |

## 普通异常与成功判断

Native Core 使用原生 `run_commands` 直接运行同容器 Python。真实 stderr/traceback、退出状态及完整原生工具结果保存在模型上下文和原始证据，不把一次 RuntimeError 当作整个任务结束。
独立 Native 样本已观察实际 TypeError、SyntaxError 等错误及自主修复；客户第 8 次没有 Python 异常，其真实业务指标修复不能代替异常修复测试。

`turn.completed`、退出码 0、Silo 上传成功都不建立业务完成。Java 重读不可变版本、复算 17 项指标、检查声明的正式报告、封存并确认进程停止之后，服务夹具才登记其历史数值状态。
完整报告复核另存最终业务结论：Native 3/6/9 和 CLI 4 等样本即使窄校验通过，也因未支持的叙述或错误事实被判完整失败；不修改原通过记录来隐瞒差异。

## 必须保留的未完成项

1. 新单镜像节点的应用 HTTP / Coding SPI、现有持久任务与模型准入链对接尚未完成；不能切换生产默认节点。
2. 正式 Java 路径的次数配置、模型响应修复策略、Workflow / Reviewer 等价终止条件仍需在新路由实际运行验证，配置或静态搜索不足以通过。
3. 原生 Session 跨进程恢复尚未接入正式受控恢复协议；重启后目前只标记 recoverable，不自动执行。不能把新 Session 重新运行冒充恢复。
4. 当前专用 rootless Executor 的 cgroup 能力问题、正式停止证据与迟到交付 fencing 尚需应用集成验收。直接 rootful Docker 夹具的资源强制结果不能冒充该生产适配已就绪。
5. 模型、费用、工具、自动修复计量与正式监控页面的完整联通尚待验收。缺失费用/修复计数记录为未知，不补造数据。

这些缺口及真实失败意味着最终提交推送和资源清理前置条件尚未满足。原始成功/失败证据留在授权测试主机并逐项通过现有 Java 资产服务归档 Silo。
