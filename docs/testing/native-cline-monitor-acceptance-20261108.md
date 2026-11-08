# Native Cline 业务与监控联合验收

实际执行日期：2026-11-09（北京时间）。文件名沿用任务书约定。结论：**限定 CSV 场景已取得两个目标；原任务的五类业务验收尚未整体通过，不具备替换旧 Coding Agent Node 的条件。**

开发测试入口：<http://192.168.195.221:18346/monitor/>。使用现有 Runtime 账号登录；凭据未写入报告。独立 Monitor 服务部署在指定测试机，未修改生产默认 Coding Agent，也未下线旧节点。

## 版本、边界与分工

- 共同基线：`db276d8e151ddb9669b603664e5c7bffb0de9843`。
- 业务目录：`Langchain4j-Chatchat`，分支 `feature/runtime-cline-cli-native-20261107`；本轮成功执行的业务源码为 `ca503a25e56342aba9c90240aeb0087b3d9e9bc0`。
- 控制台目录：`Langchain4j-Chatchat-native-monitor`，分支 `feature/runtime-cline-native-monitor-20261109`；业务修改通过 cherry-pick 集成，浏览器最终取证部署版本为 `ddca05b29`。后续证据提交不改变已部署执行逻辑。
- 官方 Standalone `v4.1.26`，原始 checkout `a459f1d04dea030833b558be645011ab739c463b`。未重写、替换或修改官方 Agent Loop。本轮未提高预算：模型调用上限仍为 12。
- 实际 Worker image：`sha256:172b45b0b4988713cc2a4a02ecbfdcb7dd5a102ec6def6facdff385352940aaf`。
- 当前 Java 构建归档 `api-classes-scoped-csv.tgz` SHA-256：`e86453782f21637ea3daccc8c628e2815a2d26561009f2e59be7a16ed534af1c`。

Monitor 的源文件位于 `cline-agent-server/monitor/`，只订阅现有 Native 日志、Runtime 任务、模型回执、Python 回执及归档接口，不持有 Docker 接口，不执行第二套编码或修复策略。业务核心文件只在业务工作目录修改，再集成至 Monitor 分支。设计与事件映射见 [集成设计](../design/native-cline-monitor-integration.md)，启动、配置和接口见 [Monitor README](../../cline-agent-server/monitor/README.md)。

## 两个目标的真实证据

### 1. 浏览器直接与 Native Cline 对话、观察自主修复

Native transport Session：`4bd40227-be81-474d-8ff2-7f3396ea401c`；官方 Agent Session：`1794160571607_gl5te`。

浏览器创建受控 Node 源码测试任务，Cline 读取源码，运行实际 `node --test` 得到失败，通过 `apply_patch` 修复 `calculator.mjs` 中的减法错误，再次运行测试通过。随后浏览器在**同一 Session**发送后续消息，Cline 解释修改并再次运行原测试，通过。独立执行同一测试也通过；测试文件 Hash 前后相同，断言没有修改。

- 修复前源码：`75cfacb7faac086c50b23ac4b29a709eb8680999e6756f620ca76d42aba07cab`。
- 修复后源码：`5b63136552577a64d788dc3cd4552739d0d60f9e1adb63ec4dfb6932d56fc75d`。
- 测试文件：`9f6c556f2f9e83f1dc63457388376ebcc56db5253da79991b5e0943ab838bfeb`。
- 三次实际 Native 测试执行：首次失败，后两次通过。这是本地受控 Node 测试，不冒充 Managed Python 业务交付。

证据：[独立复核](evidence/native-cline-monitor-20261109/native-independent-check.json)、[同会话浏览器记录](evidence/native-cline-monitor-20261109/browser-native.json)、[源码界面截图](evidence/native-cline-monitor-20261109/native-code-diff.png)、[Native 实际事件](evidence/native-cline-monitor-20261109/native-direct-events.json)。原始创建请求与后续消息分别保存在 `native-first-turn-harness.json`、`native-followup-harness.json`；这两个早期取证脚本分别因断言仍指向旧 Python 文件、服务更新导致登录过期而失败，不能统计为脚本通过。其真实任务结果由后续只读观察与独立执行确认。

