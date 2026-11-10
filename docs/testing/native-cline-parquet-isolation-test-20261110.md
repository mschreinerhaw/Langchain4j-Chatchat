# Native Cline Parquet 文件独立能力验证（2026-11-10）

**结论：已取得独立分析真实 Parquet 的成功证据，但五次稳定性未通过，严格等条件对照未达成。**

本轮完成 Native 5 个独立 Session、官方 CLI 5 个独立 Session；每组产物七项验收均为 **2/5**，同时正常结束且产物合格均为 **1/5**。保留全部失败及另 5 条未创建 Session 的准备失败，不用退出 0、文件存在或统计表部分正确代替完整验收。

本轮没有修改 Cline Core、共享模型配置、数据文件或验收标准；没有切换默认 Coding Agent Node，没有整改 Runtime OS 业务流程。新增文件均为本轮验证脚本、报告及证据。

## 1. 固定版本及环境

| 项目 | 实际固定值 |
|---|---|
| 分支 | `feature/runtime-cline-cli-native-20261107` |
| Native 适配源码 | `c167da87ddfc9dbdab813efd8444d640421ff438`；全部 src 文件 Hash 见 source-manifest.json |
| Native 入口 | `cline-agent-server/src/server.mjs`，直接文件/原生命令模式；不是依赖 Runtime 合同的 business-server.mjs |
| Native Standalone | 4.1.26；资产 manifest 固定来源 `a459f1d04dea030833b558be645011ab739c463b`，1850 个资产通过校验 |
| 官方原生 CLI | npm `@cline/cli-linux-x64@3.0.74` 官方二进制；验证发布 SHA-512 integrity，未自行编译/修改 |
| 两组测试镜像 | `sha256:ee3100eabd0862d0f60f0777d9884b5d06e50cfeb80e1fb166a22db70ffd793d` |
| 依赖 | Node v24.21.0、Python 3.11、pandas 2.3.3、pyarrow 26.0.0、numpy 2.3.5 |
| 模型 | 当前直连 Native profile 的 `qwen3.7-plus`；两组真实回执一致，没有改用 DeepSeek/其他模型 |
| 每任务预算 | 最多 12 次转发模型请求；第 13 次由测试代理拒绝。Token 如实计量，没有设置总 Token 上限 |
| 工具及超时观察上限 | 100 次工具调用、900 秒；没有任务触发工具上限 |
| 容器资源 | 每任务 2 CPU、3 GiB、256 PIDs、cap-drop ALL、no-new-privileges；原始 test.parquet 单独只读绑定 |
| 初始工作目录 | 仅 test.parquet；每次全新 workspace、state、Session，无预先分析代码/字段映射/统计答案 |

现有直连 Native 镜像无 Python，准备阶段仅将原有 Native runtime 资产复制到已有 Python+Node 基础镜像；两组均使用上述同一新镜像。不是对既有业务服务热替换环境。Native runtime 的 cline-core.js SHA-256 为 `88f3fc34f48baf44e065d959f5f5346a4b807cc7095d571a9f06cf91a6e84a3a`；CLI 二进制 SHA-256 为 `80ee64f82f895ccc695b95acd18252e00f17186a308257d7b169d444a2b27ef4`。

模型代理只逐字节转发原请求、记录真实回执和执行固定请求数上限，不提供分析工具或修复代码。模型思考内容及凭据不导出 Git。原始响应保留授权服务器，已知用量与缺失用量分别记录。

## 2. 原样指令与数据

> 请分析工作目录中的 test.parquet 文件。你可以自主读取文件、探索字段、编写 Python 程序并运行。请分析数据规模、字段类型、缺失值、重复值、数值分布和潜在异常，最终生成 analysis.py 和 report.md。报告必须基于真实计算结果，不允许编造数据。

数据来自 [NYC TLC 官方数据页](https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page) 的 [2020 年 4 月黄色出租车原始 Parquet](https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_2020-04.parquet)，4,442,620 字节、238,073 行、19 列。未裁剪、清洗、改列名、改类型或重新序列化，只按字节复制为 test.parquet。

