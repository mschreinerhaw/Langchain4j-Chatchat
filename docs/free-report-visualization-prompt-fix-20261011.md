# 自由报告中的图表配置 JSON 泄漏修复

## 当前修正：保留能力注入，修复前端展示

根据用户进一步确认，可视化声明注入本身属于正常能力。当前源码已恢复 `injectReportDraft()`，此前将注入视为根因、移除提示词注入的方案已撤回。以下原始修复与部署记录保留作为历史记录，不代表当前源码行为。

当前前端在 `splitReportVisualizations()` 中识别顶层 `json`、`text`、无语言、`json:report-block` 代码块及独立 JSON 段落。完整可渲染声明仍进入原有渲染器。直接 table/chart 配置或无法渲染的声明转换为结构化摘要卡片，展示标题、结论、原因和缺少可用数据的提示，不展示内部配置字段，不从模型配置猜测或编造实际数据。普通 JSON 示例、嵌套代码示例及不完整 JSON 保留原行为。

本次修正已部署生产，部署与浏览器验收见 [报告与探索图部署验证](report-ui-deployment-20261011.md)。下方首次部署属于历史记录。

## 历史修复记录（已被上述方案替代）

## 根因

原始任务 `eb204dd8-d5f3-4788-bac7-cabfd4dc21b2` 的报告包含顶点软件行情表的配置 JSON，并非实际行情表数据。

`ModelNativeAnalysisHarness` 每轮调用 `VisualizationCapabilityInjector.injectReportDraft()`，向自由报告提示词追加图表 schema、允许的图表类型和数据集目录，并明确要求把 `json:report-block` 配置放进 `reportMarkdown`。模型因此将渲染协议混入面向用户的正文。

生产 timeline 中图表校验结果为 `REJECTED / UNKNOWN_FIELD`。模型原生报告的保留策略令 `VisualizationPlanningNode` 将被拒绝的配置转成 `text` 代码块，而不是删除模型正文，所以最终出现不能渲染的 JSON。不是前端遗漏了已验证的数据表。

## 修复范围

- 移除模型原生自由报告链路对 `injectReportDraft()` 的调用；Runtime 的图表能力元数据仍保留用于已有产物的处理。
- 明确 `reportMarkdown` 是面向用户的内容。模型仍自主选择正文、Markdown 表格及用户要求的其他格式，无需使用图表产物协议。
- 不过滤正文 JSON，不重写历史报告，不增加模型调用或固定分析模板。已有显式图表产物的绑定、验证和兼容处理保持原行为。

## 验证

离线 Maven 定向验证：`ModelNativeAnalysisHarnessTest` 23 项、`VisualizationPlanningNodeTest` 9 项、`FinalSynthesisNodeTest` 10 项、`ReportVisualizationAuditTest` 16 项，共 58 项通过。新增回归验证实际模型提示词没有渲染协议注入，数据读取能力仍在，模型返回的普通 JSON 示例和 Markdown 表格原样保留。

API 部署备份：`/opt/chatchat-deploy-backup/free-report-20261011/api.jar`。只替换当前 API 内嵌 agents 模块的 Harness 类，保留此前探索进度修复、其他模块和静态资源。更新前 API SHA256 为 `26c9c99705d440382f6bbcedef3a81352359460356e5263763a44558caffc313`，更新后为 `11e539b0cd00cce515948ccfa6cfdc5273feada2d473f0582d7b637ab8bc882b`。

生产 `/api/v1/health` 返回 UP。原问题以新会话重跑，任务 `af70553e-cb38-37ef-88be-f3d8708ae9ae`、运行 `att-1-05df526f-e2b7-433a-ae5a-a0eb63cef83a` 返回 SUCCESS。最终回答包含正常 Markdown 行情表，没有 `json:report-block`、`chartType`/`datasetRef` 配置或 `text` 配置代码块。证据保存在本地忽略目录 `target/codex-live/free-report-20261011/`，未修改历史任务。

该重跑仅验证本次报告格式修复，不代表融资结论得到充分验证。新报告仍将截断余额推测为约 2.2 亿元，并从单日数据推断近期没有大规模融资买入；这些内容不足以证实近期杠杆资金趋势，不能作为此项格式修复的财务准确性验收结果。
