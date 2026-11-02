# MCP 工具定义可视化

2026-11-02，将“MCP 工具发布 → 工具定义”中的原始定义与输入/输出 JSON 展示替换为只读业务视图。

- 执行能力、使用角色和绑定模板数量以概览卡片展示。
- 通过“接收调用参数 → 按范围与权限匹配 → 返回匹配结果”说明工具调用方式，不新增编排或执行行为。
- 仅展示当前版本实际保存的模板范围，使用目录中的模板名称、说明、分类及参数要求。目录不可用、模板不在当前目录时保留原绑定卡片，并支持重试。
- 输入和返回结构改为字段表，展示类型、必填或返回规则、默认值、可选值及约束；0、false、null、嵌套字段与本地引用可正常显示。
- 已发布定义保持只读，修改草稿、创建版本、测试与发布沿用原流程。

修改入口 `chatchat-api/web-app/src/views/ToolPublicationsView.vue`，新增 `components/publications/PublicationDefinition.vue`、`PublicationSchemaFields.vue` 与对应独立脚本、样式和展示工具函数。目录异步读取使用单独状态和请求序号，避免快速切换工具或版本时显示过期结果。

验证：26 项相关单元测试、Vue 结构检查和生产构建通过。浏览器检查覆盖关系库、数据库运维、SSH、HTTP、API、Python 多类工具，模板名称与数量一致，1024px 布局无组件溢出，无浏览器脚本错误。对比前后版本定义、输入/输出结构、版本状态保持不变，页面未发出发布写请求。既有 SQL 模板发布测试按钮已回归，真实查询返回 4 行。

部署镜像 `runtime-os-api:publication-definition-20261102-r1`，服务器目录 `/opt/chatchat-releases/publication-definition-20261102-r1` 保留旧 JAR、Compose 配置及发布清单。仅更新 API 工作台静态资源，后端类与依赖保持原样；保留旧哈希静态资源供已打开的浏览器会话使用。

部署后截图与验证记录位于 `target/publication-visual-definition/deployed/`，复验脚本为 `scripts/verify-publication-definition-live.mjs`。工作台刷新后可查看新版界面。
