# Native Cline 业务验收（2026-11-08，执行跨至 11-09）

**结论：未通过验收，尚不具备替换旧 Coding Agent Node 的条件，不建议灰度切换。** 已提交、推送并在专项环境部署业务链路修复，但不能把这些修复和局部执行成功当作完成本任务的全部目标。

A–D 原始业务请求共派发 **9 次，Runtime 完整业务交付 0 次，成功率 0/9（0%）**。分母包含修复前失败轮以及复用于 E 进程故障的 B/D 首轮；单独的运行中取消、模型超时故障任务不进入业务交付分母。Host 修正后 A-r2/B-r2/B-r3/C-r2/D-r2 的交付仍为 0/5。五类验收场景整体通过数为 **0/5**，局部通过项单列，PARTIAL 不计成功。

## 基线、代码与部署范围

- 工作分支 `feature/runtime-cline-cli-native-20261107`。开始时远端 HEAD 为 `c7e95947cda8dd20fb310e407a550d27d9749452`，工作区干净；相对 `d8f41ff2e` 为一个业务适配提交、23 个文件、+1923/-8。重新 fetch 后 main 与 origin/main、一致的专项分支均无本地独有或远端遗漏提交；main 为 `2db9b59df`。
- 已阅读指定 Native Server/业务目录、Java cline/execution、三份审计/业务文档，并核对 CodingAgentSPI、DurableCodingTaskService、CodingTaskContract、CodingPythonExecutionBridge、CodingEvidenceCollector、RuntimePythonAssetArchiver、输入供给及归档实现。未以旧报告代替本轮实际构建与执行。
- 当前部署源码提交为 `72c098e76`；Java 业务修复最后变更于 `1030e50ed`，后续变更是 Native JS、Host 构建、验证脚本及文档。本报告所在提交另包含最终证据与审查文档。完整修改文件清单见 [changed-files.json](evidence/native-cline-business-20261108/changed-files.json)。
- 部署仅限 `192.168.195.221:5221` 的 `/opt/cline-native-business-20261108`：专项 API（loopback 18340）、Gateway（18341）、受限 Executor 和派生 Worker。生产默认 Coding Agent、共享 Python 内核及旧节点未切换、未下线。
- A–D 保持专项原有预算（12 次模型调用、786432 token、24 次工具调用、12 次代码执行等），没有提高预算。E 模型超时任务单独把 deadline 减至 30 秒，真实故障完成后恢复原专项 API 容器及配置；该故障合同仍保留原 30 秒 deadline。

## 本轮源码修复

1. 同步模型工具许可和 Executor 固定许可，缺少官方 `submit_and_exit` 时启动拒绝。首轮模型工具目录未暴露结束工具时提前失败。
2. 官方 v4.1.26 Core 定义结束工具，但默认路由关闭且 Host 缺 submit executor。通过官方公开 toolRoutingRules/ToolExecutors.submit 启用；Host 向本任务 broker 提交原生 session/toolCallId/epoch，再读取实际 Python、结果校验和归档回执。不伪造成功结果，不修改 Core Agent Loop。
3. Native turn 结束必须伴随实际官方结束工具成功、已停止的 Python、已复核的真实结果，再交由 Runtime finish。缺产物、预算耗尽、PARTIAL、无验证引用均不得提升为 COMPLETED。
4. Native profile 的 Runtime 完成额外要求 `businessValidationPassed=true`。只有结构检查通过、业务未评估的结果保留 PARTIAL 和真实证据引用；旧 SDK profile 默认行为保留。
5. 私有恢复记录保留原始冻结合同、Worker grant、控制令牌、session/epoch、活动 operation 和真实停止证明。重启先 UNKNOWN，不自动重派 Python，不查询或重放 SDK checkpoint。缺控制的旧记录即使再次持久化也不能变成可控制的新会话。
6. 现有签名 monitor 回调增加只读 operation 查询，只能读取本任务实际派发过的 Python，支持 lease/deadline 结束后的真实对账。Native 失败不因一次中间 Python 成功而被升级。
7. 真实取消测试发现已停止 operation 仍留在活动集合：取消和重启恢复现在依据真实终态回执清理；UNKNOWN、不完整回执继续保留。恢复后的状态原子持久化；只读查询可对齐已确认的 Runtime 停止终态，不执行新任务。
8. 保留既有 MCP native operation context 适配。首次 Host 重建误用缺少该适配的目录，B/D 实测失败；已保留失败证据并补齐构建流程后重建。不是通过放宽工具绑定解决。
9. 构建阻断修复：migration bootstrap 识别打包的条件 CREATE TABLE 和明确白名单的幂等 policy seed；E2E 测试修正两处过期 import 和一处过期构造器，未修改业务断言或预期结果。