前两次直接模式尝试也保留失败：第一次工具许可使用错误名称，实际官方工具为 `run_commands`；第二次所选 Python 工作区在 Native 直接模式容器内没有 Python，实际退出码 127。均通过界面取消，未将其统计为成功。

### 2. 浏览器发起 Runtime 业务 → 实际 Python 失败 → Cline 修复 → 验证交付

本次使用明确标记的非金融技术 CSV，三条原始输入为 `alpha,2.50`、`alpha,3.75`、`beta,-1.25`。它用于验证执行闭环，不替代证券账户、行情或当前金融数据。

原始业务请求（完整请求、浏览器实际 POST 与时间见 [browser-dispatch.json](evidence/native-cline-monitor-20261109/browser-dispatch.json)）：

> JOINT_MONITOR_CSV_REPAIR. First execute the existing joint_analysis.py unchanged through Managed Python to reproduce its real NameError. Then repair that same file yourself and execute again. Analyze only the selected original technical CSV: count data rows and calculate the exact Decimal sum of column value. Deliver the complete contracted rows.jsonl with verifiedSummary and a short report.md. The verified original CSV is directly available in nativeFiles; do not reconstruct transport tables or add other analytical objectives. Keep the program concise. Review the real archived result before finishing.

| 标识 | 实际值 |
| --- | --- |
| Runtime taskId | `analysis-coding-3bfb69e23343c4833397b7aa70b4510bfc56e11cc28071a4a1512dbac038db8b` |
| Worker ID | `770cd052-d887-496f-b5a5-b29b666fae34` |
| Native transport Session | `6251c209-38c1-4a3b-8d42-97cb5d45093d` |
| 官方 Agent Session | `1794166330805_m44zy` |
| Runtime requestId | `22780ff6e334d248a918be0feeeb038f` |
| 模型 | `qwen3.7-plus`，5 条实际 Runtime 模型回执 |
| 原始 CSV SHA-256 | `fcb74b46f4042569e233515f15ea92342b8e019dd9531e0e9b7e4c92d37e9fb9` |

首次源码只有 `joint_monitor_missing_name()`，在 Native Session 建立前放入专用工作区；未给 Cline 提供成功实现或成功回执。Cline 首次通过已授权 MCP 调用 Managed Python，得到真实 NameError。Cline 自主通过 `apply_patch` 写成 2855 字节分析程序，然后再次调用 Managed Python。

| 阶段 | operationId / 证据 | 真实结果 |
| --- | --- | --- |
| Python 第一次 | `cline-491d3657cccb87eb35e5eb57ba031ec895f2965c0a198783ada53222f6f96079` | FAILED，exitCode=1；源码 `54d6dea051ebe5f89e86eb934144916316d965a85c49b3f7b0c9ef6151dc8b66` |
| Python 修复后 | `cline-873982245d2d3a25b26e7fc2524a9627b89a51eecaa4974a80857b1b900f3953` | SUCCEEDED，exitCode=0；源码 `92513b152a135b1738d802ac1ab1072442cac84ff110f6c1426c51e3c4e13ccc` |
| Runtime 验证 | 第二次实际回执 | `validationStatus=PASSED`，`businessValidationPassed=true`，`stopped=true` |
| 官方结束工具 | Native event 91，toolCall `call_84628f9a858e478b9e965735` | `submit_and_exit` 真实调用，随后 `turn.completed` |
| 最终状态 | 独立查询 | Native COMPLETED、Runtime COMPLETED、workerStopped=true、activeOperations=[] |

“工具成功”“Python 成功”“Core turn 完成”“Native gateway 完成”“Runtime 交付完成”分别读取真实事件和回执，不相互推断。UI 的 Python 停止状态读取对应执行回执；全局取消确认字段仍保留其原始含义，不强制置 true。

Runtime 使用新注册的 `runtime-csv-count-sum:v1` 验证器，从 Hash 验证的不可变原始 CSV 独立计算行数与 Decimal 合计；拒绝使用模型提供的预期结果。只有显式选择 `csv-count-sum-v1` 的单 CSV 任务启用，不修改共享默认执行内核。Java 只描述任务合同与验算，不接管 Cline 的编码、修复或工具顺序。

