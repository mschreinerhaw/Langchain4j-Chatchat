# Native Cline Coding Agent Node 业务与监控统一交付

实际执行日期：2026-11-09，北京时间。文件名沿用任务书约定。**本轮最低可用标准的九项通过，限独立开发测试环境；不等于全部业务场景、全量回归或生产替换验收通过。**

运行入口：<http://192.168.195.221:18346/monitor/>。用已有授权 Runtime 账号登录。无需通过命令行创建会话、发送消息、批准工具或下载结果。

## 三个实际成果

1. **浏览器直接对话。** 可创建官方 Native Session、发送自然语言任务、连续对话、批准真实工具请求、停止任务、查看历史并刷新恢复。本次在最新版页面重新创建一个真实 Session，完成两轮对话；不是只有历史截图。
2. **可观察真实执行。** 页面分开展示 Native/Core/Runtime 状态；逐次模型表、工具选择、源码 Diff、MCP、Python operationId、实际错误和归档均可查看。实时更新保留已展开的错误与日志，切换 Session 不沿用上一任务的源码或文件内容。
3. **完成一个 Runtime 数据分析任务。** 已有浏览器派发的真实单 CSV 任务经过 NameError、自主修复、Managed Python 再执行、官方结束工具、Runtime 独立验算和归档，最终 COMPLETED。本次核对执行链源码一致，使用最新版页面重新读取真实状态、打开错误日志和下载报告；没有再派发该任务。

## 最低可用标准逐项验收

| # | 标准 | 结果与证据 |
| --- | --- | --- |
| 1 | 浏览器接收真实编码任务 | 通过。新建 `native:4b871368-aa9b-4a05-b25a-a89e3791465e`，页面实际发送请求并调用官方 Core，见 [首次对话](evidence/native-cline-delivery-20261109/fresh-first-turn.json)。 |
| 2 | 连续多轮对话 | 通过。同一官方 Session `1794168627797_0brih` 完成两个不同 taskId，见 [第二轮](evidence/native-cline-delivery-20261109/fresh-followup.json)。 |
| 3 | 实时模型、工具与任务监控 | 通过。新会话两轮共 6 条 Core 轮次观测，业务任务 5 条 Runtime 模型回执；来源明确，未知值不填零。见 [新会话模型截图](evidence/native-cline-delivery-20261109/fresh-models.png)及 [浏览器复核](evidence/native-cline-delivery-20261109/browser/browser-delivery.json)。 |
| 4 | 真实读取、修改源码与授权工具 | 通过。此前真实 Native 编码会话先搜索/读取、运行失败测试，再 apply_patch 修复并重测成功；测试断言 Hash 不变。见 [独立检查](evidence/native-cline-monitor-20261109/native-independent-check.json)和 [实际事件](evidence/native-cline-monitor-20261109/native-direct-events.json)。本次新会话只读复核同一源码和测试。 |
| 5 | Runtime 分析、验证并归档 | 通过。任务 `analysis-coding-3bfb69e23343c4833397b7aa70b4510bfc56e11cc28071a4a1512dbac038db8b`，Runtime COMPLETED、validationStatus PASSED，25 条归档记录经独立校验。 |
| 6 | Python 失败和修复可观察 | 通过。同一业务任务首次 FAILED/NameError，Cline 修改代码，第二次 SUCCEEDED/PASSED；最新版页面可打开绑定首次 operationId 的 stderr，并持续阅读。 |
| 7 | 刷新后持久历史可恢复 | 通过。新 Native 会话、既有 Runtime 成功任务及 UNKNOWN 历史均有真实浏览器刷新/离线重连检查，没有重建任务。 |
| 8 | 取消与异常不伪装成功 | 通过。新会话真实取消请求先为 CANCELLING，最终状态及实际停止事件另行核对；历史 UNKNOWN、BUDGET_EXCEEDED 保留。见 [最终取消观察](evidence/native-cline-delivery-20261109/fresh-cancel.json)、[进程和源码复核](evidence/native-cline-delivery-20261109/fresh-native-independent-check.json)。 |
| 9 | 代码提交、推送和真实证据归档 | 通过。统一集成到任务指定分支；构建、单元/关联回归、真实浏览器和真实执行证据均入库，SHA 清单随附。 |

这是验收条目 **9/9**，不是业务成功率 100%。真实业务的尝试分母见下文，失败不删除、不重算。

## 用户操作路径

