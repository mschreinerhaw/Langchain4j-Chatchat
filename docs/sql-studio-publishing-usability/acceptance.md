# SQL Studio 发布体验整改与实测

本次整改让用户在 SQL Studio 完成分类选择、发布及已发布模板的信息更新。MCP Server 仍是最终管理端，SQL Studio 复用其分类、模板和发布机制。

## 用户操作

- 分类下拉直接读取 MCP Server「业务分类维护」中启用的分类，展示中文名称，保存原分类编码。没有新建分类字典。
- 查询结果预览下的发布校验、SQL 正文和发布历史默认折叠，展开后使用灰色 12px 小字。
- 发布按钮固定在对话框底部。首次发布和已停用模板显示「发布模板」，已发布模板的信息发生修改时显示「发布更新」。
- 用户确认后，系统依次保存必要的草稿变更、进行真实只读测试并发布；任何测试失败都会停止发布。
- 已发布模板可修改名称、说明和业务分类。更新时服务端使用原 SQL、参数契约及数据源重新测试，成功后生成新的不可变发布版本；MCP 管理目录和 Runtime 工具描述同步更新，工具标识保持稳定。
- SQL、参数和数据源的修改仍通过已有的新修订流程处理。历史版本、并发版本校验、来源校验和原有权限边界均保留；信息更新同时要求编辑和发布权限。

历史分类仍可读取和执行，编辑保存时需选择维护列表中当前可用的分类。

## 源码与回归

实现提交：`a4f970166`、`699d7d1ba`、`6153b16f6`。

主要变更在 SQL Studio 发布面板、API 发布代理、MCP SQL authoring 服务及 MCP 关系列表分类展示。没有新增模板存储系统或独立发布引擎，没有加入业务统计结果。

回归结果见 [test-results.json](test-results.json)：Java 33 项、前端 25 项全部通过，Vue 结构检查通过。API 与 MCP 前端构建、后端打包通过。最终包与静态产物逐文件一致，见 [package-verification.json](package-verification.json) 和 [初版双服务打包核验](package-verification-r1.json)。最终样式修订没有改变 Java 实现，见 [2070 个类文件字节比较](final-java-bytecode-comparison.json)。

## 真实页面与查询证据

在远程系统使用真实浏览器、真实 API、真实 JDBC 查询完成验收，没有模拟网络响应。

使用资产 248 的 TPCDS 连接，单独建立仅允许 `warehouse` 表和本验收模板编码的数据源配置。执行 `SELECT COUNT(*) AS warehouse_count FROM warehouse`，真实返回仓库数量 5。原数据源的授权白名单及已有业务模板未被本次测试修改。隔离信息见 [fixture.json](fixture.json)。

1. 页面分类与 MCP 维护分类逐项一致；技术内容默认折叠且为灰色 12px。
2. 在页面首次点击发布，实际请求依次为 `test → publish`，发布 v1。
3. 在页面修改已发布模板名称、说明和分类，点击「发布更新」，生成 v2。SQL、参数、数据源和工具标识不变，历史发布保留旧名称。
4. 在 MCP「关系库查询」页面检索到新名称及「数据核验」分类，Runtime 工具描述同步更新。
5. 停用后重新打开 SQL Studio，页面显示「发布模板」，实际重新测试并恢复为 v3。

[完整实测结论](live-r1/verdict.json) · [实际页面请求及响应](live-r1/actual-ui-requests.json) · [真实查询及首次发布](live-r1/initial-publication.json) · [信息更新](live-r1/updated-publication.json) · [重新发布](live-r1/restored-publication.json) · [MCP 管理页截图](live-r1/mcp-synchronized.png) · [停用后的发布按钮截图](live-r1/studio-offline-publish-button.png)

最后一轮仅修复说明输入框宽度和字体；在最终部署上复查页面布局，见 [最终布局检查](ui-final/layout-verdict.json) 和 [最终页面截图](ui-final/studio-before-publish.png)。以上证据验证本次发布操作及真实只读 SQL，不代表额外的 Agent 数据分析场景验收。

## 部署与测试数据

[初版部署回执](deployment-r1.json) 保留完整双服务部署记录。[最终部署回执](deployment.json) 对应 API 样式修订，MCP 和 Coding Agent 不变；无数据库迁移。部署检查包含原有工具注册恢复、会话及消息保留、其他运行容器未变。

最终 API JAR SHA-256：`ba0bf9b0909d6546f5c8ca51c8d06fc453fcd21768d0075522065146518e703f`。

MCP JAR SHA-256：`8b335912be03980dc729762de55689295ccfcef2bbb99f80eca67de7c75d5323`。

验收完成后停用本次测试发布并禁用隔离数据源，保留工程及不可变历史供审计，见 [cleanup.json](cleanup.json)。业务模板不受此清理影响。

复验脚本：[test-sql-publishing-usability-live.mjs](../../chatchat-api/web-app/scripts/test-sql-publishing-usability-live.mjs)。通过环境变量 `E2E_ORIGIN`、`E2E_AUTH_FILE`、`E2E_MCP_ORIGIN`、`E2E_MCP_AUTH_FILE`、`E2E_FIXTURE`、`E2E_OUTPUT` 指定站点、外部认证文件、全新隔离草稿及输出目录。脚本会执行真实发布，应使用专用测试草稿；已归档输出不能覆盖。认证文件和数据源连接凭据不进入仓库。