另从 Runtime 下载原始输入和全部 25 条归档文件记录，在独立校验中重算得到 **3 行、合计 5.00**，与结果一致；Markdown 文件与结构化结果中的报告一致。JSON/JSONL 解析、Python AST、CSV 读取和 Parquet 的 pyarrow 读取均通过，25 条记录的大小及 SHA 均匹配。Parquet 是已有输入供给格式的可读性核验，**不表示多文件 CSV+Parquet 关联分析场景通过**。

独立检查前后仍为 2 次 Runtime Python 操作、5 次模型回执，没有额外派发业务执行。第一次独立 Parquet 检查因受控容器 UID 不能读取 0600 证据文件失败；改为仅独立只读检查容器的 UID 0 后读取成功，未修改业务数据或验算期望。

实际交付文件（全部归档清单及校验见 [独立校验 JSON](evidence/native-cline-monitor-20261109/joint-r4-independent-check.json)）：

| 文件 | 字节数 | SHA-256 |
| --- | --- | --- |
| [原始 CSV](evidence/native-cline-monitor-20261109/deliverables/original-0.csv) | 48 | `fcb74b46f4042569e233515f15ea92342b8e019dd9531e0e9b7e4c92d37e9fb9` |
| [Markdown 报告](evidence/native-cline-monitor-20261109/deliverables/report.md) | 477 | `d32abc08fc777dbc08958723775e6f5602d4d7f7d321a68b9e6c15d187fed5c2` |
| [结构化结果](evidence/native-cline-monitor-20261109/deliverables/rows.jsonl) | 1406 | `73870a63d19a13338a4b5a8a5ff2ab1a9df134e9d76bbb7750be23ede4301eff` |
| [Cline 生成的 Python](evidence/native-cline-monitor-20261109/deliverables/joint_analysis.py) | 2855 | `92513b152a135b1738d802ac1ab1072442cac84ff110f6c1426c51e3c4e13ccc` |

25 是实际归档记录数，包含输入、源码版本、日志、回执及重复归档记录，不代表 25 份分析报告。没有宣称本次交付 Excel 或 PNG。

最终只读浏览器取证：[完整记录](evidence/native-cline-monitor-20261109/browser-runtime.json)、[Python](evidence/native-cline-monitor-20261109/joint-python.png)、[源码变化](evidence/native-cline-monitor-20261109/joint-code.png)、[模型记录](evidence/native-cline-monitor-20261109/joint-models.png)、[归档下载](evidence/native-cline-monitor-20261109/joint-files.png)、[状态](evidence/native-cline-monitor-20261109/joint-diagnostics.png)。刷新后仍为同一真实任务；最终只读观察没有再次创建任务。另实际点击界面的下载链接，取得 `report.md` 并核对 Hash，通过，见 [浏览器点击下载证据](evidence/native-cline-monitor-20261109/browser-click-download.json)。

## 四次联合业务尝试与成功率

四次任务及其真实工具、模型、权限、MCP、结束事件均见 [事件记录](evidence/native-cline-monitor-20261109/joint-four-attempts-events.json)。投影去除凭据、隐藏推理和模型消息正文；请求保留 Hash，结果与源码通过专门的授权查看接口取得。原始私有 Journal 保留于测试机，不覆盖旧失败。

