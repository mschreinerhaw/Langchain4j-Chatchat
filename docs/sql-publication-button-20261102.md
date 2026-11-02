# SQL 模板发布测试按钮样式

2026-11-02 修复 SQL Studio“填写测试参数”弹窗中“执行测试”按钮悬停、按下后变浅色但文字仍为白色的问题。

原因是 `.sql-dialog button:hover:not(:disabled)` 通用样式覆盖了主按钮背景；异步加载的组件禁用样式还会覆盖按钮透明度。组件主按钮现在使用更明确的选择器，并定义悬停、按下、键盘焦点及禁用状态。

源文件：`chatchat-api/web-app/src/styles/components/sql-studio/SqlTemplatePublicationPanel.css`。

部署保留现有 API 后端与前端包，仅在现有 HTML 中引入按内容哈希命名的修复样式；后续完整前端构建会包含组件中的同样修复。当前镜像为 `runtime-os-api:sql-publication-button-20261102-r2`，备份及部署记录位于 `/opt/chatchat-releases/sql-publication-button-20261102-r2`。原始未修复镜像 `runtime-os-api:sql-test-parameters-20261101-r1` 继续保留。

使用工作台管理账号在实际 SQL Studio 中打开既有“仓库面积明细查询”模板，填写 `min_area=0` 并点击“执行测试”。实际浏览器验证悬停、按下、键盘焦点、查询中禁用态的背景和文字颜色通过；真实 SQL 返回 4 行，无浏览器脚本错误，仅调用测试操作，未发布模板。为了检查加载样式，浏览器短暂延迟实际测试请求的发送后继续请求，未模拟查询响应。

证据位于 `target/sql-publication-button-fix/live-verification.json`、`live-query-loading.png`、`live-query-success.png`。复验脚本为 `scripts/verify-publication-button-live.mjs`，凭据仅从环境变量读取。
