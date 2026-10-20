# Runtime OS 数据分析性能专项审查

## 基线与测量纪律

源码基准为 Git `8e7a965d` 加本会话已有未提交修复。性能专项开始时的实际部署冻结为 API `b1d90944d585…`、MCP `efe1dcbfb54f…`、coding worker `95761f5fed27…`。这份部署已包含受控模板、批量快照、协议读取器、有界输入观察和 Runtime 业务语义裁决边界修复。它是本轮性能专项的直接比较基线，不能与原始 Git 包混称同一版本。

原问题保持原 Agent、真实模板、真实模型与真实数据。各组固定部署、串行重复；失败样本单列，不把失败耗时当成成功性能。P50/P95 记录样本数与计算方法，小样本分位数不作为生产尾延迟保证。Mock/单元测试只验证协议和门禁，不用于证明性能达标或业务正确。

用户确认：正确率依据现有 Skills、模板声明口径及原始数据逐项核对。每项结论记录来源、口径、判定与限制。该记录用于离线验收，不作为 Runtime 业务准入裁决。

已对直接基线第 2 轮的成功报告做一项原始数据复核：从该轮原始 `CHECK_DOCKER_IMAGES` stdout 的数据行数得 19 个镜像；从 `CHECK_DOCKER_PS_ALL` stdout 得 11 个容器，其中 `Up` 4 个、`Exited` 7 个，退出码分别为 255×5、137×1、130×1。报告相应数量与原始输出一致。退出码只能说明进程退出状态，OOM、配置错误或手动停止均需日志和主机证据再确认。该核对只覆盖这些明确结论，不把整篇报告自动记为业务正确。受控模板本身声明的命令和完整输出保留在该轮主任务原始事件中。

## 已确认的代码级瓶颈

补充直接基线 R9 的受保护 Runtime 模型回执（`att-1-ab164de6-cfe4-4f7d-b890-430f4c8563ea`）：Java 主 Agent 11 次模型调用累计 415337 ms，其中前 9 次规划、检索、模板选择覆盖、参数绑定与缺口整理累计 326181 ms；后 2 次统一分析调用累计 89156 ms。Coding 7 次模型调用累计 329971 ms。因此模型调用是已实测的主耗时；Java 规划链路本身与 Coding 同量级，单独优化 Python 启动不能达成 3–5 分钟目标。这些累计调用时间不是端到端关键路径，阶段可能交叠，需以 Trace 时间戳计算关键路径。

R9 的前 9 次 Java 模型调用依次为 56294、23129、27848、25470、72535、39995、18912、33650、28348 ms。它们来自 `AgentPlanner`、`ModelAssistedRetrievalBridge`（3 次）、`reviewInterpretationPlanToolResult`（2 次）、`auditTemplateSelectionCoverage`、`SchemaDrivenTemplateParameterBinder`、`PlanGapReconciler`。其中模板选择和参数绑定属于受控规划，优化不得将授权或绑定移交 Coding。R9 最终为 `PARTIAL`，并非完整正确率样本。