| 次数 / Worker ID | 模型 | 结果及定位 |
| --- | --- | --- |
| J1 `02130e7a-a78f-4b4a-b57b-8f0b0683a5d2` | qwen3.7-plus | BUDGET_EXCEEDED。Sandbox 路径与逻辑 input_read 引用混淆，Patch 参数失败；两次 Python 仅检查数据，无最终结果。首次故障复现要求未完成。 |
| J2 `438c3d10-f99e-4ec4-a23b-e8495d594fb7` | qwen3.8-omni-flash | BUDGET_EXCEEDED。实际 RuntimeError 被现有分类器判为 UNKNOWN/STOP，未出现第二次执行。修复 Native 对真实 STOP 的遵从，未放宽 Runtime 分类器。 |
| J3 `9c2cc565-c5ac-4413-84fc-e14dac1a48b0` | qwen3.8-max | BUDGET_EXCEEDED。实际 NameError 可修复，但 Cline 为 21423 字节泛化任务生成 574 行冗余代码，未再次执行。定位到任务供给过宽。 |
| J4 `770cd052-d887-496f-b5a5-b29b666fae34` | qwen3.7-plus | COMPLETED。显式 CSV 合同的目标压缩为 2235 字节；真实失败、Cline 修复、再次执行、官方结束、独立验算和归档均通过。 |

本轮真实业务交付成功率 **1/4=25%**。前三次各有 12 条模型回执，第 13 次请求被预算拒绝；不将请求数当成成功模型回执数。J4 使用 5 次模型调用，没有提高预算。

前阶段原任务业务执行是 **0/9**，报告见 [原业务验收](native-cline-business-acceptance-20261108.md)。合并两个阶段，已记录真实业务请求 **1/13≈7.69%**。Native 直接模式的受控 Node 测试单独报告，不加入 Runtime 业务分母；观察、刷新、独立校验也不增加分母。

## 控制台及恢复验收

| 检查 | 结论与边界 |
| --- | --- |
| 浏览器真实创建与同会话多轮 | Native 直接模式通过；Runtime 模式通过已有资产分析入口创建真实持久任务。Runtime 模式不提供对运行中冻结业务任务任意注入消息或人工许可的能力，界面明确区分。 |
| 三栏与八标签 | 已实现对话、模型、工具、Skills、MCP、代码、Python、文件/诊断观测；模型回执以 Runtime 为准，缺少费用或用量保持 UNKNOWN，不由 Core 的零值补齐。 |
| 工具/Skills/MCP | 真实事件可追溯工具 ID、输入输出 Hash、授权决定、MCP operationId；Skills 的 staging 与实际工具使用分开显示。J2 日志有真实 Skills 使用；不将 staging 统计为已使用。 |
| SSE 重连与去重 | 修复大批历史回放遇到 `write(false)` 时错误断连问题，按连接等待 drain；持久 eventId/cursor 续传。浏览器刷新与离线重连后身份、状态保持，历史记录验证无重复 ID。 |
| 源码与 Diff | 对 Runtime 已执行的源码版本按 codeHash 查归档，展示真实前后文本；未持久化的未执行中间版本不伪造完整历史。 |
| 用户取消 | 直接模式早期失败任务通过真实取消停止；不将按钮点击当停止确认。 |
| 历史 UNKNOWN | 只读查询及刷新保留真实 UNKNOWN，没有自动重派发、伪造旧 SDK Checkpoint 或把已结束任务改成成功。 |
| Native 服务故障 | 创建一个真实空闲 Native Worker，从浏览器取得 Session 后 SIGKILL，再启动：idle → recoverable，epoch 改变，原日志前缀完整，无任务重放。**此项是 idle Worker 故障，不代表本轮已验证活跃模型进程故障。** |

恢复证据：[SIGKILL](evidence/native-cline-monitor-20261109/native-server-sigkill-idle.json)、[历史浏览器验证](evidence/native-cline-monitor-20261109/browser-history.json)。另一次 `docker restart` 实际触发优雅 shutdown 并取消 owned Worker，结果为 CANCELLED，原先“应当 RECOVERABLE”的测试期望失败；此失败保留在 [原始观察](evidence/native-cline-monitor-20261109/native-graceful-restart-observation.json)，未修改实际状态或将其计作故障恢复通过。

当前直接模式采用显式操作员和固定授权工作区，一个服务只支持一个 owned Native Worker；没有宣称已完成多租户、多 Worker 产品化。Runtime 使用自己的授权与持久任务范围；无 AgentRun 父记录的资产分析任务，通过真实合同租户/用户/runId 对账后观测，不创建虚假 AgentRun。

## 构建与回归

先完成完整构建及相应回归，再执行 J4 真实模型业务验证：

