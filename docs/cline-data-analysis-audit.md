# Cline 数据分析调用链与 Token 审查

审查基线为 Langchain4j-Chatchat `33d00126230e274be14b8a0990b92ea2543efe2f`，Cline 上游源码固定为 `8075d2e35cf5cad67c24f180c80a05d311b74a3e`。生产样本为 2026-11-23 的 LiveData InnoDB 分析，父任务 `31497979-75d3-338e-bb90-8c1462a2e296`，分析 Worker `92d206c7-5c08-489c-9083-c1ada12009d3`。通过只读方式在生产侧对该 Worker 的供应商回执、原生工具事件和工作区文件做聚合，文档仅保留计数和诊断字段。

## 851,021 输入 Token 的构成

生产 `provider-events.jsonl` 有 22 条完成回执，21 条提供用量。确认输入 851,021，输出 20,702，缓存读取 723,072，占已确认输入的 84.97%。该供应商的缓存读取已经计入输入，差额为 127,949，缓存写入和失败请求用量缺报。22 个请求 SHA-256 均不同。累计模型请求耗时 762,234 ms，一次连接故障占 331,135 ms。

| 请求内容 | 21 次累计观测字符数 | 按字符权重分摊的估算输入 Token |
|---|---:|---:|
| System / developer | 89,187 | 28,468 |
| 当前用户消息及其任务注入 | 267,603 | 85,418 |
| 其余历史消息及模型工具调用参数 | 410,181 | 139,748 |
| 工具定义 | 211,176 | 67,407 |
| 工具结果 | 1,611,288 | 529,980 |

字符数为 Broker 对实际串行化前请求的 UTF-16 字符计数。对应序列化请求累计 3,174,664 UTF-8 字节。估算在每次请求内，以五类观测字符之和为分母，按字符比例分配该次供应商输入 Token，再跨请求求和。中英文、代码、结构化字段和协议开销的真实 Token 密度不同。供应商没有返回各类 Token，分类估算不能用于计费。Skills 正文和业务摘要嵌入消息的部分没有单独的供应商统计。

工具细分使用原生工具终态的序列化结果长度，按完成时间累加并与各次请求的 `toolResultChars` 对账。其余差额每次 0–4 字符，对应数组封装边界。仍用上述字符权重方法估算。

| 工具结果来源 | 跨请求累计近似字符数 | 分摊估算输入 Token |
|---|---:|---:|
| JSONL 行文件返回，含行号、标记和封装 | 1,217,925 | 约 400,050 |
| 供应清单及各来源上下文 JSON | 264,057 | 约 86,127 |
| `skills` 工具正文 | 57,684 | 约 19,395 |
| `editor` 回执 | 50,090 | 约 17,253 |
| `run_commands` 失败回执 | 21,461 | 约 7,132 |

第一次请求输入 6,695，工具结果为零。前三次 `read_files` 依次返回 2,285、12,131 和 65,314 字符，下一批请求中的工具结果依次达到 2,285、14,416 和 79,730 字符。第 4 次请求输入从 11,835 升到 33,579。最后一次成功请求包含 101,961 工具结果字符和 49,908 历史字符，输入 61,511。一次 10,394 输出 Token 的调用之后，下一轮历史从 2,555 增至 26,171 字符。前缀缓存解释了重复上下文为何仍出现在总输入。不同请求摘要说明观察到的是继续对话携带历史。

四次文件读取覆盖全部九个 JSONL 来源及清单和上下文文件，其中供应清单和 source-3 被重复读取。第三次调用读取四个 JSONL 来源及其上下文，返回 65,314 字符。一个单行的 source-3 输入文件有 14,363 字符，通过工具读到的结果约 2,021 字符，并带截断标记。不能用该结果代替完整 InnoDB 数据。现场发现并注册 18 个 Skill，正文按需调用一次 `python-data-analysis`，返回 4,807 字符；其后在历史中重复携带。工具轨迹没有成功的 Shell 调用。没有证据表明 Python stdout 是这次 Token 增长的主要来源。

## 真实链路与已有能力