- **直接对话：** 登录 → “新建测试” → Native 原生编码 → 选择受控工作区 → 创建 → 在对话框输入任务。测试环境默认工作区为已具备 Node 测试工具的 `acceptance-node-project`。有真实待批工具时点击批准/拒绝；下一轮直接继续输入。
- **观察过程：** Model Calls 看调用记录，Tool Calls 看工具与失败，Code / Diff 看实际修改，Python Execution 看回执及 stdout/stderr，Files / Artifacts 下载归档。右侧显示模型、工具和 MCP 时间线。取消处理中按钮禁用，最终停止必须读取真实结果。
- **Runtime 业务：** 新建测试选择 Runtime 模式、授权输入及已有模型，输入业务需求。需要精确 CSV 验算时显式选择对应合同和数值列；通用分析的结构校验不自动升级为业务正确性通过。
- **查看已交付任务：** [打开成功任务](http://192.168.195.221:18346/monitor/#runtime%3A770cd052-d887-496f-b5a5-b29b666fae34)。登录后保留该任务定位。完整使用与接口说明见 [Monitor README](../../cline-agent-server/monitor/README.md)。

## 当前版本和实现

目标分支：`feature/runtime-cline-cli-native-20261107`。首次检查远端时业务 HEAD 为 `2d0cf16189aaa74295575db148ecdbe2780d94a6`，监控 HEAD 为 `102af167066ca02b95d670195a5cfbf967c6836d`，main 为 `2db9b59dfbb3599f7e4e48a830fb6c7361beccc6`。先 fetch、检查干净工作区及源码，再合并；未把旧报告当成最新构建结果。

| 阶段 | 实际提交 / 作用 |
| --- | --- |
| 统一集成 | `35a2e9803431cfa8cba098662aa11d7ac1a51313`，将独立 Monitor 分支合并到指定业务分支，无冲突，无旧 SDK 监控依赖 |
| 模型观测 | `66036bb955c847d4d19cded6fc1e8e9c2bbd561a`，按真实 taskId 隔离 Core 轮次，模型表显示用量、观测耗时和错误 |
| 日志可读性 | `78bfbcbac8d81184f4f2911bbdc09f74ebd22dfa`，实时更新保留详情展开及已取得日志 |
| Session 关联 | `e25314bf9eb47dddc6fe566fa90a1b51003087fc`，源码/文件请求绑定发起时的 Session 与标签，丢弃过期响应，增加只读浏览器验收脚本 |
| 取消显示 | `3c6d918be0d2490712e4e1baf23836967544c875`，取消处理中禁用重复取消；这是当前部署的 Monitor 实现版本 |

当前 Java 业务及 Native 执行链与真实成功任务的 `ca503a25e56342aba9c90240aeb0087b3d9e9bc0` 在 `chatchat-api/src/main/java`、`cline-agent-server/src` 范围内无源码差异。本轮重建了完整 Maven 项目，部署更新只涉及独立 Monitor；没有为重复制造成功而重启/重派业务执行。

沿用官方 Standalone **v4.1.26**，原始 checkout `a459f1d04dea030833b558be645011ab739c463b`。不修改 Agent Loop，不回退 SDK Runner，不修改共享模型/默认执行内核，不提高预算。Runtime 业务模型上限仍为 12 次，成功任务实际用了 5 次。

Monitor 只读取现有持久日志、Native API、Runtime 模型与 Python 回执、授权归档。没有第二套调度、预算、编码策略或文件存储。授权输入、独立 CSV 验证器、stop policy 和完成机制沿用已实现业务链。模型计费与业务终态的权威仍为 Runtime；Native 直接模式是开发对话诊断，不冒充 Runtime 计费。

## 本次新的 Native 浏览器对话

Transport Session：`4b871368-aa9b-4a05-b25a-a89e3791465e`；官方 Session：`1794168627797_0brih`。

第一轮 taskId `e03d065b-0519-4223-98d6-f637d8f67c90`，原始消息：

> 读取当前项目源码并运行现有测试。报告实际测试结果，说明当前加法实现；本次只检查，不修改源码或测试。

Cline 自主发现文件、读取源码并执行授权的 Node 原测试。5 条实际 Core 轮次观测，4 次真实权限批准。第二轮 taskId `645835ed-1c15-40df-841e-9f955f814146`：

> 请在同一会话中解释 calculator.mjs 与测试文件的关系，只引用已经实际读取的源码和刚才真实运行的测试结果。本轮不要再执行命令或修改文件。

第二轮使用同一官方 Session，再产生 1 条 Core 轮次观测。两个浏览器脚本均通过，刷新及断线后身份和历史保持。复用脚本的返回标签均为 `PASS_NATIVE_FIRST_TURN`，第二份记录中的真实消息、taskId 和 Session 明确证明它是续轮，不计作第二个 Session。

此前的原生自主修复仍由独立证据支持：源码从 `75cfacb7faac086c50b23ac4b29a709eb8680999e6756f620ca76d42aba07cab` 改为 `5b63136552577a64d788dc3cd4552739d0d60f9e1adb63ec4dfb6932d56fc75d`；测试文件始终为 `9f6c556f2f9e83f1dc63457388376ebcc56db5253da79991b5e0943ab838bfeb`。本次只读复核前后再次核对这两个文件的 Hash。

取消在第二轮完成后通过浏览器发起，用于停止并释放真实 Worker；**不是活跃模型调用中取消的新增覆盖**。最初取证脚本错误地要求 POST 接受后立即为 cancelled，实际为 cancelling；该断言失败保留。随后只读查询和进程核对确认最终 cancelled，没有发送第二次取消或替代执行。

## Runtime 完整业务交付

这是授权的非金融技术 CSV 分析，原始文件 48 字节，3 行，列 `category,value`，精确 Decimal 合计 **5.00**。原始数据不是行情、账户交易或金融样例金额；只用于本轮 CSV 业务入口验收。

| 标识 | 实际值 |
| --- | --- |
| Runtime taskId | `analysis-coding-3bfb69e23343c4833397b7aa70b4510bfc56e11cc28071a4a1512dbac038db8b` |
| Native transport / 官方 Session | `6251c209-38c1-4a3b-8d42-97cb5d45093d` / `1794166330805_m44zy` |
| Worker | `770cd052-d887-496f-b5a5-b29b666fae34` |
| 首次 Python operationId | `cline-491d3657cccb87eb35e5eb57ba031ec895f2965c0a198783ada53222f6f96079`，FAILED，NameError，exit 1 |
| 修复后 operationId | `cline-873982245d2d3a25b26e7fc2524a9627b89a51eecaa4974a80857b1b900f3953`，SUCCEEDED，exit 0，validation PASSED |
| 官方结束工具 | `submit_and_exit`，toolCall `call_84628f9a858e478b9e965735`，Native event 91 |
| 最终状态 | Core TURN_COMPLETED，Native COMPLETED，Runtime COMPLETED；workerStopped=true、activeOperations=[] |

首次源程序只有未定义函数调用，真实错误由 Managed Python 返回。修复后的完整 2855 字节程序由 Cline 的 apply_patch 生成，Java 没有编写修复代码、决定工具顺序或提供预期计算结果。Runtime 使用注册的 `runtime-csv-count-sum:v1` 验证器重新校验不可变输入、独立解析 CSV 和验算；仅显式选择此合同的任务启用。

独立下载检查的 25 条归档记录全部 Hash、大小和可读性匹配，包含输入、源码、日志和回执，不能理解为 25 份报告。另从原始 CSV 独立重算 3 行、合计 5.00；Markdown 文件与结构化结果一致。原始业务请求、5 次模型回执、工具选择、实际修复和两次执行的完整证据见 [前次联合执行报告](native-cline-monitor-acceptance-20261108.md)及 [独立验算](evidence/native-cline-monitor-20261109/joint-r4-independent-check.json)。

| 实际文件 | 字节 | SHA-256 |
| --- | --- | --- |
| [原始 CSV](evidence/native-cline-monitor-20261109/deliverables/original-0.csv) | 48 | `fcb74b46f4042569e233515f15ea92342b8e019dd9531e0e9b7e4c92d37e9fb9` |
| [Markdown](evidence/native-cline-monitor-20261109/deliverables/report.md) | 477 | `d32abc08fc777dbc08958723775e6f5602d4d7f7d321a68b9e6c15d187fed5c2` |
| [JSONL](evidence/native-cline-monitor-20261109/deliverables/rows.jsonl) | 1406 | `73870a63d19a13338a4b5a8a5ff2ab1a9df134e9d76bbb7750be23ede4301eff` |
| [Python](evidence/native-cline-monitor-20261109/deliverables/joint_analysis.py) | 2855 | `92513b152a135b1738d802ac1ab1072442cac84ff110f6c1426c51e3c4e13ccc` |

当前页面再次真实点击下载，取得 report.md，Hash 相同；打开首次执行的 stderr 后，等待实时更新仍保留展开及内容。刷新和离线重连前后仍为相同 taskId、2 次 Python、5 条 Runtime 模型回执，没有新增业务调用。

真实业务交付率保持 **1/4=25%**；前三次预算耗尽的任务保持失败。含更早阶段 0/9，合计 **1/13≈7.69%**。本次 Native 新会话、续轮、取消、只读回放、独立复核均不加入 Runtime 业务分母。

## 构建、回归和部署复核

- 当前集成分支完整 Maven package：**20 模块 SUCCESS**，6 分 19 秒，2026-11-09 04:05:10 +08。真实执行 API Vite 构建及 MCP npm ci/build；API 依赖安装复用现有 lock 对应安装，未省略前端构建或资源校验。
- Java 关联回归：**78/78**，0 failure/error/skip，2 分 24 秒，04:08:43 +08。涵盖 UserAssetControllerHttpTest、CodingTaskMonitorControllerTest、CodingAnalysisServiceTest、CodingCsvMetricObjectiveTest、CodingCsvAggregateVerifierTest。
- 最终 Node 集成回归：**42/42**，0 failure/skip。新增的模型观测检查覆盖不同 taskId 中重复 iteration=1、缺失 usage、迟到跨轮 usage、真实 error，不把累计 Token 当单次 Token。
- 真实浏览器复核、新建会话、续轮、历史 UNKNOWN、下载及实际取消证据分别保存；单元 fixture 不计入这些真实验收。
- 首次 Maven 命令因 PowerShell 拆分未引用的 `-Dfrontend.install.skip=true` 参数失败；修正命令引用后完整构建通过。初次只读浏览器脚本未先展开 details 就等待日志响应，脚本超时；修正脚本后实际日志读取及保持通过。这些失败保留，不计作业务失败，也不伪造为首次通过。
- 前阶段全量 verify 的 52 failures、36 errors 及 JVM fork 异常未闭环。关联回归通过不代表全量 verify 通过，不能据此批准生产替换。

新构建与浏览器证据位于 [本轮证据目录](evidence/native-cline-delivery-20261109/)，含 [构建索引](evidence/native-cline-delivery-20261109/build/index.json)、[Hash 清单](evidence/native-cline-delivery-20261109/SHA256SUMS)、[部署核对](evidence/native-cline-delivery-20261109/unified-monitor-deployment.json)、[最终状态对账](evidence/native-cline-delivery-20261109/unified-final-status-reconciliation.json)。日志以 UTF-8/LF 入库，索引分别记录原日志与保存版本 Hash。

[版本核对](evidence/native-cline-delivery-20261109/versions.json)同时记录 Git blob 与部署字节 Hash。首次逐字节核对定位到 Windows archive 将 app.js 的 LF 转为 CRLF；核对完整 CRLF 字节变体一致后通过，记录该换行转换，不声称两个不同 Hash 相同。原始业务产物不做换行转换，仍按原始字节校验。

本轮相对起始业务 HEAD `2d0cf1618` 的 [修改文件清单](evidence/native-cline-delivery-20261109/changed-files.txt)包含合入的监控代码、后续修复和交付证据；更早业务执行链修改保留在历史提交及原报告中。

## 其余用例、P1 与替换边界

| 范围 | 当前结论 |
| --- | --- |
| A Native 编码 | 真实错误修复证据通过；本次新增两轮实时会话和授权原测试复核通过。 |
| B CSV / Parquet | 单 CSV 完整交付通过，满足本轮最低一个分析任务要求；多文件 CSV+Parquet 关联分析尚未通过完整验收。 |
| C Skills / MCP | 已有真实 Skills 使用、授权 MCP Python 调用及失败记录可查看；完整发现/加载/版本诊断与更多工具组合列 P1。 |
| D 异常与取消 | Python 错误、预算错误、真实取消、刷新/重连、UNKNOWN 保留有证据；先前模型超时和产物缺失保留真实失败。完整活跃进程故障、供应方停止和迟到完整回执的联合矩阵尚未全部通过。 |
| E 图文报告 | Markdown、JSONL、Python 及下载通过；PNG、Excel 联合生成、归档、内容独立核对未通过，列 P1。 |

其他边界：直接模式仍为固定授权开发操作员、单个 owned Native Worker；新会话前须停止已有 Worker。Runtime 冻结业务任务不支持从页面任意注入多轮消息/权限覆盖，Native 直接模式支持多轮。未持久化的未执行中间源码不伪造版本历史。高级查询、统计、多文件预览仍可继续完善。

原证券账户和华是科技行情场景仍未通过，不以技术 CSV 的结果替代。两条历史 Native UNKNOWN 及供应方停止/用量不明状态继续保留，不重放有副作用操作；本次成功任务无活动操作不代表历史远端已全部结束。

## 回滚与交付边界

独立 Monitor 可恢复部署前归档并重启，仅影响观察入口，不自动取消或重跑业务。保留原独立监控分支、日志与历史证据。业务 API、Gateway、Worker 的既有回滚版本和旧 SDK 节点未删除；业务类备份仍位于 `/opt/cline-native-business-20261108/backups/monitor-scoped-csv/classes.tgz`，按原业务报告先对账再回滚。

本轮只改变独立测试环境，默认显示已有可用 Node 工作区，未修改共享模型、预算、生产默认 Coding Agent 或旧节点。**最低可用版本验收通过，当前仍不具备替换旧 Coding Agent Node 的条件，不提出灰度切换建议。**
