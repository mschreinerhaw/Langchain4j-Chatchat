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
| Markdown 宽表 | 表头不逐字折行；窄屏可横向滚动；分析按钮留在可视区域 |
| 证券/账号字段 | `000155`、`070200046604` 等前导零不丢失 |
| 数值字段 | 千分位、正负数正确转换为图表数值 |
| Markdown 多表 | 每表均有分析入口，并提供“对比数据集” |
| 旧 Artifact 原生 HTML | `<table>` 自动获得与 Markdown 相同的增强能力 |
| 旧 Artifact 管道文本 | `|---|` 文本先修复为表格，再提供图形分析 |
| 非表格/破损表格 | 不误生成空图表或虚假按钮 |
| HTML 安全边界 | script、事件属性、javascript URL 被清除 |
| 证据链 | 默认折叠，灰色小字号，不抢占正文视觉层级 |
| 前后端资源选择 | 同时存在 answer/reportHtml 时优先 Markdown answer |

## 自动化层级

- `resultTableEnhancer.test.js`：数据与 DOM 契约，包括前导零、旧 HTML、多表和安全清洗。
- `artifactPresentationStyles.test.js`：宽表、证据链和事件桥接的样式契约。
- `test-ui-artifact-visual.mjs`：真实 Chromium 窄屏布局检查，并输出
  `test-results/ui-artifact-regression.png` 供人工复核。
- `UiArtifactServiceTest`：后端资源优先级、manifest 与生命周期测试。

视觉脚本必须检查按钮的实际坐标位于表格可视区域内；仅检查 DOM 中存在按钮不足以防止宽表把按钮推到屏幕外。
