# 内置金融数据源编辑页查询修复

2026-11-02。关系库查询编辑页执行 `builtin_financial_market` 查询时报 `SQL datasource not found`。相同模板通过已保存模板的测试入口能正常返回 100 行。

原因是 `/api/v1/database-query/test` 在创建预览配置前先进入普通数据源资产解析，而应用自有的金融读库不属于 SQL 资产注册表。控制器现在优先识别已有的内置数据源 ID，复用已保存模板的 `invokePreview` 和受控 `FinancialMarketQueryExecutor`。草稿构造由内置查询和既有工作流查询共用，保留 SQL、参数、行数及超时。普通外部数据源的路径保持原有行为。

修改源码：`chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/database/admin/DatabaseQueryAdminController.java`，并增加控制器回归测试。

本地整模块 Maven 编译受已有依赖缓存版本不一致影响，存在与本次改动无关的缺失类型。因此使用服务器当前运行 JAR 的准确依赖编译修改的控制器，并执行控制器、查询调用服务及数据源集成测试：20 项通过，0 失败、0 错误。核对已有请求和响应嵌套类的二进制签名一致，仅替换主控制器类。

部署镜像 `runtime-os-mcp:builtin-financial-query-20261102-r1`，保留现有前端资源及其他后端类、依赖。基准 JAR SHA256 为 `35d3412c8c243e1ca5780484d57af81dfb45ef7bb6c1dbff52b2fd55df565e21`，部署 JAR SHA256 为 `ea7453068ef089e50ac1cca4b5b0632aac1d36caed9d90f3bdaf39025c0ef281`。服务器发布目录 `/opt/chatchat-releases/builtin-financial-query-20261102-r1` 保留旧 JAR、Compose 备份、补丁清单及部署记录。

线上验证：

- 在实际编辑弹窗执行中债曲线 SQL，成功返回 100 行，无浏览器脚本错误。
- 设置最大 3 行后返回 3 行，曲线名称参数筛选正确。
- 已保存内置模板测试成功，普通 MySQL 数据源 `SELECT 1` 成功。
- 内置查询对系统表的访问仍被拒绝，未创建伪造的 JDBC 数据源资产。

证据与截图：`target/builtin-financial-query-fix/live-verification.json`、`builtin-query-success.png`、`test-reports/TEST-junit-jupiter.xml`。线上复验脚本为 `scripts/verify-builtin-financial-query-live.mjs`。
