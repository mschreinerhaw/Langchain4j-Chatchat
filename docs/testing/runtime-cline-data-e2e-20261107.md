# 数据供给与行情探索端到端验证报告

本轮先行[完成度审查](../audits/runtime-cline-completion-review-20261107.md)。测试使用真实金融环境，不合成行情、不删记录、不修改共享模型或权限。结论：B曾完成真实回退和归档校验，最终复读因目录刷新被来源校验拒绝，当前端到端为PARTIAL；搜索关闭D完成受限交付；完整交付及三轮稳定性仍不通过。

## 真实样本与证据要求

华是科技，301218，SZSE，source 38，market_quote_daily，2026-11-02。已有真实日快照2943行，Parquet SHA `32e2b1184c69f460d67a99bbb1f34f4fed3d994116552cb45426f2c8a0ba2bc4`，1494236字节。独立复算：涨幅19.99%、有效样本2943、竞赛排名12、并列3个（12–14）。行情时点与货币未声明则未知；历史存储完整不代表交易所采集完整。

每个场景必须串联：请求原文及Hash→Cline实际objective→工具调用/返回→源证据→Parquet/schema/manifest→Hash与挂载→Python执行→报告归档→API查看。只有模型请求不证明工具执行；count=0不能自行伪造。外部搜索摘要不得当报价。

| 场景 | 输入/扰动 | 当前证据 | 状态 |
|---|---|---|---|
| A 精确证券匹配 | 原生resolve精确301218及真实日文件 | R7精确匹配/计算已证明；B2内也有标准代码count=1；本轮六个同日文件任务没有完整报告/图表，未取得独立A完整通过样本 | FAIL（完整链）；精确匹配单项PASS |
| B 精确失败后回退 | 原始业务标识SZSE:301218，行情代码字段精确比较，未改生产记录 | B2实际count=0→名称匹配1→同日全文件2943行→Python计算→COMPLETE报告→首次归档API逐字节验证；最终复读403 | PARTIAL（探索计算PASS，当前交付受阻） |
| C 本地不足/搜索可用 | B2同日真实文件包四文件，补充本地没有的官方上市公告；已有联网Omni | C/C2/C3均真实搜索，最终预算耗尽；C3源码本身语法错误，提交与执行SHA一致 | FAIL（完整链）；自主搜索能力PASS |
| D 本地不足/搜索不可用 | 同四文件，既有联网关闭Plus；不切共享开关 | D3完成本地计算、明确MODEL_INTERNET_DISABLED、结构化缺口和归档；业务报告为PARTIAL | PASS（受限交付合约） |

B的最初测试脚本通过Windows标准输入传送中文时转成问号，保留在服务器 `/var/tmp/runtime-cline-acceptance-20261107-B`，不计场景验证或产品失败。修正后由UTF-8文件传输，原需求SHA `07320f206717834fad17509a9d2804ae7e39e97275b86b5ed70890d5e38d8ab1`，服务端objective实际包含同一需求。B2私有证据目录 `/var/tmp/runtime-cline-acceptance-20261107-B2`。

B2已经观察到的真实有序过程：

1. `market_get_history` filters.quote_code=`SZSE:301218`，2026-11-02，返回count=0。
2. 同轮目录精确解析该前缀标识为空；名称“华是科技”返回1个候选，含市场和类型。
3. Cline自主以标准代码和名称核对同日观测，均返回1行，发现source 38。
4. 自主选择 `market_get_day_snapshot`，source 38 / 2026-11-02，count=2943、complete=true，模型只收到文件描述符。
5. 只读独立Python核对全部文件SHA、完整行数、市场/交易日/来源及过滤结果，最终报告归档API校验通过。精确查询曾被模型重复一次，不忽略这一无进展行为。

