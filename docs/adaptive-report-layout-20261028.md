# Runtime OS 图文报告自适应排版交付

已完成代码改造和用户确认的**受控样本验收**。没有把受控样本标记为真实客户数据，也没有将本次 API/Web 改动发布到生产。已有任务状态修复的现场版本保持不变。

实施前审查见 [审查记录](adaptive-report-audit-20261028.md)。固定结构主要来自前端先摘要、再全体指标、再章节的强制顺序；原协议没有可引用的章节ID和整份报告混排顺序。原协议、CSS和提示词都没有硬性要求四张指标卡。本次继续使用 `python_visual_report.v1`、原证据归档、UiArtifact和图表组件，没有建立第二套报告系统或新增微服务。

## 配置和实际传递链

在现有对话生成入口开启“图文报告”后，可展开“报告排版”：

| 设置 | 可选值 | 默认 |
| --- | --- | --- |
| 排版策略 | auto智能自适应、template指定模板、concise简洁报告 | auto |
| 模板 | briefing结论优先、evidence-first证据优先 | briefing；仅template使用 |
| 内容密度 | comfortable舒适、compact紧凑 | comfortable |
| 自动图表 | 是否生成/展示可选的数据图表 | true |
| 自动配图 | 是否使用有依据的归档配图 | true |

原图文开关保持原行为：排版默认auto不等于强制所有聊天都生成图文报告。配图沿用通过校验的PNG/SVG归档产物，使用 `role: illustration` 区分；数据图表不因为关闭配图而被当成装饰图片删除。没有新增联网图片搜索、图片生成服务或不可信图片URL。

配置复用 `workspaceDrafts` 的租户/用户/工作区作用域保存在当前浏览器。它不是跨设备的服务器级默认设置。每次提交会创建独立快照，随后随原任务和最终报告持久化到服务端；历史报告按其自己的快照恢复，不读取用户后来改变的默认值。

实际链路：

```text
PromptComposer.reportPresentation
  → ChatAssistantView.toolInput.reportPresentation
  → AgentChatModeHandler / ClineContextAnswerService
  → ReportPresentation.read（校验、补默认值）
  → CodingAnalysisService 原任务 objective + CodingRuntimeContext
  → Cline SDK 原模型轮次、原 Python 分析程序及 artifact_read
  → CodingEvidenceCollector.execution.reportPresentation
  → VisualReportArtifacts（请求快照优先于模型自报设置）
  → 原 CODING_VISUAL_REPORT / UiArtifact visual-report JSON
  → ReportRenderer
```

三份现有 Coding JSON Schema 同步允许真实使用的任务上下文字段及reportPresentation；Java上下文白名单和冻结/哈希机制同步支持。配置不会成为MCP业务工具参数：ToolDirect明确移除reportMode和reportPresentation。它不改变模型选择、工具授权、数据权限或执行预算。

## 同一报告协议的最小扩展

继续使用原指标、章节、图表、表格、摘要、数据来源、限制和validation。新增可选字段：

- `presentation`：已经规范化并冻结的五项排版偏好。
- `sections[].id`：章节稳定引用；旧记录缺失时按原顺序得到section-1等兼容ID。
- `layout: {schemaVersion: "report_layout.v1", blocks: [...]}`：模型决定的顺序与有限布局提示。
- `metrics/charts/tables[].dataRefs`：引用该报告授权数据来源；新布局必须逐项绑定。
- `charts[].role/chartType`：图表与配图的区分，以及实际基础图表类型的描述。
- 原结果行中的 `limitations` 会被明确保留到报告中；原始数值继续作为精确字符串保存。

布局例子（仅说明协议，不是强制模板）：

```json
{
  "schemaVersion": "report_layout.v1",
  "blocks": [
    {"id": "conclusion", "type": "section", "ref": "finding", "emphasis": "important"},
    {"id": "trend", "type": "chart", "ref": "price-trend", "span": "full"},
    {"id": "detail", "type": "table", "ref": "prices"},
    {"id": "limits", "type": "limitations"},
    {"id": "sources", "type": "sources"}
  ]
}
```

组件白名单仅有summary、metric、section、chart、table、limitations、sources。ref只能指向当前报告中真实存在的对应元素；不接受内联数据、任意组件名、HTML、JS、CSS或URL。span只允许full/half，emphasis只允许normal/important。共享schema位于 `chatchat-common/src/main/resources/runtime/report-layout.schema.json`；Java和前端均校验类型、字段、引用、重复ID/重复放置及数量上限。

模型在原生成过程选择布局：时间序列可用趋势图，多对象可用对比图，有真实整体和分母才用占比图，大量明细用表格；重要异常及证据可优先。没有数据就保留文字，不补虚构指标/图表。无需凑四卡，也不要求所有报告有相同章节顺序。简洁策略通过原生成提示词约束内容长度，密度由独立设置控制；它不会在恢复时删去风险、限制和来源。

前端只排版已有内容，不计算新的业务数据，也不生成额外图表。未被模型布局放置的已有证据会保留，限制和来源不能通过省略布局块被静默隐藏。非法/缺失的新布局或缺少逐项绑定时保留原分析并回退兼容布局，标记排版未通过，不把它伪装成已验证的智能布局。无新增排版模型调用或重新查询/重跑Python的修复编排。

## 渲染、安全、历史和导出

新增通用 `ReportRenderer.vue`，原 `PythonVisualReport.vue` 作为兼容入口，共用一份渲染实现；原Chat/UiArtifact挂载及导出入口继续工作。保留原PNG/SVG、Plotly离线沙箱、图片懒加载及出错降级。支持12列响应式网格、整行/半行混排、重点块、长报告章节导航、来源与数据依据查看、加载/部分失败状态。