- 20 模块 Maven package：SUCCESS，`maven-package-scoped-csv-r2.log`，2026-11-09 03:28:21 +08，2 分 56 秒。此前已执行真实 Vite 前端构建，57.96 秒；最终 package 跳过重复 npm 构建。
- Java 关联回归：78/78，通过。范围为资产分析 5、监控控制器 6、分析服务 62、CSV 独立验证 4、目标供给 1；`scoped-csv-objective-regression-r2.log`。
- 最终集成 Node 回归：41/41，通过；`final-node-tests.log`。包括真实服务接口投影、SSE 大体量回放与断点、Native 任务供给和 STOP 政策测试。单元测试不计入真实业务验收。
- 初次 Java 编译使用错误 getter，后修复；首次增量 package 因恢复了生成前端 index 后又跳过前端构建而资源校验失败。实际重建前端后完整 package 成功；失败日志保留，不冒称首次通过。
- 前阶段全量 verify 的 52 failures、36 errors 及 JVM fork 异常仍未闭环。上述关联回归通过不代表全量回归通过，也未证明所有旧失败均为基线问题。

构建日志原件位于业务工作目录 `target/native-business-acceptance/`；最终 Node 日志位于 Monitor 工作目录 `target/native-monitor/`。本报告证据目录提供 [构建日志索引与原日志 Hash](evidence/native-cline-monitor-20261109/build/index.json)，归档日志转换为 UTF-8/LF 便于阅读，原日志 Hash 与存档 Hash 分开记录。产物文件保留原始字节，由 scoped .gitattributes 禁止换行转换；全部证据的 SHA 清单见 [SHA256SUMS](evidence/native-cline-monitor-20261109/SHA256SUMS)。相对共同基线的修改路径见 [changed-files.txt](evidence/native-cline-monitor-20261109/changed-files.txt)。

## 五类原业务场景及未通过清单

| 原场景 | 当前结论 |
| --- | --- |
| A 证券客户账户 070200046604 | 未通过；本轮没有以技术 CSV 替代该客户的真实授权交易及资产输入。 |
| B 华是科技最新行情 | 未通过；未形成有真实授权行情支持、完成交付的报告，不编造行情。 |
| C CSV+Parquet 多文件关联 | 未通过完整场景；限定单 CSV 场景真实失败修复及交付通过，不能代替多文件关联。 |
| D Markdown+PNG+Excel | 未通过完整场景；本轮 Markdown、JSONL、Python 真正交付，未证明 PNG/Excel。 |
| E 全套异常恢复 | 部分验证；取消、预算、工具错误、Python 错误、历史状态与空闲 Worker SIGKILL 有证据；全部模型超时、活跃执行 Node/Worker 故障、重复派发、迟到回执等组合仍未完成联合验收。 |

仍需解决：原金融数据供给与真正可交付业务；多文件关联、Excel/PNG；全量回归失败；历史未决远端模型操作的停止与用量确认；完整活跃故障矩阵。[最后状态对账](evidence/native-cline-monitor-20261109/final-status-reconciliation.json)再次查询四个 Runtime 任务，前三个 BUDGET_EXCEEDED、第四个 COMPLETED；保留两条历史 Native UNKNOWN，未发送控制操作。历史 Native UNKNOWN 与远端无法确认的状态保留，不能以本次 activeOperations=[] 证明全部历史操作已结束。

## 回滚与切换

Monitor 是独立服务，可停止 `cline-native-monitor`，保留持久证据；停止观察服务不代表取消业务执行。Native 直接模式服务 `cline-monitor-native` 与 Runtime 业务执行服务分开。业务 Java 部署前备份位于 `/opt/cline-native-business-20261108/backups/monitor-scoped-csv/classes.tgz`，此前镜像、配置及业务源码版本仍保留，按原验收报告的旧节点回滚路径恢复；回滚前先对账正在执行的操作，不自动重放。

**不提出灰度切换建议。** 当前只能确认限定场景已完成联合闭环和浏览器观测，尚不满足五类业务全部通过、监控完整验收及不存在未决远端执行的联合条件。