SHA-256：`3104e1e5548ade630988a4a8b9ec76c51a057b88159f78676c7d344f48491450`。10 个正式任务完成后均验证文件未变。没有调用 Runtime SQL/API 模板，没有使用前一轮客户数据或适配后的 p0/c0 数据包。

原始 Arrow Schema 如下；报告里的 datetime64[us]/object 是 pandas 读取视图，不表示测试前修改了物理 timestamp/string/null 类型。

```text

VendorID: int64
tpep_pickup_datetime: timestamp[us]
tpep_dropoff_datetime: timestamp[us]
passenger_count: double
trip_distance: double
RatecodeID: double
store_and_fwd_flag: string
PULocationID: int64
DOLocationID: int64
payment_type: int64
fare_amount: double
extra: double
mta_tax: double
tip_amount: double
tolls_amount: double
improvement_surcharge: double
total_amount: double
congestion_surcharge: double
airport_fee: null

```

独立基准在另外的只读、无网络容器中读取原始文件，不执行 Cline 源码，也不向任务目录写入结果。主要基准：重复行 0；airport_fee 缺失 238,073；另四列各缺失 19,580；总缺失单元格 316,393，整体缺失率 6.9946038223%；负车费 1,295；零时长 286；距离 >100 英里 12。源统计、全部数值列描述、IQR、偏度/峰度、相关性及额外声明复算见 independent-baseline.json。

## 3. 十次真实执行

N1—N5 对应原始目录 native-6—native-10；native-1—native-5 是准备失败，单独列于第 6 节。各组内部顺序执行，两组部分时段并行，工作区及预算互不共享。未向会话追加人工提示或代写程序。

Token 为真实 provider 回执的 prompt / completion / total，completion 已含其内部 reasoning 用量，不重复相加。工具数为完成回执数，包含失败回执；一次 run_commands 可包含多个子命令。耗时包含终止清理。原始 collector 的 PENDING 是验算前快照，最终判定以 final-review.json 为准。

| 样本 | 模型请求 | Token（输入 / 输出 / 总计） | 工具回执 | 秒 | 执行终态 | 七项产物验收 | 正常结束且验收通过 |
|---|---:|---|---:|---:|---|---|---|

| [N1](evidence/native-cline-parquet-isolation-20261110/native-6/summary.json) | 7 | 67,060 / 8,812 / 75,872 | 6 | 163.805 | turn_completed | 通过 | 是 |
| [N2](evidence/native-cline-parquet-isolation-20261110/native-7/summary.json) | 12 | 186,434 / 14,779 / 201,213 | 12 | 901.587 | DEADLINE_EXCEEDED | 失败 | 否 |
| [N3](evidence/native-cline-parquet-isolation-20261110/native-8/summary.json) | 12 | 114,699 / 8,437 / 123,136 | 12 | 259.038 | MODEL_ERROR_OBSERVED；采集器中断清理（原 summary=null） | 失败 | 否 |
| [N4](evidence/native-cline-parquet-isolation-20261110/native-9/summary.json) | 12 | 238,999 / 16,902 / 255,901 | 11 | 308.584 | turn_completed | 失败 | 否 |
| [N5](evidence/native-cline-parquet-isolation-20261110/native-10/summary.json) | 12 | 175,301 / 12,320 / 187,621 | 12 | 242.823 | MODEL_ERROR_OBSERVED | 通过 | 否 |
| [O1](evidence/native-cline-parquet-isolation-20261110/official-1/summary.json) | 12 | 219,980 / 9,149 / 229,129 | 12 | 193.602 | CLI_EXIT_1 | 失败 | 否 |
| [O2](evidence/native-cline-parquet-isolation-20261110/official-2/summary.json) | 11 | 203,665 / 14,521 / 218,186 | 10 | 268.003 | CLI_EXIT_0 | 失败 | 否 |
| [O3](evidence/native-cline-parquet-isolation-20261110/official-3/summary.json) | 12 | 204,695 / 16,237 / 220,932 | 12 | 314.556 | CLI_EXIT_1 | 失败 | 否 |
| [O4](evidence/native-cline-parquet-isolation-20261110/official-4/summary.json) | 11 | 260,747 / 19,025 / 279,772 | 10 | 345.846 | CLI_EXIT_0 | 通过 | 是 |
| [O5](evidence/native-cline-parquet-isolation-20261110/official-5/summary.json) | 12 | 204,302 / 13,958 / 218,260 | 12 | 306.832 | CLI_EXIT_1 | 通过 | 否 |

