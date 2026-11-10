# 2026-11-09 晚间续接与服务器验证

本轮已同步远端并继续早上的 `feature/runtime-cline-cli-native-20261107`，基线 `5e67ae18f4d6fe4519ee123b421614646e37bdb4`。再次 fetch 后远端基线未变。`main` 与远端仍为 `2db9b59df`，未合并无关历史分支。用户补充验收重点为“需要正确输出图文报告”，因此以真实数据、图表数值、文件归档和页面呈现为本轮交付标准；以下记录不是全量稳定性或生产替换验收通过证明。

## 修复与证据

1. **Native 源码差异合并过大。** 原适配把远隔的修改和中间未改动的代码合成一次替换，可能超过既有单段 16,000 UTF-16 字符限制。现在通过唯一未改动行拆分实际差异，倒序构造精确补丁，仍由原 `CodeHistory` 验证。早上真实失败任务的两份源码离线回放得到两段补丁，old/new 长度分别为 7,770/8,635 和 6,027/9,962，按原验证器应用后逐字节等于 Native 实际修改结果。没有运行这份历史业务程序，也没有把源码回放算作业务成功。不可分的大段重写仍被原限制拒绝。
2. **必要上下文藏于大清单。** 当前 Native 请求直接携带 Runtime 原样提供的 `fileLayout`、完整 `inputVersions` 及 `analysisInstructions`。原始清单、来源、字段声明和文件 Hash 仍保留。第一轮真实探针曾重复翻读约 51 KB 的 `runtime-inputs.json`；不能把精简输入本身当作业务正确证明。
3. **输出上限信息缺失。** 模型没有配置上下文窗口时，`ClineRuntimeModelBridge.contextPolicy` 现在仍返回 Runtime 实际执行的 `outputReserveTokens`，不推测窗口大小。Native 启动读取既有预算接口并投影任务预算和模型上下文限制。本轮最终部署的新任务已实测在初始提示中包含该字段；实际单次输出上限仍为 8,192 Token。
4. **Native 响应修复授权未执行。** 遵守 Runtime 已返回的 `maxRepairAttempts`：最多允许规定次数的后续模型请求；成功响应结束该修复序列，超限停止任务。计数及停止标志持久化，重启不会恢复额度。没有新增模型重试循环，没有提高模型或任务预算，也没有改动官方 Core。
5. **监控容器继承错误健康检查。** `cline-native-monitor` 原检查读取不存在的 `/run/cline-poc.json`，造成持续 unhealthy。现在依据实际 `CLINE_MONITOR_CONFIG` 和 `/healthz` 检查；重建前后断言镜像、环境、启动命令、用户、挂载及 HostConfig 一致。旧容器保留供回滚，最终状态 healthy。
6. **Native 精简路径遗漏图文协议。** 原 `nativeUserObjective` 分支替换长目标时，丢失已有 `VisualReportMode.instruction`，而行 Schema 的 visualReport 仅声明为 object，不能供模型还原真实图文文件/布局协议。现在在既有 Native JSON 声明中完整保留 `visualReportContract`，包含原图文 Schema、文件引用、中文字体、数值/单位和布局协议，使用原本的分块与严格上下文验证。未开启图文时不附加这份协议；没有新增图文模板或修改原协议。

## 已执行检查

| 检查 | 结果 |
| --- | --- |
| Native Node 全部回归 | 54 项通过，0 失败 |
| 模板绑定与候选评估 | 44 项通过，0 失败 |
| 模型桥接与回调 | 47 项通过，0 失败；相关 Reactor 编译通过 |
| 图文合同、分析服务与产物验证 | 97 项通过，0 失败；相关 Reactor 编译通过 |
| 基础部署后真实浏览器 | 登录、任务布局、历史、失败状态、下载 Hash、刷新及辅助聊天验证通过；0 页面异常、0 控制操作 POST |
| 服务验证 | 用户登录成功；Gateway UP；监控 healthy |

日志位于本地忽略目录 `target/continuation-20261109/`。上述检查不是全量 Maven verify；早上记录的 Planner/Orchestrator 历史失败和两条历史 UNKNOWN 没有被本轮检查消除。

## 真实客户请求

| 任务 | 版本与结果 |
| --- | --- |
| `dca5a855-ff2a-37b6-8ecc-10dbb15b5419` | 本机 PowerShell 管道编码损坏中文；发现后取消，保留记录，不计业务验收 |
| `0b055b8f-50ef-397e-8046-da98fd833cd6` | 第一轮部署：找到 10 个模板候选并实际取数，Native 连续模型输出截断，最终 BUDGET_EXCEEDED；Python 尚未执行；Worker 与远端停止均已确认 |
| `d3d8f162-d678-35d3-8ef0-44ac1c88bec7` | 上下文直接投影后：再次发生连续输出截断；在部署输出上限信息和响应修复次数检查前主动取消，保留原始请求和回执 |

