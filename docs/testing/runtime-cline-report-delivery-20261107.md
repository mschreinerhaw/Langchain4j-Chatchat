# 完整报告及图表交付验收报告

## 已有真实通过证据

[R5四图归档证明](evidence/runtime-data-remediation-20261106/r5-report-archive-proof.json)：Flash 1报告、4个Plotly图均可通过Runtime资产API读取，报告/图表SHA、字节数、数组长度、章节引用、相同operationId的停止回执校验通过。报告含8个独立日期，未把重复16行当16日。重新核对发现其标准化报告 `analysisStatus=PARTIAL`，任务COMPLETED和结构要求COMPLETE不能使它成为无条件完整业务成功。

[R7整日文本计算报告](evidence/runtime-data-remediation-20261106/r7-day-file-verification.json)：2943行完整文件被Python实际计算；最终报告17361字节，SHA `92c84ef52405d25423b012dd939e4703fca70f5451d79929367cbcc47592668d`；API results等于不可变归档，引用校验通过，最终进程已停止。本任务文本合约无图表要求，不能冒充新四图任务。

Python exit=0缺少rows.jsonl的真实反馈保留，系统明确REQUIRED_OUTPUT_MISSING、要求路径和下一步编辑修复。Cline后来修复后才交付。技术成功不认证全部业务结论：R7报告把未知价格货币写成“元”，且99.58百分位措辞不准确；原件保留，不由验收脚本改写。

## 本轮增量与验证

页面四项事实状态之外，增加当前生命周期：提交、数据发现、数据交付、Cline分析、Python执行、报告生成与校验、交付成功/失败，并保留部分交付/缺数据状态。仅从真实事件和终态回执推导；exit=0但缺报告不显示成功，失败任务即使有阶段产物也显示部分交付。

独立修复提交 `fb56f38cc`、`a98ea1f41`、`c63e70532`；前端全量148项通过，Vite生产构建通过。测试覆盖实际market工具发现、输入就绪、Python零退出缺文件、artifact_read复核、终态成功、阶段成果、INPUT_REQUIRED及具体缺失图表路径。修复文件任务空事件面板不可见，以及有阶段报告时错误覆盖正在执行状态的问题。

中间API版本 `c63e705321a922f4c1ddfd51e0681dedb219bb19` 通过原空闲发布和39项工具目录就绪检查，随后用于三轮模型对照；应用JAR SHA `618689c828ea04e492f458336632e5ef2a1c217ff0fe20aaa1869fd86a3be288`。未改变Worker SDK0.0.93、共享模型、权限或预算。真实Chrome只读验证R5报告：四张Plotly图逐个在原有 `sandbox=allow-scripts` iframe渲染，0次execute、0个浏览器错误，准确显示“报告部分交付”，没有把旧业务限制隐藏成成功。

本轮B2曾完成文本合约及首次归档校验，最终旧报告下载受阻详见下文；[数据报告](runtime-cline-data-e2e-20261107.md)提供任务、operation、下载文件ID、SHA和人工数值复核。C2真实搜索后引用不合格，最终预算耗尽；D2成功归档受限报告，仍存在“市盈率TTM”与口径未知声明矛盾，不能认证所有业务结论正确。

真实日志还定位了 `UI-EVENTS`：文件任务 durable 事件没有经过状态接口投影，八阶段前端逻辑因此缺少输入。该缺陷已先记入清单，再以当前任务最后256条安全事件的只读投影修复；不暴露代码、提示词、原始数据或工具参数，Python停止额外核对同operation停止证明，错误提示仅采用同task/operation的既有规范化反馈。历史终态只补进度，不替换原报告；页面恢复后只读补查一次，不重放分析。

## 最终版本与真实页面验证

最终API源码 `e2a97cfacd99d44d528b988cf126f0e96bcec155`，JAR SHA `acc584fa86aa9ee79064bef55e2dd571a56e40072ca02c105d91feecacf0fa41`，镜像 `runtime-os-api:data-remediation-acc584fa86aa`。[部署证明](evidence/runtime-cline-acceptance-20261107/final-deployment.json)记录：六任务全部终止后发布、工具目录39项健康、无数据库迁移、其他容器/会话内容/消息索引不变。发布包含只读进度及停止对账修复，不把它说成纯前端或模型性能改进。

[SDK/Worker证明](evidence/runtime-cline-acceptance-20261107/sdk-worker-unchanged-proof.json)：Worker镜像保持 `sha256:118b02724d1236fb6a2268f9a5b07818e60669928381dca6c2145eef0b22224d`，SDK/core/agents均0.0.93，四个Runtime适配模块与已审查bundle逐字节Hash一致。相关离线记录见[测试日志摘要及完整日志Hash](evidence/runtime-cline-acceptance-20261107/offline-test-records.json)；最终前端149项、停止/状态组合86项通过，各组有重叠，不相加冒充独立用例数。