| 层 | 源码与行为 |
|---|---|
| Java 数据供应 | `chatchat-api/src/main/java/com/chatchat/api/runtime/coding/analysis/NativeAnalysisBridge.java:34,58,145,161`，`primary` 到 `execute` 冻结来源文件及数据供应清单，交付 JSONL、上下文和完整性信息。当前指令让模型读取清单和上下文，随后由模型决定是否直接读取行文件。 |
| 授权和传输 | `NativeCodingTasks.java:34–52` 校验身份并创建任务契约。相同目录下 `NativeSiloTaskService.java:72–155` 捕获已授权的输入版本、工具和交付契约。 `cline-agent-server/src/s3-task-transport.mjs:47–100` 验证 grant、输入清单，落盘后保护输入，停止 Worker 后核验并上传结果。 |
| 运行与 Skills | `cline-agent-server/src/governance-server.mjs:192–239` 准备输入、按授权请求 Skill 包及启动标准会话。 `native-capability-files.mjs:32–62` 准备工作区 Skill 和发现信息。已存在 `coding-agent-node/skills/python-data-analysis/SKILL.md`，指引 Python、分块、指标及报告。生产实际 Skills 工具调用证明该 Skill 参与了分析。 |
| 原生文件、搜索与 Shell | 上游 `sdk/packages/core/src/extensions/tools/executors/file-read.ts:58,149,216–257` 使用文件大小、窗口和文本行限额，普通文本路径可以读入结构化数据。 `output-limits.ts:20–52` 的命令、读取、搜索目标上限均为 48,000 字符，多文件的聚合回执可以超过单文件阈值。 `search.ts:389–519` 会读文本并返回匹配上下文。 `bash.ts:401,454,733,1137–1161` 缓冲命令输出后返回并选取默认 shell。命令证据另外由本仓库 `native-command-observer.mjs:11–29` 和 `command-evidence.mjs:50–65` 保留。 |
| Managed Python 路径 | `cline-agent-server/src/business/runtime-tools.mjs:46,70` 已有逻辑引用读取和 `python_execute`。 `business/prompt.mjs:25–26` 指导读取有界格式样本、Schema 并计算完整文件。Managed 路径归档 stdout/stderr，模型接收执行反馈。生产样本走前一条原生 Silo 路径，原生 Shell 的返回仍可能成为普通工具结果。 |
| 请求及监控 | `governance-server.mjs:132–179` 应用模型与推理策略、提交请求、流式透传并记录供应商回执。 `model-receipt.mjs:5–46,61–98` 区分确认 Token、缓存与请求形态。 `chatchat-api/src/main/resources/coding-monitor/app.js:126–141` 汇总供应商回执。上游 `sdk/packages/core/src/extensions/context/compaction.ts:329–388,715` 处理上下文预算和压缩，不能代替上游工具和业务数据治理。 |

## 已确认问题与待验证风险

1. 行数据经过通用文件读取进入模型并被后续请求携带。减少重复携带最有价值的是给模型提供计算所需 Schema、可核验的轻量探查结果和产物引用。原始文件应作为程序输入完整保留。
2. 本次任务六次 Shell 全部退出码 1，均出现账户不可用提示。停止后的生产 Worker 的 `/etc/passwd` 证实 UID 10001 `runner` 为 `/usr/sbin/nologin`。基础 `deploy/docker/python-runtime/Dockerfile:43–47` 使用该 shell；`deploy/docker/production/Dockerfile.native-worker` 仅设置 `SHELL=/bin/bash`；既有独立运行时构建 `cline-agent-server/docker/Dockerfile.python:14` 另有 `usermod --shell /bin/bash runner`。这解释了本次 Shell 无法承担文件探查和完整计算。编辑工具有十次操作、两次失败，应保留源码和失败诊断用于修复。
3. 截图中失败的 `3606819b-0694-4ece-aab6-14ae39525ec9` 回执记录 HTTP 200、331,135 ms、`UPSTREAM_CONNECTION_FAILURE` 和用量缺报。证明流已收到 HTTP 响应之后断开。当前记录未拆分首字、最后活动时间和上游重试，不能定位到具体代理或断流机制。Worker 随后 `native-core-unavailable` 停止，父任务继续停留在 `WAIT_MODEL`，经授权取消收尾，业务验收 `NOT_RUN`。
4. 当前只确认此案例的 JSONL 路径。CSV/Parquet/Excel、原生搜索和 Shell、通用 MCP 的大输出风险须通过隔离用例验证。不能把工具审计上限当作完整性或数据外发约束，也不能用简单字符切片替换完整计算。