Node 只投影模型传输、许可、原生事件、实际源码差异和 Python 回执。读取/搜索/编辑/Skills/工具选择/修复继续由官方 Cline 自主执行；没有第二套编码规划或修复循环。

## 构建与回归结果

| 检查 | 结果 | 证据 |
|---|---|---|
| 完整 Maven package | 20 模块成功；2026-11-09 00:12:04 +08:00；跳过重复测试 | `target/native-business-acceptance/maven-package-final.log` |
| 全量 verify | **失败**：Agents 474 项，52 失败、36 错误、2 跳过；HarnessToolRecoveryTest JVM 异常退出，下游未完整执行 | `maven-verify-r2.log`；[88 项逐项定位](evidence/native-cline-business-20261108/full-regression-failures.json) |
| migration 独立 verify | 14 项通过 | `migration-verify.log` |
| Native Java 回归 | 236 项，0 失败、0 错误、1 跳过 | `native-package-r2.log`；该次后续 package 曾因前端旧资源失败，随后完整打包已重新通过 |
| 最终 Native Node 回归 | 34/34 通过 | `node-tests-stop-reconciliation.log` |
| 监控 Java 补充回归 | 5/5 通过，依赖模块与 API 编译成功 | `monitor-tests-final.log` |
| 官方 Host check-types/lint/build | 容器工具链执行通过 | 专项机 `evidence/acceptance-20261108/host-build/` |

[构建摘要与日志 Hash](evidence/native-cline-business-20261108/build-summary.json)记录具体版本和日志完整性。全量失败没有混入旧 surefire XML，没有修改断言使其通过；尚未做同环境基线复现，不能声称这 88 项均为历史问题。

官方源码固定于 v4.1.26 checkout `a459f1d04dea030833b558be645011ab739c463b`。Host 构建前后 604 个 `sdk/packages/core/src/**/*.ts` 指纹一致；Host 配置及 MCP 元数据适配改变了编译 bundle，不能声称二进制 Hash 未变。

| 产物 | SHA256 |
|---|---|
| 原业务 Worker cline-core.js（实际 docker cp 核对） | `39e76ca525a3736d645ebb972e60a34a45bac413837e709f0bf3d2a7a6aad361` |
| 当前 Host cline-core.js | `03ec8a39a97a41a5dd8709d4281e95ad17d89c59429ab80ee51fb3e20da32241` |
| 官方 descriptor，前后相同 | `d28dace3b4daff727770c3625917b22a0c54a8f855c4222530de1923b493086f` |
| 当前 Worker image | `sha256:172b45b0b4988713cc2a4a02ecbfdcb7dd5a102ec6def6facdff385352940aaf` |
| 当前 Executor image | `sha256:225a0a6090a5766816ba836fdc504aa93f41639767eae2941a4414c33c822c09` |
| 最终完整 API classes 归档包 | `4c818e5129f1cd5de311c20fe8751ab0935e6901d067bc5b5e15083bbf4b77a1` |

旧错误构建 `89172dff…` / Worker `0b849556…` 缺失原有 Host MCP context，不能作为当前验收或优先回滚版本。最终 Gateway 两个修改模块的部署 Hash 与本地源码一致，见[部署与恢复证据](evidence/native-cline-business-20261108/sanitized-fault-and-reconciliation.json)。

## 五类真实场景