本轮共 113 次真实转发请求，真实回执合计 2,010,022 Token，用量回执均完整；测试代理拒绝不算 provider 成功调用。拒绝发生在固定预算耗尽，不应误报成上游服务限流或模型不可用。每次实际模型、工具 schema Hash、采样参数、用量及时间均保存在对应 summary.json 的 modelReceipts 中。

### Session 身份

| 样本 | 真实 Cline Session ID | Native HTTP Session / CLI conversation |
|---|---|---|

| N1 | `1794288397409_q98xt` | `0b8ac461-12d8-4859-ac6d-fc5ea5499c57` |
| N2 | `1794288561706_z8fe0` | `120e9d6f-053c-4179-9ee6-164c48046b9c` |
| N3 | `1794289464028_oyhcn` | `df3f6329-d81f-4d84-b568-caaf6d00c5d4` |
| N4 | `1794289743791_y6v3m` | `96503cf0-01ba-4894-bf2e-b877d24e51b3` |
| N5 | `1794290053087_hcf22` | `cbdb0d19-62e5-46a1-b7fc-b3b31857f53e` |
| O1 | `1794288130267_i8nja` | `conv_1794288130340_3mj0s9i` |
| O2 | `1794288325383_tf71z` | `conv_1794288325468_0n75cdz` |
| O3 | `1794288591917_i0hth` | `conv_1794288592000_poznzsq` |
| O4 | `1794288907079_3corf` | `conv_1794288907169_o193tkz` |
| O5 | `1794289253530_dq1we` | `conv_1794289253618_b144dub` |

正常完成的 Native 会话也在取证后取消并停止其专属 Worker 以释放资源；清理后的 persistedNativeState=cancelled 不覆盖已观察到的 turn_completed。N2/N3/N5 则分别保留超时、错误观察和主动清理原因，未改写成成功。

## 4. Python 执行、修复与独立验算

| 样本 | 真实执行与产物 | 独立复核及结论 |
|---|---|---|
| N1 | editor 写入 12,161 字符被 6000 限制拒绝；Cline 自主改用已授权 run_commands 写入源码，真实运行 analysis.py 成功；两个文件齐全 | 212 项自动检查匹配；另审读完整报告/源码，隔离副本复跑退出 0；通过 |
| N2 | Python 成功生成报告；12 次模型后发生不可恢复预算错误，Server 仍 running，最终按 900 秒超时清理 | 172 项表格检查匹配，但总结缺失率写 2.15%，真实 6.9946%；声称至少半数小费为零，真实约 45.1593%；失败 |
| N3 | 首个执行版本 analysis.py:126，date_min 未定义导致 NameError；编辑修复未完成；无 report.md | 最终源码隔离复跑仍退出 1。观察到不可恢复模型错误后中断采集器，其 finally 完成清理；失败，不剔除 |
| N4 | 正常完成，analysis.py 能执行，两个文件齐全；报告由 Cline 另写 | 122 项检查匹配，但五个 IQR 异常计数错误；例如车费 28,756 应为 22,775，零时长 1,049 应为 286；失败 |
| N5 | 首个执行版本 analysis.py:282，analyze_categorical 未定义；Cline 自主编辑，第二次真实执行成功，生成完整报告；随后第 13 次模型请求被拒绝 | 223 项检查匹配，源码复跑退出 0；声明的关键字段重复数 15、短于 1 分钟 4,256 另行复算匹配。七项产物通过，流程未正常结束 |
| O1 | Python 真实 SyntaxError:51；`python3 analysis.py 2>&1 \| head -150` 返回的 shell 状态被 head 掩盖，工具 success=true 不表示 Python 成功。Cline 尝试修改 | 无 report.md；最终源码隔离复跑仍在第 111 行报 IndentationError；预算耗尽退出 1；失败 |
| O2 | 多次真实探索，生成源码及报告，CLI 退出 0；源码复跑退出 0 | 161 项匹配，但 pd.cut(right=True) 的首个区间含 0，被标成 <0，报 1,462 而非 1,295；失败 |
| O3 | 真实 Python 统计成功，报告另写；预算耗尽退出 1 | 98 项匹配，但“>100 英里约100+条”与真实 12 条冲突；报告宣称脚本自动生成报告，保留脚本及隔离复跑均未生成报告；失败 |
| O4 | CLI 正常结束；完整源码、报告；隔离源码复跑成功 | 205 项匹配；另核对地点 Top 10、同地点 20,122 及叙述；通过 |
| O5 | Python 真实完成完整统计并生成报告，源码复跑成功；最后因模型请求预算耗尽退出 1 | 266 项匹配；费用分项按报告声明公式复算为 87,397 / 平均绝对差 0.91467344 / 最大 4.50。七项产物通过，流程失败 |