章节导航只滚动当前报告，不修改SPA路由。原章节顺序和旧Markdown报告继续兼容。模型Markdown仍使用html=false的安全渲染；Markdown图片不能绕过归档授权去加载外部URL，图像只能通过原图表产物接口读取。模型不能提供可执行脚本或动态前端组件。

图表仍来源于同一次已停止且hash匹配的执行清单，使用原归档fileId/sha256/size。dataRefs必须属于已授权、已引用的报告来源；执行记录继续绑定代码、结果数据集和产物哈希。该校验能证明来源、执行身份、结构和产物一致性，不声称所有模型业务解释都经过独立语义认证。

没有数据库结构迁移，也没有重写旧报告。原Markdown、analytical_report.v1以及已有HTML兼容路径继续保留。PDF仍使用原导出器，浏览器已实际下载、解析并渲染导出PDF；报告来源可随原导出逻辑展开，技术执行信息不被展示为正文。

## 受控样本验收

用户确认本阶段先使用受控样本。样本和计算由 `scripts/prepare-report-layout-fixtures.py` 生成，使用Python标准库Decimal，不联网、不安装依赖。布局是验收夹具中的结构描述；这些测试证明协议和渲染能力，不冒充真实模型自行选择六种布局的生产端到端证据。

| 场景 | 真实执行的样本计算/边界 | 受控布局 |
| --- | --- | --- |
| 股票行情 | 10.00→10.50，两个相邻区间首尾变化5.00% | 结论→趋势图→摘要→原始表→一个指标 |
| 交易偏好 | 20/30/50笔，共100笔，基金占50.00% | 真实分母的占比图→分析→明细→摘要→一个指标 |
| 资产及盈亏 | 两资产盈亏-12.50和8.75，净盈亏-3.75 | 异常/净额→解释→对比图→明细→摘要 |
| 企业财务 | 大额Decimal季度差额7.01，保留9007199254741000.02 | 精确表格→解释→指标→摘要，不画丢失小数的浮点图 |
| 市场资讯 | 只有定性样本文本，无行情/财务观测 | 证据与边界→摘要；无虚构指标和图表 |
| 数据不足 | 没有基础记录 | 范围说明和补充要求；零指标、零图表、零伪造明细 |

生成脚本执行13项计算/边界检查。前端测试将图表和表格与同一份样本数据引用核对，并核对实际Plotly沙箱内的图表数值。六类报告的桌面和390px移动端截图、序列化恢复顺序、后续偏好不改变归档布局、设置持久化、移动设置面板以及章节导航均通过。

## 自动测试与限制

| 检查 | 结果 |
| --- | --- |
| Java协议、真实JSON Schema、原报告接纳、Cline上下文与工具参数回归 | 116项通过 |
| 报告Vue/布局/兼容/配置测试 | 38项通过 |
| 原Cline Node/SDK回归 | 103项通过；模型/执行网关为测试适配器 |
| 六类布局真实浏览器 | 通过；真实Vue/Plotly，受控数据和API产物夹具 |
| 原PNG/SVG/Plotly及PDF浏览器回归 | 通过；真实原Python产物，API夹具 |
| Vue结构检查及生产前端构建 | 通过；没有新增依赖 |
| 更广UI artifact回归 | 338通过、1项既有MCP按钮源字符串样式断言失败；不是本次报告功能失败，也不能称全套回归全绿 |

新SDK用例确认排版配置进入原SDK任务提示，在原2次模型轮次和1次Python执行中完成，不增加排版调用或数据查询。这里是实际SDK配合测试网关，不是一次生产模型推理。浏览器耗时包含图表加载和截图，不能拿它代替真实模型生成延迟；本阶段未声称生产推理耗时已完成对照实验。

证据目录：[adaptive-report-layout-20261028-evidence](adaptive-report-layout-20261028-evidence/)。六类报告均为明确标注的样本，不含客户凭据、账号或生产数据。完整生产模型/Cline端到端、客户真实数据验收和发布验证尚未执行。

## 文件范围与运行方式

主要文件：

- 协议：`ReportPresentation.java`、`ReportLayout.java`、`VisualReportMode.java`、`CodingRuntimeContext.java`、`report-layout.schema.json`及三份Coding Schema。
- 生成/接纳：`AgentChatModeHandler`、`ToolDirectModeHandler`、`CodingAnalysisService`、`ClineContextAnswerService`、`CodingEvidenceCollector`、`VisualReportArtifacts`。
- 前端：`PromptComposer`、`ChatAssistantView`请求/作用域传递、`ReportRenderer.vue`、兼容入口、`PythonVisualReport.js`、`reportLayout.js`、`reportNarrative.js`及原两份样式。
- 验收：新增协议/渲染测试、原测试扩展、六类样本JSON/生成脚本、`test-report-layout.mjs`及验收页面；npm原报告测试入口包含新用例。

```bash
python scripts/prepare-report-layout-fixtures.py
# chatchat-api/web-app 下：
npm run test:visual-report
npm run test:report-layout:browser
npm run test:visual-report:browser
# coding-agent-node 下：
npm test
```

浏览器脚本使用现场可用的Edge，或通过PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH指定预装Chromium。没有临时下载安装浏览器、包或字体。Java测试使用现有Maven缓存和真实JSON Schema验证器。工作区前序任务改动继续保留，没有为本次改造回退它们。

正式发布应一起更新API与Web，避免只上前端配置而旧后端未读取/归档新协议。本次未覆盖正在运行的生产版本。