第一轮模板选择的实际指标：输入 3,073 Token、输出 3,699 Token，模型耗时 32,258 ms；10 个候选的等价 Schema 分支从 10 合并为 3，分支内容 12,509 → 3,785 字符，完整 Function Schema 4,268 字符。早上另一次实际探针为 5,913 输入 Token、约 29,964 ms。两次样本不是稳定性能验收；输入减少没有证明延迟下降。

## 部署与回滚

2026-11-09 21:09:14（Asia/Shanghai），更新既有专项 API 与 Gateway：5 个 Native 模块和 `ClineRuntimeModelBridge.class`，逐文件核对 SHA-256。部署前无 STARTING/RUNNING Native 任务，保留 23 个历史任务记录；未切换生产默认节点。

- 入口：`http://192.168.195.221:18346/monitor/`。
- 精确备份：`/opt/cline-native-business-20261108/backups/final-followup-20261109T130839Z/`。
- 监控原容器：`cline-native-monitor-before-health-20261109T125135Z`。
- 完整部署清单：`/opt/cline-native-stability-20261109/continuation-20261109/deployment-final.json`。
- Java class SHA-256：`d016a1e224bae64d3c8e6456cc470759a52e92c9293190c3f754b7bd33138ac6`。

回滚先核对活动任务，再仅停止专项 Gateway/API，按部署清单恢复备份中的对应文件，先启动 API 并核对登录，再启动 Gateway。保留全部任务、Worker 目录和远端执行证据。监控回滚使用保留的原容器，注意原健康检查错误也会恢复。

21:22:18（Asia/Shanghai）补充部署图文协议修复的两个 API class，登录与 Gateway 就绪验证成功；对应备份为 `/opt/cline-native-business-20261108/backups/visual-contract-20261109T132145Z/`，逐文件清单为远端 `deployment-visual-contract.json`。这是增量回滚点，恢复该备份只撤回图文协议修复。

21:32:11 将专项 Gateway 的既有 `editTool` 配置从 `apply_patch` 改为官方支持的 `editor`，用于减少实际出现的补丁语法错误。只重启专项 Gateway；模型、预算、其余配置与 Worker 镜像均不变。私有配置原件备份在 `/opt/cline-native-business-20261108/backups/editor-profile-20261109T133208Z/`；不将配置内容或凭据导出。恢复该文件并重启 Gateway 可撤回编辑方式变更。

## 未完成范围

最终版本的单 CSV 技术冒烟已完成：任务 `analysis-coding-58a186237f20161a1af05f95ed308c51639c1f4de6272911b29b30edf0b7063f`，Native/Runtime 均为 COMPLETED，`validationStatus=PASSED`、`businessValidationPassed=true`，官方 submit 成功，Worker 已停止，活动操作为空。实际进行了两次 Managed Python 执行：首轮数值验算通过但报告引用不合法，Cline 自主修正后再次执行，最终引用合法。共使用 11 次模型请求。通过 owner 内容接口独立下载全部 27 个任务文件并逐项核对大小和 SHA-256；另行用标准 CSV/Decimal 解析原文件，确认 3 行、合计 5.00，与最终 JSONL 的 `verifiedSummary` 一致。这是已有非金融小型 CSV 的技术冒烟，不计入客户业务验收分母。详见[逐文件验算证据](evidence/native-followup-20261109/csv-smoke.json)。

图文协议修复前客户请求 `66644747-18d9-3dee-8c88-c8d8eeaae7cd` 最终 BUDGET_EXCEEDED，没有 Python 完成回执。包含一次截断、Add File 补丁格式错误及提前结束被拒绝；不能只用“连续截断”概括该次失败。

图文协议修复后的首轮行情任务实际绘图，但引用与授权来源不一致；Cline 修复后又出现 Python SyntaxError，在结束该轮后才切换 editor 配置。原工作空间 `83aa82e7-d5be-4de3-9b74-c67b5e8553a5` 和节点 `96aed91d-1dcb-4c52-b6b7-15a9236001a5` 继续保留。

使用 editor 的真实行情图文验证工作空间为 `da9c543b-acde-42a6-b1c0-17f8ada3a5d1`，节点 `50d3eea8-d947-4c11-a431-bd84d8ed035a`。输入是已有授权行情 CSV，共 26 条记录；目标证券 301218 有 16 条，覆盖 8 个交易日。实际 PNG 与 Plotly 的 8 个点均与原 CSV 一致，下载 Hash 和真实浏览器呈现通过。但纵轴写了“元，未声明货币单位”，与未知单位矛盾；PNG 还采用等距分类横轴。Native 最终 BUDGET_EXCEEDED，未完成正式提交，不能认定正确图文报告验收通过。

用户已将本轮验收重点明确为正确图文报告，不把未给出完整口径的历史 30 次要求作为本轮新的完成计数；客户账户、行情、多文件、图文/Excel、完整恢复也不会因一个小型 CSV 技术通过而全部提升为通过。