| 排序依据 | 实测证据 | 源码入口 | 结论 |
|---|---|---|---|
| Coding 模型时间 | 历史成功轮 R7：21 份持久化模型回执，合计 525864 ms；162728 输入 Token、66473 输出 Token | `ClineRuntimeModelBridge`、`ClineModelReceiptStore` | 模型调用时间是已确认的大项；此前口头合计 23 次有误，现按回执修正为 21 |
| 进入分析前的关键路径 | R7 从任务开始到首个自主分析回合约 345 秒 | `AgentOrchestrationEngine`、`InterpretationPlanRuntime`、`SchemaDrivenTemplateParameterBinder` | 包含规划、资产与模板候选复核、参数绑定和查询，不能全部记作数据库耗时 |
| 成功后继续循环 | 直接基线 R9 的第 4 次 coding 调用已获得结构有效结果；之后继续 3 次模型调用，合计 119263 ms，并再执行两次 Python | `runner.js` 的 `python_execute`、`afterRun` | SDK 未把已验证结果作为完成工具，导致结果已足够后仍继续分析；这是实测浪费，119.3 秒是该次运行的潜在节省，不是改造后的实测收益 |
| 无效文件探测 | R8 连续 3 次程序只打印，返回 `REQUIRED_OUTPUT_MISSING`；第 4 次模型返回非法工具参数，输出 16384 Token，达到配置上限 | `runner.js`、`ClinePythonCallbackController` | 执行器有数据而模型缺乏有效观察路径，会反复探索。已用契约声明的有界观察及统一读取器修复，不开放任意 stdout |
| 结果恢复异常扩散 | R8 恢复时将缺少结果文件异常直接抛给父任务，整轮失败 | `ClineCodingAdapter.completeVerifiedOperation` | 已改为真实停止后的结构化失败回执，由主分析 Agent 决定后续处理 |
| 并发边界 | 独立只读调用已有并发入口，但 `executeParallelReadOnly` 使用公共 ForkJoinPool，方法本身未设置独立并发上限 | `FailureIsolatedBatchExecutionLayer`、`TemplateExecutionLayer`、`ToolRuntimeService` | 应复用已有只读授权判断并限制并发；不能放宽写操作或依赖关系 |
| 缓存与推理观测缺失 | 当前 Coding telemetry 只公开 input/output Token；SDK 已提供 cached/reasoning Token 细项，当前代码未转存 | `CodingModelCallTelemetry`、`MeteredChatModel`、`ClineRuntimeModelBridge` | 历史缓存命中率未知，不能填 0；需补真实提供方回执字段 |

R8 仅一次失败诊断：679.6 秒。R7 仅一次历史成功诊断：1721.9 秒。两者版本不同，不计算二者之间的 P50/P95。历史 Runtime 强制业务审查在 R7 包含 5 次 coding 结果审查与 3 次报告审查；组件已按职责边界删除，不把删除业务越界裁决称作跳过必要的结构或执行校验。

## 已排除或尚未证实的假设

- `FinalSynthesisNode` 对已有模型最终内容走 `publishHarnessReport`，不另调模型重写。生命周期事件出现 `final_synthesis` 不等于新增模型调用，不能据事件名称认定重复推理。
- Python 的现有工具回合约数秒，包含启动、执行、轮询、归档及校验。它不是纯 Python CPU 时间，当前不能据此给出容器启动与代码执行各自占比。
- 缓存命中、模型排队与推理 Token 没有提供方记录时均标为未观测，不推测为 0，也不将输入 Token 全部认作未缓存 Token。

## 优化实施顺序

代码改造中的模型配置采用 `chatchat.coding.analysis.simple-model-name`、`complex-model-name` 与各自的 `*-reasoning-effort` 显式部署参数；未配置时沿用原任务模型。简单/复杂只按受控输入的数据集数量和行数判别，阈值由部署配置给定，不读取证券名称、客户号、模板 ID 或业务公式。所选注册模型和提供方身份写入不可变 Coding 任务快照；恢复时验证同一身份。推理配置仅在管理员明确设置时送到模型请求；提供方是否支持、缓存是否命中，均以真实回执为准。

只读批处理从公共 ForkJoinPool 改为有界共享执行器，默认 4 个工作线程、有限队列。它仍只接受 Runtime 已授权且声明独立的只读调用，保留声明顺序的结果槽位和中断取消。Docker 基线中的批处理声明为 `SEQUENTIAL`，故不能将此改动在该问题上的收益计作已实测；需在确有独立只读声明的任务中另测。

统一 Trace 投影复用主任务事件、Java 模型用量、Coding 模型回执和 Python 管理执行表及不可变执行回执。Python 的受理至容器启动、容器进程、进程结束至归档落库分别计时；最后一段同时包含日志保存、归档和状态更新，不能称为纯归档 CPU 时间。历史 Coding 事件遗漏结果引用时，仅在成功且存在结构校验回执时标注“由历史结构回执推断”，未知值保持 `null`。缓存读 Token 和推理 Token 均为提供方可选字段，缺失时保持未知。

