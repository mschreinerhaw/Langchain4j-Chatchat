# 动态报告显示回归测试

动态报告修改不得只验证“表格存在”。每次修改报告外置、Markdown/HTML 渲染、证据链或图形分析时，至少运行：

```powershell
cd chatchat-api/web-app
npm.cmd run verify:ui-artifact
```

视觉回归使用本机 Chrome/Edge；CI 可通过 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 指定 Chromium。

## 固定测试矩阵

| 场景 | 必须满足 |
|---|---|
| Markdown 单表 | 表格、行列统计和“图形分析”同时存在 |
| 表格紧跟正文 | 即使缺少空行，也必须自动形成独立 Markdown 表格块 |
| 历史表格缺少分隔行 | 连续且列数一致的管道行自动补齐 Markdown 表头分隔行；不修改历史原文 |
| 结构化答案块中的破损表格 | `uiRenderContract.answerBlocks` 与普通回答共用表格规范化入口，列数不一致时仍渲染为真实表格 |
| Markdown 宽表 | 表头不逐字折行；窄屏可横向滚动；分析按钮留在可视区域 |
| 证券/账号字段 | `000155`、`070200046604` 等前导零不丢失 |
| 数值字段 | 千分位、正负数正确转换为图表数值 |
| Markdown 多表 | 每表均有分析入口，并提供“对比数据集” |
| 旧 Artifact 原生 HTML | `<table>` 自动获得与 Markdown 相同的增强能力 |
| 旧 Artifact 管道文本 | `|---|` 文本先修复为表格，再提供图形分析 |
| 非表格/破损表格 | 不误生成空图表或虚假按钮 |
| 代码块中的管道文本 | 保持原样，不得误转换为表格 |
| HTML 安全边界 | script、事件属性、javascript URL 被清除 |
| 证据链 | 默认折叠，灰色小字号，不抢占正文视觉层级 |
| 前后端资源选择 | 同时存在 answer/reportHtml 时优先 Markdown answer |

## 内容协议

运行时展示必须明确区分内容类型，禁止把所有结果都作为一段 HTML 字符串处理：

- `Markdown`：普通回答和中小型文字报告；渲染前执行表格块规范化。
- `Table` / `Visualization`：结构化 `columns + rows`，用于查询结果、大表格和图表，不由 LLM 拼接管道文本。
- `Html`：仅用于明确声明的旧版或动态 HTML 资源，并经过清洗与兼容修复。
- `EvidenceList`：证据链辅助信息，默认折叠，不混入报告正文。

大数据结果应保存为独立资源并分页/按需加载，Markdown 表格只作为中小结果兼容格式。

## 自动化层级

- `resultTableEnhancer.test.js`：数据与 DOM 契约，包括前导零、旧 HTML、多表和安全清洗。
- `artifactPresentationStyles.test.js`：宽表、证据链和事件桥接的样式契约。
- `test-ui-artifact-visual.mjs`：真实 Chromium 窄屏布局检查，并输出
  `test-results/ui-artifact-regression.png` 供人工复核。
- `UiArtifactServiceTest`：后端资源优先级、manifest 与生命周期测试。

视觉脚本必须检查按钮的实际坐标位于表格可视区域内；仅检查 DOM 中存在按钮不足以防止宽表把按钮推到屏幕外。

## 动态涨跌语义配置

趋势指标关键词以及上涨、下跌、中性色写入 `ui_trend_semantic_config`。当前登录租户没有独立配置时，继承数据库中的全局默认项；前端通过 `GET /api/v1/ui-display/trend-semantics` 加载最终配置。

租户配置使用一次性整体更新，避免关键词和颜色只更新一半：

```http
PUT /api/v1/ui-display/trend-semantics
Content-Type: application/json

{
  "keywords": ["涨跌", "盈亏", "收益", "同比", "环比", "change", "profit"],
  "upColor": "#e5484d",
  "downColor": "#16a36a",
  "neutralColor": "#98a2b3"
}
```

`DELETE /api/v1/ui-display/trend-semantics` 删除租户覆盖并恢复全局继承。关键词匹配不区分大小写；空关键词列表和非法颜色会被拒绝。配置接口暂时不可用时，浏览器继续使用内置安全默认值。