最终部署、监控修复及真实浏览器摘要分别见[部署清单](evidence/native-followup-20261109/deployment.json)、[健康检查修复](evidence/native-followup-20261109/monitor-health.json)和[浏览器验收](evidence/native-followup-20261109/browser.json)。

私有请求、客户数据、令牌和完整模型事件仅保存在远端 `continuation-20261109/private/` 与本地忽略目录，不纳入 Git。

## 最终部署和验收结论（22:50）

**修复已部署，正确图文报告尚未验收通过。** 用户补充图文重点不等同于取消早上的其他业务要求；账户场景和原 30/30 门槛仍未通过。

后续修复保持原预算与官方 Core 不变：每次模型请求使用既有 Runtime 预算接口和 SDK 原有预算提示，预算守卫拒绝后持久化停止原因并结束任务；Gateway 镜像补入原有共享 helper。模型可见 editor Schema 如实声明官方已有的 6,000 UTF-16 字符限制。Native 输入别名缩短为 `inputs/input-N/文件名`，原 fileId、Hash 与 Sandbox 路径不变。选中的 `.py` 仅在原授权允许 CODE_EXECUTION 时建立字节相同的可编辑副本，并暴露 `sourceCodeFiles.nativeEditablePath`；不自动执行或生成业务逻辑，测试覆盖未授权和原字节一致情况。

21:46 Gateway 派生镜像为 `sha256:21d7d6ff57d17f6488dea23b6b6469029cb46b0b5a35690556e8e83843e4709e`，Worker 镜像保持原样。22:17 部署 7 个 Node 模块和 3 个 Java class；22:33 更新源码工作副本相关 3 个 Node 模块。后续增量备份如下：

- `/opt/cline-native-business-20261108/backups/runtime-budget-20261109T134651Z/`
- `/opt/cline-native-business-20261108/backups/reviewed-final-20261109T141638Z/`
- `/opt/cline-native-business-20261108/backups/source-working-copy-20261109T143320Z/`

撤回全部本轮修改需要结合前文各层备份逆序恢复，最后一层备份不包含完整原始部署。原 Gateway 容器和监控容器均保留。未替换 `runtime-os-*` 生产实例，未推送代码或创建发布提交。对应部署清单见本目录 evidence 下的同名 JSON。

真实图文尝试使用已有授权原始 CSV，没有伪造业务输出或向模型提供预计算答案。后续源码复用使用此前真实执行附件中的原始程序，按原字节上传为支持的 `.py` 输入，仅用于修复验证，不能计为从零生成的稳定性通过。

| 验证目录 | 实际结果与验收判断 |
| --- | --- |
| market-editor | 实际 PNG/Plotly 数值一致，但单位错误、BUDGET_EXCEEDED，未通过 |
| market-concise | 实际 Python 输出可审阅，但纵轴只有 close，未满足单位声明；无效重复预算请求后停止，CANCELLED |
| market-pro | 模型输出截断修复次数耗尽，FAILED，无合格图文报告 |
| market-max | 只执行诊断程序，缺少 rows.jsonl；后续超时 UNKNOWN，Worker 已停 |
| market-wire-final | 模型仍生成超过 editor 限制的修改，后续精确锚点不匹配；预算守卫停止，FAILED，无 Python 完成报告 |
| market-reuse | 原程序按字节交付工作副本，但模型编辑锚点与源码不匹配；预算守卫停止，FAILED |
| market-reuse-max | qwen3.8-max 复核同一源码，供应方请求超时；UNKNOWN，Worker 已停，活动操作为空，保留用量对账状态 |

最终现场复核时间为 2026-11-09 22:50:37（Asia/Shanghai）：API、Gateway、MCP 运行，Gateway 认证健康检查 UP，两个监控容器 healthy。当前 owner token 读取真实工作空间成功；无 STARTING/RUNNING Native 任务，部署源码与本地逐文件 SHA-256 一致。共有 5 条 UNKNOWN，Worker 均已停，其中一条历史记录仍保留未对账 operationId，不能根据停止状态清除。详见[最终现场状态](evidence/native-followup-20261109/final-observation.json)。

当前阻塞是模型仍不能在原预算内可靠完成准确编辑、实际执行与正式提交，已有图文还存在语义标注错误。回归通过和服务健康不证明这些问题已消除；不交付错误报告作为正确结果，不继续重复同根因测试填充成功分母。

## 2026-11-10 代码归档

用户明确要求为 main 打标签，并确认当前代码继续保留在 `feature/runtime-cline-cli-native-20261107`。main 的原提交 `2db9b59df` 标记为 `baseline/main-20261110`；本报告对应的源码、测试和脱敏部署证据作为当前进度提交到任务分支。提交前 Native 54 项回归再次全部通过。该代码归档不改变上述图文业务验收未通过的结论。