| 场景 | 原始业务请求与验收结论 |
|---|---|
| A 证券客户账户 | 客户号 070200046604，计算授权交易/资产支持的资金、持仓、盈亏及行为特征。**不通过**：本次输入没有实际客户账务数据，不能计算独立对照指标；A-r2 已完成原生结束与归档，但 Runtime PARTIAL、父任务 FAILED。没有填入历史样例金额 |
| B 华是科技行情 | “分析华是科技最新的股票价格行情走势。” **不通过**：首轮因 Host MCP context 缺失而失败，后复用于真实进程故障；两轮独立复测均在父层模型规划 request timed out，未进入 Native。证券定位、单证券/全量行情、授权搜索可用性与趋势交付仍未验收 |
| C CSV + Parquet | 使用所有明确授权的原始文件、解析自身 Schema、关联且避免双格式重复、保留 missing/null，生成分析及报告。**不通过**：首轮 Python 退出 0 但缺 rows.jsonl；故障修复轮实际 RuntimeError 后预算耗尽，未完成第二次 Python 成功 |
| D 图文报告 | 使用授权 CSV/Schema 生成 Markdown、Excel、至少两张实际数据图表及结构化视觉报告。**不通过**：修正 Host 后 Cline 实际加载 Skills、读写源码，但 12 次模型预算耗尽前未调用 Python，未取得所需文件 |
| E 异常与恢复 | 实际触发及对账见下。**部分验证、整体不通过**：模型调用超时后供应方终止/用量不明；迟到验证覆盖真实事件重投，未覆盖首次迟到完整远端回执；不能把局部故障通过计为 E 整体通过 |

各轮真实标识及终态如下。表中两个状态分别为 Native Gateway / Runtime Coding，不能与父任务状态混同。完整 parent/run/Native Agent session/model/tool/Python 标识与时间序列见[逐轮证据](evidence/native-cline-business-20261108/sanitized-business-evidence.json)；未产生的 ID 明确留空。

| 轮次 | Runtime 标识 | Native transport session | 最终状态 |
|---|---|---|---|
| A | `analysis-coding-464634ffb10e361b866149d59a923f52f16636a2d0711004b507522403abc388` | `b0c5adce-474b-4662-96d9-b4729fbb95af` | FAILED / FAILED |
| A-r2 | `analysis-coding-43e863f91541fd49826c1b25cb897ca98486b354a86ae761b44cd29cfc24a648` | `f3c4a83d-6ad0-4157-a605-070bcfb42513` | PARTIAL / PARTIAL |
| B | `analysis-coding-36182710d995ba7ae498af8cf0bba467de3ee5c97f5416e46aac71645e578e76` | `6ea070ed-6c49-45a6-b0c9-a5233348cc90` | CANCELLED / CANCELLED |
| B-r2 | 父任务 `1264e7ae-dd5c-3462-9fec-c2c2ca7e6af7`；未创建 Coding task | 未进入 Native | FAILED（父层模型超时） |
| B-r3 | 父任务 `3f05f65d-463f-3ca8-94f3-407698684c3a`；未创建 Coding task | 未进入 Native | FAILED（父层模型超时） |
| C | `analysis-coding-9e994791378a69b3182a1aa1f4d15d29600be2a8c789b7af960bc615276d6dd1` | `ebc4ff39-09b8-4fb0-93c9-4104b0b517c6` | BUDGET_EXCEEDED / BUDGET_EXCEEDED |
| C-r2 | `analysis-coding-de5287c9b8710aeecaef3ccadda7acb05112c504bee5b85b5d8ce0f69a31be47` | `62599be5-bba8-473a-8015-eb4f6737069a` | BUDGET_EXCEEDED / BUDGET_EXCEEDED |
| D | `analysis-coding-b86dd0a61f20dd6c8283cf1c5c17fadf298a341c945b5d7ec8f5e958552dd864` | `7a183abd-ed64-4a68-83e3-f7fd55f56373` | CANCELLED / CANCELLED |
| D-r2 | `analysis-coding-e2810cd5f43db7cdcbb1f2c2daa44dce03716fa934bca4926e6b1db4c41f652c` | `7e4e36e5-86d2-4309-a00f-d59141c2dada` | BUDGET_EXCEEDED / BUDGET_EXCEEDED |

原始请求指定 `qwen3.8-omni-flash`，实际 Runtime 已有模型策略并非全部采用该别名：A 的真实 Native 回执为 `qwen3.7-plus`，B 首轮为 `qwen3.6-plus`，C/D 为 `qwen3.8-omni-flash`。B-r2/r3 的父层记录为 qwen3.6-plus 超时，没有 Native 模型回执。没有为了本轮测试改变这些模型策略；证据以实际 modelInfo/providerRequestId 为准。

### 已实证的原生结束与修复边界

