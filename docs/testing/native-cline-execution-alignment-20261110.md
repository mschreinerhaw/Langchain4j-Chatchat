# Native Cline 执行限制解除与能力对照记录（2026-11-10）

本报告记录真实成功、失败及环境差异；当前仍在执行最终 Runtime 三次独立 Parquet 复测，**不能宣称 P0 全部验收通过**。五类任务 × 三端 × 至少三次的完整矩阵尚未完成。依用户优先级，先验证 Parquet 的真实计算、错误修复、产物与独立验算，再扩大场景。

## 源码、部署与范围

- 分支：`feature/runtime-cline-cli-native-20261107`；基线 `aa33f77c9e1b675bb0c0a82409001cc93e4d9de3`。
- 实现提交：`2173ebc5f`（unlimited 与普通异常）、`8187fd3fb`（声明的分析产物）、`2bc064126`（主 JSONL 选择）、`fbe34ba55`（观测指标）、`3bbc81f59`（消息条数隐含上限／真实派发状态）、`37254518a`（独立 Session 请求参数）、`1bacaaceba4dcc10daefa316288d23ef9145238b`（进程捕获层保留任务 stderr）。详细来源、默认值、终止路径见 [源码审计清单](native-cline-execution-limit-audit-20261110.md)。
- 服务器仅部署隔离实例：API `18410`、Gateway `18411`、MCP `18412/18413`、Redis `18414`、Monitor `18416`。监控入口：`http://192.168.195.221:18416/monitor/`，沿用用户已提供的应用登录身份；报告不收录口令。
- API／MCP 使用独立克隆数据库、目录和 Redis；专用 rootless Executor 使用原有授权模板。原服务、默认部署的 Coding Agent Node、共享模型／服务商定义、Cline Core 和测试原文件均未修改。
- 最新 API／MCP 加载 `release-1bac/classes`，75 个类／资源条目经过 SHA-256 校验；包含 `/python/runtime_capture.py`，不能仅更新 Java 类或配置。Gateway／Monitor 运行 `3bbc81f59`；Runtime Worker `afe24354076b877ee2cf9061cbf5e3210952b16d2f2d4fc5862618bd52f84c02` 仅更新 Native Server 的错误终态投影。

## 固定数据与测试条件

真实原文件为 NYC TLC `yellow_tripdata_2020-04.parquet`，工作文件名 `test.parquet`，4,442,620 bytes，238,073 行、19 个原始字段。

SHA-256：`3104e1e5548ade630988a4a8b9ec76c51a057b88159f78676c7d344f48491450`。

原始字段依次为：`VendorID`、`tpep_pickup_datetime`、`tpep_dropoff_datetime`、`passenger_count`、`trip_distance`、`RatecodeID`、`store_and_fwd_flag`、`PULocationID`、`DOLocationID`、`payment_type`、`fare_amount`、`extra`、`mta_tax`、`tip_amount`、`tolls_amount`、`improvement_surcharge`、`total_amount`、`congestion_surcharge`、`airport_fee`。两个时间字段为 Arrow `timestamp[us]`，`store_and_fwd_flag` 为 string，`airport_fee` 为 null；完整 Schema 在独立基准 JSON 中保留。

最终对照使用同一 `qwen3.7-plus`、8192 单响应输出 Token、`reasoning_effort=low`，不设置任务模型／工具／普通修复次数上限；固定 1200 秒任务截止时间。独立 Native 与 Desktop 的透明转发代理保留真实请求参数、响应 Hash 和供应商 usage，不改写请求体。Runtime 使用固定 Java 模型适配配置。输出 Token 的单响应保护不等于累计 Token 或请求次数预算。

用户已明确接受**功能等价工具**口径，并要求记录权限和环境差异：

| 入口 | 实际版本／执行路径 | 环境与权限 |
|---|---|---|
| Desktop | Windows 本机 Cline Desktop 0.0.49；现有 Desktop sidecar `/transport` 创建独立 Session。已找到 VS Code 扩展 4.1.26，但本组执行入口为实际 Desktop，未用 CLI 冒充 | Python 3.14.3、Pandas 3 系列；本地命令、编辑／读文件。最终三次任务只覆盖实际使用的工具，额外可见工具列入原始回执 |
| 独立 Native | Server 独立新 Session，Core 4.1.26／源码标记 `a459f1d04dea030833b558be645011ab739c463b` | Python 3.11、Pandas 2.3.3、PyArrow 26；允许受测容器内命令、读写文件；输入只读挂载 |
| Runtime + Native | 同一当前 Runtime Core，受管 Python 经既有 MCP 回调执行；API／MCP 使用本报告列出的代码覆盖 | 禁网、不安装依赖；保留用户／租户、路径、资源授权和停止对账；额外要求 `rows.jsonl`、证据引用、归档及结果复核回执 |