Java 主 Agent 的历史纯文本模型调用只记录估算 Token，因为原 `MeteredChatModel.chat(String)` 丢失了 SDK 的原生 `ChatResponse` 用量。对仍沿用 LangChain4j 默认 `chat(String)` 实现的已注册模型，改用其完全等价的原生 `ChatRequest` 路径，以保留真实输入、输出、缓存和推理 Token 回执；显式覆写纯文本方法的模型仍走原路径。Node 的系统指令前缀现在只放稳定规则，任务目标、Skills 和快照配置保留在用户回合中，消除一份重复上下文并改善自动 Prompt Caching 的前缀稳定性。是否有缓存命中仍只按提供方实际回执判断。

1. 在现有 Cline SDK 上使用完成工具生命周期：真实停止且结果登记、结构校验通过后立即完成；没有结果文件仍是可修复失败。保留最后工具交互 checkpoint。
2. 在同一 `python_execute` 契约内提供精确代码补丁：匹配上一程序哈希和唯一代码锚点，重建完整代码后继续走现有代码冻结、执行、校验和审计。修复不编辑输入数据，不另建 Agent 框架。
3. 补齐统一 Trace：复用主任务事件、Java metering、Coding provider receipts、Python 执行回执；记录缓存/推理 Token 是否可观测、重试原因、模型和部署版本。阶段重叠不直接相加冒充关键路径。
4. 有界并发只作用于已经授权的独立只读模板，保留顺序槽位、取消、预算、错误隔离和真实停止证明。
5. 模型与推理配置只通过已登记资源及可信执行配置选择，按一般资源规模和明确复杂度配置路由；不根据证券名、客户号、模板 ID 或指标公式分支。未验证的提供方参数不强行启用。

## 直接基线结果与协议缺陷

同一 Docker 运维问题、同一 `hy3` 模型的直接基线共执行 5 次：成功 3 次、部分成功 1 次、失败 1 次。仅对 3 个成功样本计算端到端分位数，P50 为 701091 ms，P95 为 1059203.7 ms；成功样本的 Java 模型耗时 P50 为 438666 ms，Coding 模型耗时 P50 为 190907 ms，Python 容器内进程耗时 P50 为 5000 ms。该排序证明主要耗时在 Java 规划和 Coding 模型调用，不在 Python 计算本身。成功样本 Coding 调用次数 P50 为 6，Python 执行次数 P50 为 5；4 个进入 Coding 且可观测首轮结果的样本中，首轮成功率为 50%。提供方未返回缓存细项，因此基线缓存命中率保持“未记录”，不填 0。

第 5 个基线样本在进入 Coding 前失败。原始事件证明，计划重写器把服务端签发的模板发现分页游标放进了已改变查询条件的新步骤，MCP 正确拒绝 `Invalid template discovery cursor`。修复后，分页续查必须保持同一工具并复用上一已执行步骤的完整解析后输入，只允许游标采用服务端实际签发值；模型若移动步骤 ID，也必须以唯一的工具与游标对应关系识别，歧义时拒绝重写。回归用例覆盖“改变查询却复用游标”的路径。该约束属于协议完整性，不包含模板 ID、业务字段或业务公式。

候选 `deepseek-v4-flash` 使用冻结基线部署做了 1 次独立真实探针，耗时 814301 ms。它不与 `hy3` 基线合并计算，也不足以证明正确率；由于单次耗时未优于 `hy3` 成功样本 P50，本次优化部署保持原模型，以维持前后可比性。

## 复现入口

`scripts/benchmark-runtime-analysis.py` 从受保护认证文件登录，复用原始请求，生成每轮原始任务事件、Coding 模型/工具回执、统一 trace 及分位数摘要。它检查组内部署是否变化，并在父任务终态但子执行未停止时中止后续样本，防止重叠和虚假成功。

服务器证据目录：`/opt/runtime-os-docker-stage/evidence/template-controlled-20261020/`。专项直接基线目录为 `performance-baseline/`，采样进行中。达到 3–5 分钟及 30–90 秒目标与否，必须在优化后真实重复组完成后再判定。