A-r2 官方 `submit_and_exit` 成功事件为 `222`，toolCallId `call_7a767c7b6c574cfc8a279ed4`。第一次 Python operation `cline-74297556c44868c99c7d6510e1c7f4a4eab49017a8a923e32e7a0e8ab3e8a26d` 技术状态 SUCCEEDED，却缺必需产物，CodeHistory REJECTED。Cline 原生 apply_patch 修改后，第二次 `cline-3b581680aea89dfc3523cdc18f6284873d96ccabff695ea7a89bac310bdee4cd` 实际执行成功，产生 result/validation/bundle 引用并被读取复核，最后 Runtime 保留 PARTIAL。

两次源代码 Hash 分别为 `86e132242b7cd4e1e867da21d1200989c6436336ccd066409d6fd599a1a06833` 与 `ee3e6ebd54683911de6d73fdd3623b014003e40db69607bb9c67960834589b05`。这证明**实际产物合同错误后的自主修复**，不能冒充 Python 异常修复或客户业务完成。

C-r2 在独立新任务工作区、Native Session 创建前放置仅抛 RuntimeError 的已有故障程序，由官方 Cline 原样调用 Managed Python。实际 operation `cline-977ba4f0e8c147a93420cc44002f12488fb3abe55cfb3c27e9dfa57d4a8adbf8` 为 FAILED、executorStopped=true，源码 Hash `827bdf8a4268ab5acc31cf34cdce9402027fe4dfd53fb3ee0f83642b16a2f1db`。未提供修复代码或成功回执；后续原生修改记录存在，但没有第二次成功执行，所以“异常 → 修复 → 再成功”仍未通过。

## 数据供给、产物与独立检查

- 实际通过 Runtime 下载接口取得 99 条归档文件记录，**99/99 下载 Hash、大小及可读性检查通过**。包括输入、源码、日志和结果，不是 99 份业务报告，也不是业务成功率。逐文件 ID、名称、大小、SHA256、解析结果见[完整文件清单](evidence/native-cline-business-20261108/artifact-download-checks.json)。空日志明确标记 empty。
- A-r2 的最新授权 Parquet 另经独立读取确认为 10 行，record 字段为 title/content/sourceUrl/retrievalSource 等检索字段，不是客户账务字段；见 [账户输入 Schema 核对](evidence/native-cline-business-20261108/independent-account-input.json)。未沿用旧报告假定其输入类型。
- 原始 CSV 和 Parquet 各 26 行、18 条独立记录。独立读取实际授权下载内容，核对原生 JSON 类型、字符串代码、数值、布尔、显式 missing/null 元数据、行顺序及多重集，全部一致。原始重复没有删除，也没有把两种格式重复相加；见[独立输入核对](evidence/native-cline-business-20261108/independent-csv-parquet.json)。
- 原始 Parquet SHA256 `935f004477cf762f0c7436bf82d92e4b5635ae87a4d2c54703a3b79bd14764f6`，CSV `b867b673016329873b76c17ef55a338128a0a8574953efaf25ec2879306c0cc2`。初版检查器错误地把 CSV 普通字符串/布尔当 JSON，已按真实编码修正检查器；原始文件 Hash 和业务期望未修改。
- 授权文件描述符、Native 路径与 Sandbox `/data/input` 路径分开交付；Native 实际传输逐块核验长度/Hash。公开证据记录每任务文件、快照引用、每 operation 输入引用及文件 Hash；没有扩展任意文件访问。
- 真实监控 HTTP 验证覆盖 A-r2、B 首轮共 9 个 owner 请求：任务状态、模型回执、事件、输入均 HTTP 200；A-r2 同时验证 TASK_LATEST 与实际 operation 的 OPERATION_FROZEN。A-r2 监控实际返回 12 份模型回执，B 返回 8 份；见[HTTP 检查](evidence/native-cline-business-20261108/monitor-http-checks.json)。没有以此声称浏览器监控集成全面通过。
- C 首轮实际加载 2 个原生 Skills，D 修正 Host 后实际加载 3 个，具有原生 tool.completed 与 Runtime SKILLS_LOADED（skillId/version/contentHash）事件。仅复制 Skills 文件不计使用。
- 本轮没有归档成功的独立 Markdown、Excel 或 PNG 文件。A 的文字说明包含在 JSONL 结构化报告内；D 的文件、标题、图表值及 Excel 数据因此无从独立核验，均不计通过。现有归档代码支持合同允许的 results/validation/logs 多产物，但“代码支持”不能替代实际交付。