自动验算检查报告里实际声明的值，按显示精度允许舍入误差；不能解析的排版保留 REVIEW_REQUIRED，再审读源码、原始工具输出及叙述，不以“解析到的部分都对”自动判成功。逐项七条件矩阵见各目录 final-review.json。行业字典、监管解释不作为本轮已经独立认证的结论。

补充复跑只使用复制的最终源码和同 Hash 输入，在无网络、只读根文件系统、无额外能力的独立容器中执行，不改原始工作区，不计入正式 10 次成功数。N4/O3 的脚本只打印统计，不能据补充复跑伪造它们重新生成报告。正式 Python 成败以 tools.json 中真实调用输出为准。

每条的 analysis.py/report.md（存在时）、tools.json、events.json.gz、versions.json.gz、验算记录及复跑摘要均保留。versions 是文件观察快照；完整原生工具输入和模型原始请求/响应在授权服务器保留，可核对每次变更。没有要求 Host 或 Java 替 Cline 写业务代码。

## 5. 官方 CLI 与 Native Server 差异：严格对照限制

**本轮没有满足“相同可用工具条件”，也没有建立生成参数完全一致，不能把以上两组当作严格 A/B 或据成功率给平台排序。** 这是测试准备不足，保留全部结果，不通过修改配置使本轮变成“通过”。

| 项目 | Native Server | 官方 CLI |
|---|---|---|
| 真实模型/文件/镜像/请求数上限/超时 | 相同 | 相同 |
| 实际发送的 max_tokens | 32000 | 未显式发送，使用 provider 默认 |
| 暴露工具 | read_files、search_codebase、fetch_web_content、editor、ask_question、run_commands | read_files、search_codebase、run_commands、editor、ask_question，以及 team_* 系列 |
| 有效授权方式 | 每次权限请求由采集器按固定 allowlist 批准；fetch_web_content 未授权 | CLI auto-approve；试图用泛名 team 禁用工具，未匹配实际 team_* 名称 |
| 实际调用工具 | 均仅为 read_files / editor / run_commands | 同左，无 team/MCP/模板调用 |
| 遇不可恢复预算错误 | 投影为 model.error，但可能仍 running | CLI 退出 1 |
| 时区表现 | Worker 环境导致报告时间多为 UTC | CLI 报告多使用镜像 Asia/Shanghai；耗时及原始模型时间统一按 UTC 记录 |

两个发行入口的各自二进制/资产已固定 Hash，但未证明其内嵌 Core 为同一构建；不能将差异直接归因于 Core。基础问题已排除“完全不会读 Parquet”：两组各有完整、正常结束且验算通过的真实案例。按用户判定矩阵，可继续检查 Runtime 集成，但本轮没有执行集成整改，也没有证明集成链路稳定。

## 6. 准备失败及采集器修正

最初 native-1—native-5 在 HTTP listener 尚未就绪时收到 ConnectionResetError；没有创建 Cline Session、没有提交指令、没有模型调用，各约 3.3 秒。均保留在 cases.json 和各目录 failure.json，不伪装成正式会话成功或静默删除。随后仅将采集器就绪检查改为先 GET /health/ready，再单次 POST /v1/sessions；正式 Native 会话为 native-6—native-10。

