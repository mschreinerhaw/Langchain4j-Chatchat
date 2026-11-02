# 关系库查询轻量编辑页

2026-11-02，基于 `origin/fix/unified-query-template-management`，在工作分支 `fix/sql-query-simple-editor` 同步手工模板与 SQL Studio 来源的编辑页。

- 单个 SQL 输入区，左下角提供参数弹出面板和“执行查询”文字按钮。
- 基础信息与运行配置默认收起，复用既有结果预览。
- 手工模板保存为单条 SQL，编辑旧多步骤模板时明确提示替换行为。
- SQL Studio 继续使用源工程 SQL 和参数定义；在管理页填写测试值并执行查询，保存发布信息。保留参数类型、默认值、枚举、引用定义与 null 值，不重建源参数 schema。
- 列表中的测试入口继续使用原有参数对话框。

验证：前端生产构建、Vue 结构检查、`node scripts/test-simple-query-editor.mjs`、`scripts/verify-simple-query-editor.mjs` 浏览器检查通过。浏览器覆盖两种来源的布局、参数输入、SQL Studio 实际执行及保存。验收模板“仓库面积明细查询”传入 `min_area=294242` 返回 3 行；保存后源 SQL、参数定义、显示名称一致；浏览器无脚本错误。

部署地址：<http://192.168.195.221:18101/admin/index.html>。

仅替换 MCP 管理页静态资源，复用服务器既有 JAR 中的后端和依赖。新镜像 `runtime-os-mcp:simple-query-editor-20261102-r1`，大小 601582559 字节，JAR SHA256 为 `4316d512faa14cdef6584b2598015ed32af9b90e99f2a33f59a1d3b6f8895b6c`。

服务器发布目录 `/opt/chatchat-releases/simple-query-editor-20261102-r1` 保留前端包、旧 JAR、Compose 配置备份和部署记录。旧镜像为 `runtime-os-mcp:unified-query-management-20261101-r2-compact-20261102`。可恢复 `compose-before.json` 后，在现有目录使用 Compose 项目名 `runtime-os-stage-maintenance` 和 `config/deployment.env` 重新创建 `mcp` 服务回滚。

部署后浏览器证据位于 `target/simple-query-editor-deployed/`，含截图与 `verification.json`。

## r2：配置顺序与 SQL 语言编辑

基础信息、运行策略置于 SQL 查询区域上方，继续默认收起。SQL 输入区使用与 SQL Studio 相同版本的 Monaco（0.52.2），按需加载，提供 SQL 语法高亮、行号、括号配对、缩进、关键字/函数/已配置参数补全及 SQL 格式化。参数面板和执行查询按钮仍在编辑器左下角。

快捷键：`Ctrl+Space` 补全，`Shift+Alt+F` 格式化，`Ctrl+Enter` 执行查询。SQL Studio 来源继续只读展示源 SQL。

新版镜像 `runtime-os-mcp:simple-query-editor-20261102-r2`，发布目录 `/opt/chatchat-releases/simple-query-editor-20261102-r2` 保留 r1 镜像对应的 JAR 和 Compose 备份。部署后浏览器检查结果与截图位于 `target/sql-language-editor-deployed/`。

## r3：移除完整 JSON

查询结果不再展示“完整 JSON”折叠入口，预览说明同步改为“查看查询语句、返回数据表和错误信息。”。发布镜像 `runtime-os-mcp:simple-query-editor-20261102-r3`，备份位于 `/opt/chatchat-releases/simple-query-editor-20261102-r3`。