## 最小整改及影响

1. 恢复生产 Native Worker 中 Runner 的可执行 shell，沿用独立构建的 UID、工具权限、隔离 daemon 和既有命令观察。新增通过账户默认 shell 启动 Python 的构建检查。
2. 沿用现有 Skill、Java 源快照、原生文件读取和 Silo 契约。把逐来源的文件格式、Schema、行数及来源完整性写进小型结构清单。CSV/JSONL 分批探查，Parquet 优先读 footer，Excel 使用只读迭代。缺少元数据显式标记待计算。为数据分析任务提供程序化探查的正常入口，完整数据和大明细留在文件。
3. 在数据分析任务的原生工具边界实施按来源和用途区分的结果策略，保留源码读取、编辑、错误定位和任务恢复。受控样本使用全任务预算并保留数据出处。原始数据路径治理需同时覆盖 read/search 与支持命令执行的回传边界；通用编码任务沿用现有行为。不得用文件名或固定业务任务硬编码绕过授权。
4. 复用既有结果 Schema 和 Silo，补充结构化指标、质量摘要、计算覆盖、证据引用。摘要限额作用于模型可见输出；原始输入、大明细和完整审计按既有授权交付。统计区分有界探查与完整扫描。发布前验证 Java 与 Cline 的错误结束状态能够对账。
5. 请求监控补充消息来源标签、工具结果大小和阶段耗时。超时应分别度量连接、首 Token、流空闲和整体时间，由明确的一层负责重试。取消应传播。生产上线需单独授权。回滚按完整发布或独立镜像回滚，保留数据和之前的契约。

## 回归清单

- 原生生产镜像以 UID 10001 通过默认 shell 执行 Python，命令失败后保留诊断和可编辑源码，权限拒绝和取消继续生效。
- 用小 CSV、十万行 CSV、一百万及一千万行 Parquet、多表关联、大型 Excel、JSON/JSONL，以及空文件、缺字段、冲突、损坏和缺失输入验证探查与全量计算。统计结果与确定性基准核对。
- 以真实 InnoDB 长单行验证程序获得完整文本，模型只获摘要和证据引用。检查反复读片段、搜索、打印、命令转发和 MCP 输出边界。
- 检查多租户、授权撤销、快照哈希校验、路径隔离、Silo、失败对账、取消恢复和普通 Cline 编码回归。
- 同模型、同数据、同任务、同缓存条件对比累计 Token、缓存、正确性和产物。70% 为验收目标，当前表格只提供改造前证据。对比试验和完整 P0 闭环仍需隔离环境执行。

## 已实施的首批整改与验证

- Native Worker 构建沿用独立镜像的 Runner Shell 配置，增加以 UID 10001 通过默认 Shell 启动 Python 的检查。用相同 Python 基础镜像在无网络、无生产挂载的临时容器复现原账户错误，应用改动后得到 Python 3.11.18。
- JSONL 供应清单从已有的完整快照写入过程采集顶层字段名，最多交付 16 个短字段名，超出时标记 `fieldInventoryComplete=false`。JSONL 内容和计算覆盖由原快照契约负责。字段元数据不替代声明类型、字段含义或完整性回执。
- 既有 `python-data-analysis` Skill 增加逐行探查示例，检查快照行数、缺失值和空值，并限制错误诊断到行号。详细计算结果由现有文件交付机制承载。
- `NativeAnalysisBridgeTest` 两项通过，其中 20 个来源和万行分页用例检查了末页新增字段、字段清单超限、原始行数和样本值隔离。Cline 回执、Skills 和命令证据测试 14 项通过、1 项因本地平台跳过。Skill 示例的正常与行数不匹配用例通过。
- 原生 read/search/命令输出的按数据用途管控、其他格式与大数据量探查、结构化业务结果验收和同条件 Token 对比属于后续 P0。上述局部测试不构成生产或业务端到端验收。