N2 已完整观察不可恢复 model.error 后 Server 仍 running，直到 900 秒。N3 同类错误发生后，停止旧采集器并由 finally 清理，原 summary 的 terminal=null 原样保留，failure-observation.json 补充实际原因。最后两条采集器识别 recoverable=false 的模型错误后结束观察并清理，不再等待剩余超时。各版采集器 Hash 在 final-review.json 中；预算、900 秒上限、工具授权、数据、模型和七项验收标准均未改变。

## 7. 根因位置与最小整改建议（本轮不实施）

| 已证实问题 | 对应代码/证据位置 | 最小下一步 |
|---|---|---|
| Native 错误事件不收敛状态 | `cline-agent-server/src/server.mjs:60` 只 emit model.error，完成状态依赖后续 done；N2/N3/N5 有真实错误与 running 投影 | 单独修复不可恢复错误的终态投影及清理，保留原始错误，不修改 Core |
| 等条件准备不足 | `scripts/verify-native-parquet-isolation.py:140` 的 disabledTools 泛名未覆盖实际 team_*；逐次 modelReceipts 记录真实 tools/max_tokens | 下轮在转发模型调用前核对工具描述及生成参数；先建立可审查的等条件配置，不事后调整本轮样本 |
| 长源码编辑拒绝/截断 | 两组 tools.json 均有 editor 6000 限制；N1 自主采用允许的命令写入，O1 留下语法/缩进错误 | 区分工具输入拒绝与 Python 错误，验证官方支持的分段编辑；不增加预算或绕过业务环境权限 |
| 报告硬编码叙述与真实计算矛盾 | `native-7/analysis.py:240,331`；`native-9/report.md:202,225` | 要求 Cline 用其计算结果构造总结；独立验算同时检查表格与叙述，不能只查文件存在 |
| 分箱边界标签错误 | `official-2/analysis.py:100` 的 bins/labels/right=True | 对声明的区间边界独立复算；不得用业务名词掩盖统计口径差异 |
| 实际 Python 错误被 shell 管道掩盖 | O1 真实工具输出 success=true 同时有 SyntaxError | 核对 Python 退出状态与 stderr，不以整个 shell 管道退出状态作为 Python 成功证据 |

## 8. 原始证据与复核入口

授权服务器：`/opt/cline-parquet-isolation-20261110/`。

- `data/test.parquet`：未改动的真实文件。
- `runs/<case>/`：原始 Session 状态、工具/权限日志、全部模型请求/响应、源码快照、产物和验算记录。
- `reproduction/<case>/`：补充隔离复跑 stdout/stderr/副本；不替代正式产物。
- `evidence/`：固定计划、源码/环境 Hash、原始统计基准、各阶段采集日志及最终审读矩阵。
- `private/`：只留授权环境的模型连接凭据，不导出 Git。

仓库脱敏入口：[全部样本](evidence/native-cline-parquet-isolation-20261110/cases.json)、[最终矩阵](evidence/native-cline-parquet-isolation-20261110/final-review.json)、[数据基准](evidence/native-cline-parquet-isolation-20261110/independent-baseline.json)、[版本及环境](evidence/native-cline-parquet-isolation-20261110/provenance.json)、[原始文件 Hash 清单](evidence/native-cline-parquet-isolation-20261110/private-evidence-hashes.json)、[清理及现有服务状态](evidence/native-cline-parquet-isolation-20261110/final-environment.json)。

10 次正式 Session 均保留，测试容器已全部停止；现有业务 API、Gateway、Monitor 仍运行。未更换默认节点、未合并 main、未删除旧证据。

验证脚本：`scripts/verify-native-parquet-isolation.py`（采集）、`verify-native-parquet-isolation-audit.py`（原始文件独立统计）、`verify-native-parquet-isolation-report.py`（报告复核）、`reproduce-native-parquet-isolation.py`（补充隔离复跑）、`export-native-parquet-isolation.py`（脱敏导出）。本轮仅做脚本语法及证据一致性检查，没有把单元测试或补充复跑算成 10 次正式任务。
