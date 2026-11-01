# SQL Studio 查询测试参数交互整改

分支：`fix/sql-template-test-parameters`。部署源码提交：`31e0c012b`。

## 用户操作

发布窗口的“查询测试”标题右侧增加“测试查询”文字按钮。点击后弹出“填写测试参数”，按当前模板参数定义生成输入项；保留默认值，数字、布尔、枚举、日期分别呈现，支持必填参数、可空参数、明确不传入的可选参数以及本地 Schema 引用。复杂对象和数组使用独立 JSON 输入框。

普通发布窗口不再常驻展示测试参数 JSON。参数确认后点击“执行测试”，查询结果仍显示在原发布窗口。“取消”、关闭和打开参数窗口都不执行 SQL；无参数查询也需要明确点击执行。弹窗沿用 SQL Studio 的系统对话框、按钮、输入框和颜色变量，支持键盘关闭、焦点约束和返回触发按钮。

已发布模板同样提供测试入口。服务端复用现有只读查询、安全校验、当前版本检查、来源版本检查、数据源权限和审计；只返回本次测试回执，不保存到已发布定义，不改变线上版本、启停状态、SQL、参数契约或原发布证据。改动已保存 SQL 或未同步草稿时，需先创建/保存对应修订。

## 回归验证

[regression-tests.json](regression-tests.json)：SQL Studio 前端 177 项（22 个测试文件）通过，其中发布窗口 20 项；后端真实 H2 JDBC 21 项通过。覆盖取消不执行、默认值 0、布尔 false、日期、NULL、可选参数省略、引用、无参数、错误保留输入，以及已发布测试不改变记录、旧版本和撤销权限被拒绝。前端结构检查及生产构建通过。

[package-verification.json](package-verification.json)：API 的 2161 个 Java 类逐字节一致；MCP 976 个类中仅 SQL 模板编写服务及其内部类变化；105 个前端文件与本次构建逐字节一致。

## 真实环境验证

已部署至 `192.168.195.221:18100`。以已发布的“仓库面积明细查询”和无参数的“仓库地区分布与面积汇总”复验，连接资产 248 TPCDS。真实页面测试通过，见 [live/verdict.json](live/verdict.json)。

- 打开与取消参数弹窗不执行查询；无参数模板也需明确点击执行。
- 弹窗提交 `min_area=294242`，返回仓库 1、3、4，共 3 行；改为 `min_area=0`，返回仓库 1、2、3、4，共 4 行。实际测试请求及服务端行数据见 [real-ui-query-receipts.json](live/real-ui-query-receipts.json)。
- [测试前发布记录](live/history-before.json)与[测试后发布记录](live/history-after.json)完全一致，线上版本、启停状态及原有发布证据均未改动。
- [文字按钮](live/published-query-test-button.png)、[参数弹窗](live/parameter-dialog.png)、[3 行结果](live/query-result-294242.png)、[4 行结果](live/query-result-0.png)均为线上真实页面截图。

部署回执见 [deployment.json](deployment.json)。API 镜像为 `runtime-os-api:sql-test-parameters-20261101-r1`，MCP 镜像为 `runtime-os-mcp:sql-test-parameters-20261101-r1`。未执行数据库结构迁移，工具注册表 HEALTHY，原会话内容、消息索引及无关容器检查通过。

范围：本次验收证明 SQL Studio 参数交互、真实查询和已发布模板不被测试修改；不将返回行数或页面成功提示当作完整业务数据校验通过。