最终独立 Native 的 Core SHA 为 `03ec8a39a97a41a5dd8709d4281e95ad17d89c59429ab80ee51fb3e20da32241`，与 Runtime Worker 相同；runtime-manifest SHA 为 `40e55f9d35788dd1a208a1229d2a0ccc03432b0f00d59c562b841a19b91d7989`，1850 个 manifest 文件逐项验证。早期诊断批次使用另一个 Core Hash，不能混算为同版本对照。

Pandas 2／3 对同一 Arrow string 字段可能显示 `object`／`str`；这是读取环境的呈现差异，原文件类型未改。Python 镜像、依赖、网络和额外交付要求不完全相同，不能把所有成功率差异单独归因于 Native 适配层。

正式 Parquet 初始指令使用用户原文，无后续人工补救、字段映射或预生成分析代码：

> 请分析工作目录中的 test.parquet 文件。你可以自主读取文件、探索字段、编写 Python 程序并运行。请分析数据规模、字段类型、缺失值、重复值、数值分布和潜在异常，最终生成 analysis.py 和 report.md。报告必须基于真实计算结果，不允许编造数据。

## 最新固定参数 Parquet 对照

下表中的“数值检查无差异”仅表示已识别和独立复算的条目；检查器始终将未覆盖的文字结论留给复核，不自动给出 PASS。最终成功还需要执行、Schema、任务覆盖、产物与无虚构结果的联合证据。

| 独立任务 | 真实模型请求／SDK 迭代 | 输入／输出 Token | 终态与独立验证 |
|---|---:|---:|---|
| Desktop `desktop-parquet-aligned-1` | SDK 26；真实 HTTP 数以代理回执为准 | SDK 522242／12359 | completed；代码实际运行；202 项数值／Schema 检查无差异。独立复算的原件保留局限见后文 |
| Desktop `desktop-parquet-aligned-2` | SDK 10 | SDK 138884／9657 | completed；原程序独立重跑 exit 0 并生成报告；203 项检查无差异 |
| Desktop `desktop-parquet-aligned-3` | SDK 24 | SDK 587785／16315 | completed；原程序独立重跑 exit 0 并生成报告；270 项检查无差异 |
| Native `native-aligned-v2-parquet-1` | HTTP 16 | 368280／15307 | turn_completed；原代码独立禁网重跑 exit 0。163 项匹配，但正文 1 人乘客占比 81.27% 不匹配任何相应分母；严格正确性失败 |
| Native `native-aligned-v2-parquet-2` | HTTP 12 | 203804／16678 | turn_completed；原代码独立禁网重跑 exit 0。103 项匹配，但将新增辅助列计入原始字段总数，写成 20，真实为 19；失败 |
| Native `native-aligned-v2-parquet-3` | HTTP 8 | 105517／9121 | turn_completed；原代码独立禁网重跑 exit 0。134 项匹配，但乘客数 1 的占比写 74.68%，真实 74.685075586%，按两位小数应为 74.69%；保留这项较小的精度差异，严格判定失败 |
| Runtime `runtime-parquet-1bac-1` | 待执行 | 待执行 | 最新代码下独立复测，结果不得用旧任务替代 |
| Runtime `runtime-parquet-1bac-2` | 待执行 | 待执行 | 同上 |
| Runtime `runtime-parquet-1bac-3` | 待执行 | 待执行 | 同上 |

Native 第一次正文占比的独立结果：全部记录分母为 `74.68507558605974%`，非空乘客数记录分母为 `81.37789311328052%`；`81.27%` 与两者均不相符。第二次的 20 列和第三次的显示精度问题严重性不同，不应概括成无法读取或无法计算 Parquet。三次均实际读取文件、编写并执行了程序；问题在最终报告交付正确性。

Desktop 首次独立复算有一项验证操作失误：脚本将 Windows 输出目录写死，原样复算覆盖了工作目录报告的生成时间。已保留复算后报告及复算前完整 `read_files` 工具回执；从回执逐行提取 380 行原报告文本，确认正文仅生成时间不同。原始字节 Hash 未在覆盖前冻结，不能将复算后的报告宣称为原件，也不能将提取文本宣称为字节级原件。证据在该任务 `replay-provenance/`。其余样本保留独立目录及输入／源码 Hash。独立 Native 第三次程序输出统计到 stdout，Markdown 由 Cline 编辑交付，复跑程序不自动生成 report.md；该事实单独记录，不以程序 exit 0 代替报告验证。

## 计数限制和普通异常的真实证据