## E 真实故障与恢复逐项记录

| 故障 | 本轮真实证据与结论 |
|---|---|
| 模型超时 | 单独 30 秒合同实际进入 Native，2 次模型请求；第二次调用日志为 AgentDeadlineExceededException / “Agent execution time budget exhausted during model invocation”。不是 Mock/假响应。Runtime 与 Native 最终 BUDGET_EXCEEDED；供应方终止及用量仍未确认，不能计全部对账通过 |
| 模型预算不足 | C/C-r2/D-r2 实际 12 次调用用尽，第 13 次申请被拒绝，最终 BUDGET_EXCEEDED；未增加预算 |
| 工具失败 | 实际 ENOENT、apply_patch 解析失败以及 Host MCP context mismatch 均有原生失败回执；A、D 有原生工具修正记录。没有把工具成功计为业务成功 |
| Python 失败 | C-r2 真实 RuntimeError、FAILED、停止及诊断/源码归档已确认；异常后再次执行成功仍未通过 |
| 必要产物缺失 | C 首轮、A-r2 首次 Python 均实际缺必要 rows.jsonl；A-r2 后由 Cline 修改并再次执行得到可读结果。缺失阶段未完成 Runtime 交付 |
| 用户取消 | 独立 E task `analysis-coding-252f971c2819158029ab1d8f221638e979b04cbb38f97c2b0b33aa8a78ffed2f`，Native session `9e92098b-d7a7-412b-8bb4-4dcaec982ede`。观察真实 Python RUNNING 后，调用 exploration 用户 stop 接口，实际 operation CANCELLED、exit 137、executorStopped=true；Native Worker exited 0，任务 CANCELLED。最终恢复后活动集合为空 |
| Node 服务重启 | 对真实运行中的 B 首轮 Gateway SIGKILL 后重启，恢复 UNKNOWN，原 session 保留，不重放模型/Python；Runtime cancel/resume 实际完成 CANCELLED 对账 |
| Worker 终止 | 同轮对真实运行的 Worker SIGKILL，真实退出 137，保留 stopProof，未派生替代执行 |
| 重复派发 | 恢复后再次提交同一冻结合同，返回相同 session、UNKNOWN，没有新 Worker/Python；另有 C 原合同实际重复提交检查 |
| 迟到回执 | 将 A-r2 实际成功 Python 进度事件原样在终态后重投，HTTP 409，PARTIAL → PARTIAL，没有新成功回执/新执行。覆盖迟到重投，不能代替“首次迟到完整远端回执”覆盖 |

运行中取消 operation 为 `cline-dbaed22c6c5ae6be4c9b80c5e305fac404499c897a92639faf88fe713f01cb83`；精确 Native session、原始故障请求、前后实际回执、代码/manifest Hash、模型/工具调用及最终停止证明见[故障与对账证据](evidence/native-cline-business-20261108/sanitized-fault-and-reconciliation.json)。此任务仅执行明确的等待程序以测试取消，不作为任何金融业务成功。

模型超时 task 为 `analysis-coding-1c08226ade3a687230eae98880b18e1ec68eaae70a6a347e490f5cf01e92ca2f`，Native session `a229e79c-8931-4a15-b658-a5bfb2b83fac`。测试使用真实配置模型及真实 Runtime deadline，未改共享默认；原专项 API 已恢复。模型停止只能确认本地调用终止，供应方推理/计费未知，不能写成已确认停止或已知零用量。

## 最终状态对账与剩余问题

对账覆盖 16 个 Native 记录（含历史），所有专项实际 Worker 均 exited，另一个历史 Worker 从未启动、状态 created；无专项 Native Worker 仍运行。所有实际可查询 Python operation 均已停止；历史缓存 `cline-fdcaf886db3bb4a0d0fbbf8d17a88b396e19a80d5b19b62f39f01622fb7d0630` 返回“本任务未派发”404，不能伪造其成功或取消回执。