B2任务 `analysis-coding-cad2bf2e36e82374f4d15bca17830ebd1d3010a6498968aae049af958f7cd16b`，12次模型调用、4次Python（含NameError和输出契约修复），最终operation `cline-95a3dccefd18f1aed503f2d76f4441656f57d2a2a9cdf5fff34eb3ac72233b4c`。收盘81.44（币种未知）、涨幅19.99%、排名12、并列3；与独立过滤一致。报告保留单日/单源范围、单位与覆盖限制。原始Markdown见[B真实业务报告](evidence/runtime-cline-acceptance-20261107/B-accepted-business-report.md)。无图表要求的文本合约，不冒充图表验收。

归档14684字节，SHA `03f90ccc21f32102133e40a56bea89f875b125472369c0621caca16be9552f50`；文件ID `6c586a8c74f8c78771d8b1952c304957fa995cfdda768f9c5feceba1cdfe4224`。已验证API返回单行与不可变文件一致、同operation停止证明与引用校验；详见[B文件证明](evidence/runtime-cline-acceptance-20261107/B-day-file-verification.json)和[B归档证明](evidence/runtime-cline-acceptance-20261107/B-native-report-archive-proof.json)。

C/D及性能冻结材料统一为B2实际日快照的manifest、schema、Parquet和既有生产者声明，四文件实际SHA分别为 `cdbaf9ed6abf723ae344cd22e2dee79c2758a7846e258e79fa7cd4f9add267ce`、`34a62adbd6a1f46555314931a71d1838ffd61b7deaed91ad848ab8ebb15d7bdd`、`32e2b1184c69f460d67a99bbb1f34f4fed3d994116552cb45426f2c8a0ba2bc4`、`eddedb8e96428313fd01f5c5ed7205dcd72e7713f9cb3e2937ea34ac34e244fa`。未用旧26行样例代替日行情。

## 搜索场景最终复测

C3 `analysis-coding-8d31d9eec775afa12a640f954c8b5460df38e1c07f72d0b2198160bec97f162a`：会话为AVAILABLE/CONFIGURED_NOT_PROBED（配置可用不冒充已探测服务），Cline实际选择两次web_search和三次web_fetch，保留来源和检索时间。最终BUDGET_EXCEEDED，无可交付报告。首次29498字节源码SHA `ac23c65ca4d0c46c0fc0f265c709df27b0fba0e6601a0d9cf0809a28f7190433`，独立compile在350行报unmatched ')'；执行反馈SHA完全一致。后续原生编辑产生新版本，但仍有语法错误。证据没有支持“Runtime传坏代码”或“Search未开放”的推断。引用元数据修复虽通过离线鉴权/边界回归，本轮没有到达最终报告引用修复，不能宣称其模型端到端通过。

D3 `analysis-coding-13b69957f3a4d079017563d578eafe7685cd154d77453ac01cb3076367879aa8`：会话明确UNAVAILABLE/MODEL_INTERNET_DISABLED/serviceChecked=false，无web工具且未反复重试；继续本地Python并归档PARTIAL报告。报告写明公告上市日期、发行数量和官方原文URL缺失，保持价格货币未知、成交量为万单位、单日单源限制。主要本地数值81.44、19.99%、排名12/2943与独立复算一致。报告原件见[D3受限业务报告](evidence/runtime-cline-acceptance-20261107/D3-accepted-business-report.md)。PE=0及其升序排名仅为原始字段运算，不能据此认证估值含义；报告未充分讨论此口径风险。

D3归档6348字节、SHA `4d92d0d303ca856e46453444a5b58b7e5536b19d5d618fad7cb2eac90c1b77e6`、文件ID `43d9f2320d9ac5448d4be918d233ebb1481c15c02cf8457801840a1837fdb912`；同operation `cline-d35e2b0bc183154e745e296338bb6a003925acdd480a4467fd95d7d0e7699371`停止证明、引用、字节Hash及API结果均核对通过。D3没有明确解释并列3个的名次处理，因此受限交付通过不等于全部本地业务要求完成。配置关闭不等于供应商宕机；真实供应商故障在线场景仍UNKNOWN，现有故障单测不能替代。