| 任务／组件版本 | 真实行为 | 结论 |
|---|---|---|
| `runtime-parquet-final-1`，API `2bc064126`、旧 Worker | 36 次 Java 模型尝试，其中 32 次实际供应商请求；最后 4 次被 64 条消息校验本地拒绝。6 次 Python 执行、5 次修复，1524798 Token；最终 FAILED | 已跨过原 12 次模型／3 次修复，但仍存在隐含消息条数上限。该样本失败，已推动修复，不能报告成 36 次供应商请求 |
| `runtime-parquet-final-2`，同一旧部署 | 27 次模型请求、7 次 Python 执行、1184402 Token；归档 analysis.py、report.md、rows.jsonl；独立重跑 exit 0；132 项早期数值检查无差异 | Runtime PARTIAL／OUTPUT_CONTRACT_VIOLATION，业务验证 INCONCLUSIVE，不能报告为完整交付成功 |
| `runtime-parquet-3bbc-1` | **37 次成功供应商模型请求**，2475703 Token；第 33–37 次真实派发成功。3 次 Python 执行，产物归档 | 64 条消息造成的约 32 次请求上限已真实跨越。1200 秒截止到达后 Runtime 为 BUDGET_EXCEEDED／DEADLINE_EXCEEDED；Gateway 为 UNKNOWN，但停止证明确认 Worker 已退出、无 OOM、Native Session cancelled。不得据此重放旧任务 |
| `runtime-repair-3bbc-1` | 同一 Session 主动 RuntimeError 后修复，40 次模型请求、5 次 Python 执行、1977816 Token；最终 PARTIAL | 普通 RuntimeError 未直接终止任务，但捕获层仍丢弃原文，故完整错误回传要求当时未满足；保留失败证据 |
| `runtime-repair-1bac-1` | 最新捕获器已回传真实 `RuntimeError: ISOLATION_REPAIR_PROBE_20261110` 和完整 traceback；同一 Session 后续继续执行 | 真实原文已贯通；最终执行、产物和终态仍待归档，不能提前算全任务通过 |

错误捕获修复位于 `chatchat-mcp-server/src/main/resources/python/runtime_capture.py` 及 `PythonDockerRuntime.java`。原捕获器只保存计量、Hash 和固定分类，原始 stderr 不落盘；单独修改 Java 归档层不足以恢复已丢弃的原文。隔离节点显式配置 `chatchat.mcp.python.preserve-task-error-details=true` 后，Docker 向 Runtime 捕获器传入受控开关，在用户程序启动前打开不跟随符号链接的日志文件，流式保留当前任务 stderr。默认行为保持原元数据边界，stdout 不进入模型回执。stderr 4 MiB 资源边界明确记录截断、原始字节数和 Hash，不能冒充完整捕获。

## 当前根因与最小建议

1. **真实执行限制**已落实到 admission、持久账本、SDK maxIterations、响应修复、普通 Python 修复、消息数组校验及 Schema／序号等终止路径。没有用大整数、自动创建新任务或重启失败任务模拟 unlimited。单响应、截止、内存、权限、取消、未知在途操作对账等保护仍在。
2. **报告数值与原始 Schema 的正确性存在波动**：Native 最终三次分别出现占比误写、辅助列混入源 Schema、小数显示偏差。源码和产物保持原样。建议下一步先核对各端提示／工具回执／报告生成路径差异，要求报告从程序结果生成并复算；本轮未改 Core、原文件、模型或验收标准来消除失败样本。
3. **Runtime 额外引用复核会阻止最终提交**：`runtime-parquet-3bbc-1` 的 `reviewedArtifact.reportReferenceValidation.valid=false`，回执给出了准确授权 datasetReference，但 Cline 多次提交仍被 `/native/submit` 拒绝，最终触发 deadline。检查 `cline-agent-server/src/business/runtime-tools.mjs` 的 attachReport 和 `business-server.mjs` 的停止／已复核结果条件。建议仅补足可恢复工具错误的具体引用诊断，不取消授权引用或复核条件。
4. **结构验证与业务完成门禁存在分工问题**：通用 `runtime-result-schema` 独立校验不声明业务已验证，结果为 INCONCLUSIVE；`ClineModelCallbackController.finishSdk` 对 Native 完成要求 PASSED／businessValidationPassed，因此实际归档产物可能仍返回 PARTIAL。对应 `CodingEvidenceCollector.Validation.status()`、`CodingValidationPlanService`、`ClineModelCallbackController`、`business/run-state.mjs`。最小建议是明确任务对应的真实独立验算计划与完成语义；不得直接把 INCONCLUSIVE 改成 PASSED 或伪造业务验算。当前未降低这条门禁。
5. **监控与停止状态**：Java 账本已经记录 deadline 终态，但某次 Gateway 仍 UNKNOWN；已有停止证明显示容器 exited。应对齐只读状态投影，不以 UNKNOWN 自动重跑。供应商调用、Java 尝试、本地拒绝分开展示；未知费用不能显示已知零费用。

