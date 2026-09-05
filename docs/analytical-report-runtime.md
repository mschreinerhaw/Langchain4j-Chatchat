# 分析报告编排协议

Driver 的 `governed_management_synthesis.v4` 产出结构化发现选择，Runtime 发布
`analytical_report.v1`。旧版 v1–v3 文本协议仍可读取，但不会被默认为已通过新版数据表达约束。

## 职责与数据路径

1. 现有 Worker / Reducer 完成分析、证据准入及合并。
2. `VerifiedReportDataCatalog` 从当前运行的 `deterministicInsightResults` 中提取成功执行的、带来源记录引用的强类型计算结果。
3. Driver 输出 `dataRef`、`visualizationIntent`、问题、观察、解释、业务含义及依据 Claim ID；不输出绘图数值。
4. `VisualizationPlanningContract` 根据实际执行的运算约束图表意图，并生成包含图 ID、字段、单位、排序、数量及关联发现 ID 的 Plan。
5. `ChartDataExecutor` 从选中的计算结果执行排序与截取，保留十进制字符串精度。
6. `ReportComposer` 校验 Claim 与计算结果的记录来源绑定，编排 `AnalyticalInsightBlock`。
7. `AgentTaskService` 将报告协议放入 `uiResponse.analyticalReport`；`UiArtifactService` 将其归档成独立 JSON 资源。
8. `AnalyticalReport.vue` 在聊天内联及归档报告两条路径渲染相同的块。图、表、指标、解释与证据位于同一块。

文本客户端的 Markdown 由同一批 Block 投影生成，不单独编写第二份数据报告。结构化报告存在时，不再追加独立的工具结果图表。

## 发布规则

- 新版核心结论必须同时有文字解释和可验证的数据表达（图、表或指标）。缺少合格 dataRef 的发现保留为数据状态块，不进入核心结论。
- 只有 `executed` 的强类型计算结果可进入数据目录；模型伪造的同形 JSON 不可进入。
- 计算结果引用的每条记录须由发现的依据 Claim 覆盖，不能绑定其他数据集或其他记录。
- 图表和原表共享执行后的行数据。数据表及指标以十进制字符串跨 JSON 传输，避免 JavaScript 改写原始精度。
- 仍需 Driver 对观察、解释、因果关系和业务含义执行审查；来源绑定和数值校验并不证明因果关系。
- 待复核的依据不会被提升为核心结论；不符合图表条件时显示已验证表格或指标，不能通过制造数值补齐图表。
- 失败、降级恢复或输出被拒绝时，不保留上一次的结构化报告。

## 当前支持范围

计算结果 `top_n` 对应 RANK 横向柱状图；`concentration` / `contribution` 对应贡献排名横向柱状图；标量计算结果对应 KPI。
贡献结果的主指标单位（例如 ratio）与明细数值单位分开保存，不能用比例单位标注原始规模。
Top-N 子集不视为完整构成，不生成饼图或 100% 构成图。当前不生成 Pareto 累计线。

协议枚举预留 TREND、COMPARE、DISTRIBUTION、COMPOSITION、CORRELATION、FLOW、ANOMALY。
这些意图尚未接入相应的可信计算结果适配器时，回退为表格，不随意选择图形。
新接入的运算必须同时补充：语义/单位元数据、可信计算结果、Planner 条件、Executor、渲染器和反例测试。

## 验证

- Java：`ReportComposerTest`、`GovernedFinalClaimContractTest`、`AnalysisSynthesisCoordinatorTest`、`UiArtifactServiceTest`。
- 前端：`src/js/ui-artifact/analyticalReport.test.js`。
- 浏览器：`node scripts/test-analytical-report-visual.mjs`，测试真实归档加载路径、ECharts 横轴/纵轴、图表数值、原表精度、证据和手机布局。夹具使用明确标注的测试数据。