脚本 `prepare-runtime-business-acceptance.py` 只准备测试请求；`verify-runtime-autonomous-resources.py` 提交一次原生任务并只读收集有序工具证据，不在Java/Python编写搜索或分析循环。每个请求有独立intent；未知响应只轮询，不重放。

## 最终场景覆盖与边界

| 场景 | 验收结论 | 证据边界 |
|---|---|---|
| A 精确匹配到完整报告及图表 | FAIL | 精确查询成功不等于完整链通过；未用B的文本合约冒充图表合约 |
| B 精确未命中后自主全日回退 | PARTIAL（最终交付） | 真实探索计算和首次文本归档PASS；目录来源刷新后旧报告下载被严格校验拒绝 |
| C 本地不足且Search可用 | FAIL（完整链） | 自主搜索及来源/时间保留PASS；C3代码和修复失败，不能以搜索成功替代报告 |
| D 本地不足且Search不可用 | PASS（受限交付） | D3明确关闭原因、本地计算和缺口；仍有并列/PE说明不足，非无条件完整业务成功 |
| E 同快照完整报告和图表归档 | FAIL（本轮完整链） | 新六任务没有可验收报告；Plus3仅有两个未关联的Plotly草稿。旧R5四图仅为归档/渲染补充回归，不满足本轮同快照完整交付 |
| F 历史超长补丁 | PASS（离线） | 真实28866字符样本通过；实际24182字符新拒绝也将完整分段提示返回模型，不声称模型自主分段成功 |

六个最终对照任务均核对同一组四个文件的实际Hash和当前nativeFileSchemas绑定，[六任务schema证据](evidence/runtime-cline-acceptance-20261107/native-schema-delivery-proof.json)明确39列、2943行、当前fileId及原始manifest绑定。即使映射正确交付，模型仍可能误读原始schema与外层schema；该现象不能自动归类为文件缺失或权限不足。该文件入口与B的自主资源工具入口不同，不能因为B成功就宣布所有入口稳定。

原始金融值未被写成测试常量交给模型；独立复算只作验收，不代写或改写模型报告。所有完整日行情留在受控文件中；B实际day工具回执没有把items整批注入模型上下文。权限、市场/日期/来源、文件大小、Hash、完整存储快照和资源限制均保留证据。存储快照完整不证明交易所采集完整。

附加停止回归也复用了相同真实文件，但主动取消不计分析成功。首次在存储准入阶段被507拒绝，确认无Coding任务后，清理本轮有确切准备记录且无容器引用的两个未部署镜像及对应缓存；原JAR、业务对象、日志、已部署和回滚镜像保留，未降低MinIO保护阈值。随后恢复原节点，最终CANCELLED，原契约/预算不变，停止后新增模型或Python开始数为0。详见[停止证明](evidence/runtime-cline-acceptance-20261107/stop-validation-proof.json)与[容量恢复范围](evidence/runtime-cline-acceptance-20261107/owned-unused-image-capacity-recovery.json)。资源不足不是业务行情不存在。

本次真实探索覆盖MCP/source38/该证券和该日快照。其他PostgreSQL/OpenSearch发现路径、跨市场重名和全部别名没有逐一在线穷举，保留UNKNOWN，不扩大本样本结论。

最终复读时间2026-11-06T22:10:43Z：B2返回403/SOURCE_CHANGED。逐一通过原授权MCP读取并比较19项来源，18项Hash相同；唯一不一致为名称解析候选的 `source_refreshed_at`，证券代码/名称/市场及其余候选字段相同，完整日快照Hash仍一致。来源刷新不等于行情不存在，历史成功证据也不等于当前下载成功；没有绕过来源校验。D3复读200，6348字节及SHA与归档一致。详见[最终复读](evidence/runtime-cline-acceptance-20261107/final-report-readback-proof.json)。B2归档JSONL为14684字节；公开Markdown是从其reportMarkdown字段提取的6175字节文本，两者不是同一文件或同一Hash。