旧 `analysis-coding-e459…` 与 `analysis-coding-30e2…` 因原始 Native 控制信息不完整仍保留 Native UNKNOWN，Runtime 已为 BUDGET_EXCEEDED；没有用 SDK checkpoint 或新 grant 重新执行。新 B/D 取消任务已对齐 CANCELLED，A-r2 为 PARTIAL，C/D 预算失败保持 BUDGET_EXCEEDED。模型供应方未确认的停止与用量仍是待对账项，不能由 Worker 退出证明代替。

| 未通过项 | 定位与后续所需事实 |
|---|---|
| 客户账户业务 | A 实际授权快照/报告缺少交易、持仓、资金流水，只有检索材料；需要真实授权账务输入，不能用样例或生成数值补齐 |
| 股票行情业务 | B 两轮父模型超时，首轮 Host 故障已修但最新 Native 股票完整链未验证。需真实完成证券定位、全量行情回退、授权搜索配置/可用性探测；当前不能声称行情不存在或股票代码已识别 |
| Python 异常自主修复 | C-r2 实际失败后未得到修复后成功；定位原生工具/模型调用、源码变更及预算日志。不能以 A 的产物修复替代 |
| 多产物交付 | D-r2 未执行 Python，无 Markdown/Excel/PNG 实物归档，故无数据值/标题独立校验；不能因 archiver 支持扩展名而计通过 |
| 业务验证 | 当前通用 CodingSchemaResultVerifier 只作 STRUCTURAL_ONLY，业务未评估时 INCONCLUSIVE；CodingFinancialResultVerifier 仅适用于显式可信账本规则。不能把通用结构通过当这些业务请求的独立验算，尚未完成全部业务验证能力 |
| 模型远端对账 | 真实超时留下供应方终止/用量未知；本地 stop proof、预算终态不等于供应方停止证明，保留待对账状态和预留量 |
| 历史 Native 恢复 | 两条旧 UNKNOWN 缺原始控制绑定，实际 Worker 已停、Runtime 已终态，仍不能宣称恢复这些会话成功 |
| 全量回归 | 52 失败、36 错误和 JVM fork 失败未关闭；逐项源码帧/方法/信息见失败 JSON，未通过修改预期规避 |
| 监控与迟到覆盖 | owner REST/模型回执/输入快照和 5 个监控单元检查通过；浏览器完整监控、首次迟到完整远端回执尚未全部验收 |

没有把任何部分成功统计为总体通过。当前只有原生结束/负向完成门槛、产物合同错误修复、部分数据供给、真实停止恢复和归档读取等子项通过；业务目标和最终切换条件仍不满足。

## 证据保全与回滚

原始业务请求、模型/工具消息、金融原始文件、完整源码、执行回执和实际日志保存在专项机 `evidence/`、`state/`、`workers/` 的私有目录。仓库不包含凭据、原始金融数据或模型密钥，只导出审阅后的元数据、标识、调用/状态序列、Schema/Hash 和可读性检查。[私有证据 Hash 索引](evidence/native-cline-business-20261108/private-evidence-hashes.json)用于定位当时原文；[工作区源码 Hash/语法清单](evidence/native-cline-business-20261108/sanitized-fault-and-reconciliation.json)不等于这些源码已实际执行或业务正确。

专项原 Executor 保留为 `cline-business-executor-pre-acceptance`，阶段容器 `-pre-completion`、`-pre-context` 亦保留；`backups/acceptance-*` 与 `backups/completion-*` 保存匹配的源码/classes/私有配置/inspect。模型故障 API 容器保留为停止的 `cline-business-api-timeout-test`，原专项 API 已恢复。不要选择已知缺 MCP context 的中间失败镜像作为优先回滚点。

回滚顺序：停止接收专项新任务，先查询并停止/对账实际远端执行；无法确认者保持 UNKNOWN。停止当前专项 Executor，恢复同一备份的源码/classes/两个私有配置和匹配 pinned WorkerImage，再恢复匹配保留 Executor，最后重启专项 Gateway/API并核对健康、状态与活动 operation。不要删除 Journal/证据，不要将同一个 Native taskId 转交 SDK 重跑。旧节点保留，只有新的、独立授权任务才可按既有默认 SDK 路由运行。

生产默认与旧节点未变更，不需要对生产执行反向切换。**业务验收、完整监控验收、无未决远端执行三个门槛没有同时成立，不提出灰度或替换建议。**