## 环境失败与未剔除样本

早期克隆环境出现数据库表归属、MCP 授权许可路径、H2 查询池、Gateway 文件所有权及 MCP gRPC 自调用问题。gRPC 自调用形成 186 个等待线程并耗尽宿主资源，线程转储定位后，仅在隔离 MCP 关闭 client、自身保持 server，保留原服务。先前环境失败均保留，不拿部署准备失败替代真实模型失败，也不悄悄删掉。

早期独立 Native `native-unlimited-1/2/3` 使用 max_tokens=32000、未显式 low，以及不同 Core Hash。三次为 24／23／15 次请求。第二次报告有五处明确数值偏差，保留失败；第三次由 Cline 自主安装 tabulate，独立复跑基础镜像缺依赖的失败同样保留，另用原任务已安装包的只读副本复跑，未修改程序。其后 `native-aligned-parquet-1/2/3` 参数试验为 12／29／29 次请求，但 provider 记录缺少 SDK 要求的元数据导致 low 被迁移丢弃；实际 HTTP 参数已识别，因此全部保留为诊断，不混入最终参数一致三次。

早期 Desktop 使用既有 `alibaba-cn` 适配的三次独立 Parquet 任务为 SDK 23／43／30 次迭代，产物和复算均保留；另有一次旧 baseURL 导致 NotFound 的准备失败。Desktop 的 SDK 估算费用与供应商实付费用分开；新代理配置下 SDK totalCost=0 缺少可信价格，不能据此声称免费。

另有最初版本的 5 Native + 5 官方 CLI 隔离测试，原报告 [native-cline-parquet-isolation-test-20261110.md](native-cline-parquet-isolation-test-20261110.md) 保持不变。官方 CLI 不是 Desktop；旧批次有 12 请求限制，不能算作本轮 unlimited 结果。

用户提供的本地 `xuelong_stock_chart.py` 原件未改。已有静态核对发现其两段共 30 行数据仅处理前 15 行，涨跌幅使用了错误字段索引；PNG 存在不等于行情／K 线业务正确。这是既有用户样本的问题记录，不冒充本轮股票行情三次正式对照。

## 回归与证据位置

回归包含真实 H2 连续 300 次 admission／持久计量、已安装官方 SDK 同一 run 的 41 次模型迭代／40 次工具／8 次普通错误、501 条消息桥接、Native／Monitor 69 项、Node 完整 262 项及构建。最新捕获器 Python 回归 14 项、Java 捕获边界／反馈相关 Maven BUILD SUCCESS，独立报告检查器 3 项。确定性 fixture 不计真实模型成功率。日志与更早失败日志均在本机 target 下保留。

独立报告检查器：[verify-native-cline-parquet-report.py](../../scripts/verify-native-cline-parquet-report.py)。它接收独立基准与补充结果、原程序和报告，逐项比较已识别数值并给出 Hash；未识别的文字、执行与业务语义不自动判通过。

原始证据根目录：

- 服务器：`/opt/cline-native-unlimited-20261110/`。`evidence/deployment-3bbc.json`、`deployment-1bac.json`、`final-executor-preparation.json`；`direct/`、`direct-aligned/`、`direct-aligned-v2/`；`runs/<case>/`；`workers-final/`；`verification/`。
- 独立 Native 原始模型 HTTP 请求／响应、usage、工具调用和代码版本：各 cohort 的 `runs/<case>/model`、`events.json`、`versions/`，敏感配置与完整原始日志留在授权主机，不进入公开压缩包。
- 本机 Desktop：`target/native-unlimited-live/<case>/transport.json`、`result.json`、`workspace/`、`verification/`、`replay-provenance/`；实际 Desktop 会话标识及完整工具回执在其中。
- 原数据与基准：服务器 `/opt/cline-parquet-isolation-20261110/data/test.parquet` 和 `evidence/independent-baseline.json`；本机额外复算 `target/native-unlimited-live/evidence/independent-supplement.json`。
- 导出的公开证据应包含产物、真实工具回执、usage、参数与 Hash 清单，排除凭证与模型内部推理；最终复测完成后更新本报告的结果表和证据包。

旧 harness 的 frozen-plan 中存在复制前批次 SHA／轮次顺序的元数据错误；早期 Runtime intent 也未随各组件覆盖同步更新。原件保留，实际部署清单、文件 Hash 和 `public/provenance-corrections.json` 作为纠正证据，不能直接把旧声明当作实际执行版本。