真实Chrome对新失败任务 `analysis-coding-d321dd5823afd66df8cdf00b8a17394cd472103c89fb05c5ab90a774217c97df` 读取35条真实进度事件，显示：模型已响应、快照已就绪、Python进程已结束、报告交付未通过。生命周期明确“报告交付失败”，完整显示 `/workspace/output/results/rows.jsonl` 缺失和下一次Sandbox无旧输出的修复说明；0次execute、0浏览器错误。[页面失败证明](evidence/runtime-cline-acceptance-20261107/ui-failure-final-proof.json)。模型响应和零退出均未被提升成报告成功。

旧R5四图在最终版本仍逐张实际渲染，[四图页面证明](evidence/runtime-cline-acceptance-20261107/ui-four-chart-final-proof.json)。这是已存在部分报告的归档/查看回归，不是本轮同一日快照E场景完整通过。八阶段转换有单测，真实页面覆盖部分交付、失败和进度补齐；没有声称八个中间阶段均被本轮浏览器逐时在线拍到。

## 新六任务为何没有合格报告

[逐任务失败证明](evidence/runtime-cline-acceptance-20261107/business-quality-proof.json)保留实际执行Hash、退出码、缺失/校验状态、规范化错误和原始输出资产。六个任务均未交付可验收报告；五个没有注册结果/图表资产。Plus3虽有 `distribution.json`、`ranking.json` 两个Plotly草稿，API读取、字节数和SHA通过，但没有必需的rows.jsonl、报告和关联引用，不能当作已交付图表。后续v3程序又出现SyntaxError，不能借用前一次输出目录冒充新执行完整交付。

因此B/D/旧四图有历史归档技术通过证据，而本轮同快照“完整报告+图表”E和稳定生成验收FAIL。最终复读又发现B当前交付受阻，见下文。不能以采集脚本退出0、文件状态AVAILABLE、Python退出0或任务局部产物替代该结论。对本地单位、覆盖、并列和PE语义没有充分说明的报告仍保留业务限制，不在验收脚本里改写原件。

## 最终复读与来源更新

[最终复读证明](evidence/runtime-cline-acceptance-20261107/final-report-readback-proof.json)：B2旧报告当前下载403/SOURCE_CHANGED，D3下载200、6348字节且Hash相同。B2全部19项已披露来源中18项Hash一致，唯一变化是证券目录名称候选的 `source_refreshed_at`；其余候选字段及整日行情快照未变。整个生产任务的来源再校验会因此阻止报告披露。此行为符合当前严格来源版本规则，但暴露长期归档可交付边界；不能把目录刷新误报成行情缺失，也不能在缺少明确版本语义时移除校验。B的历史闭环通过保留，当前交付降为PARTIAL。

[独立发布顺序证明](evidence/runtime-cline-acceptance-20261107/final-release-order-proof.json)逐任务核对终态时间，最晚2026-11-06T21:19:21.790Z；新发布准备上下文始于21:20:05.948Z，当前容器JAR SHA与发布记录一致。停止修复和只读进度修复均在六轮之后发布。

## 真实停止与迟到回执

第二轮Flash的Worker比最后一次恢复查询晚约1秒停止，导致控制面长期RUNNING。`e2a97cfac`仅对已经持久化stopReason的原任务做有界对账；不会改变预算、重新提交模型/代码或把GET状态变成执行入口。未知停止仍返回需对账，不能编造结束证明。62项Analysis测试覆盖延迟回执、到期旧预算及STOP没有SUBMIT权限；Workspace24项继续通过。

[新版本真实停止证明](evidence/runtime-cline-acceptance-20261107/stop-validation-proof.json)：相同真实文件和完整业务问题，首次存储507且无任务准入；释放本轮未使用构建副本后恢复原节点。一个真实模型操作开始后发出停止，1431ms返回CANCELLED，原契约和预算不变，停止后新增模型/Python开始数为0，未误报报告成功。该主动取消任务不计模型分析成功率。

终态写入存在并发竞争，实际获胜回执没有stopReconciliation计数，故具体等待次数及是否命中有界等待分支记UNKNOWN，不以0/false填充；初始标签的修正及原文件Hash一并保留。相同截止竞态在新版本的精确迟到时序由离线测试复现，真实主动停止通过，两种证据不相互冒充。
