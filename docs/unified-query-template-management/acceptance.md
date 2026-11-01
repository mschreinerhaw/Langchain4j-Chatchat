# SQL Studio 与手工查询模板统一管理

分支：`fix/unified-query-template-management`。最终部署源码：`c1c080fc6`。

## 页面与来源

MCP Server → 查询能力模板 → 关系库查询。SQL Studio 与手工模板共用原有 `CrudCatalog` 编辑表单、`ModalPanel` 详情与 `ApiTestParameterDialog` 参数弹窗；没有新增另一套模板存储或执行引擎。

- 两种来源都在同一列表查找，使用同一个“编辑关系库查询”窗口、公共字段、窗口布局和底部操作。
- SQL Studio 已上线模板新增“编辑、测试、详情”，名称、描述、业务分类可以在这里修改并同步到原发布记录。保存前按模板参数执行验证，原作者当前的数据源、模板权限仍须通过。
- 模板来源作为字段展示。手工来源保留原来的查询流程编排；Studio 来源显示关联源文件的 SQL 和参数定义，只读呈现，修改 SQL 或参数契约仍从源工程创建修订，避免两端内容分叉。
- 列表测试和编辑窗口测试均复用同一个参数弹窗。支持默认值、数字、布尔、NULL、本地 Schema 引用；0 和 false 不会当成空值丢失。结果显示在同一预览区域。
- 历史下线 Studio 修订可测试、查看，不能通过普通保存隐式重新发布。原有手工模板的启停、删除与批量操作保留；Studio 修订不会误入手工删除接口。
- 原有详情页的技术记录仍默认折叠，查询列表继续显示业务列。

## 权限和生命周期

管理端对 Studio 的写入、测试是显式的平台管理员操作：服务端先检查查询管理菜单及超级管理员身份，再从已保存修订取得租户、发布者与源版本，不接受客户端指定身份、SQL、参数契约或数据源。普通菜单权限不授予原作者代理能力；非管理员继续使用原有详情访问范围。

操作复用 `SqlTemplateAuthoringService.test/updateMetadata` 及其并发版本、当前发布头、只读 SQL、数据源白名单、发布者资源权限、发布器和审计。管理员操作日志另外记录发起的管理账号与模板编号。未放宽 Agent、数据库或 MCP 权限，也未把 Studio SQL 复制成手工数据库查询记录。

## 验证结果

[regression-tests.json](regression-tests.json)：前端 6 项、后端 31 项，合计 37 项针对性测试通过。后端包含实际 H2 JDBC 测试；覆盖普通菜单用户不可委托原作者、过期版本、已撤销数据源权限、已下线模板不得隐式发布，以及共享表单不通过隐藏的手工字段重写 Studio 契约。

[真实页面回执](live/verdict.json) 验证通过：

1. 手工和 Studio 来源都打开同一个编辑标题及布局，公共字段一致，截图见 [手工表单](live/manual-shared-form.png)、[Studio 表单](live/studio-shared-form.png)。
2. 两种来源的编辑窗口都使用同一个参数组件，截图见 [手工参数](live/manual-shared-parameters.png)、[Studio 参数](live/studio-shared-parameters.png)。手工测试取消后未改动已有业务配置。
3. Studio“仓库面积明细查询”提交 `min_area=294242`，实际连接资产 248 TPCDS，返回 3 行；见 [请求及真实行数据](live/studio-real-query.json)、[统一预览结果](live/studio-shared-query-result.png)。
4. 在页面修改本会话创建的验收模板“仓库地区分布与面积汇总”的描述，SQL Studio 历史接口同步返回新描述，再通过同一页面恢复原描述；见 [修改回执](live/metadata-changed.json)、[同步读取](live/runtime-history-during-update.json)、[恢复回执](live/metadata-restored.json)。
5. SQL、参数定义、数据源、名称与分类保持原值。恢复后的绑定执行修订与修改前完全相同，见 [修改前](live/bound-revisions-before.json)、[恢复后](live/bound-revisions-after.json)。两次正常发布的历史记录保留，没有篡改或删除版本。

本次真实验证覆盖统一管理与原发布链路复用，不据此声称所有手工数据源或全部 Agent 业务场景重新验收通过。手工来源的现场检查为表单、参数弹窗和取消操作，真实数据库查询使用本会话的 Studio 验收模板。

[原有详情页浏览器回归](details-regression/verdict.json)也通过：两种来源均默认折叠技术记录，使用相同的灰色 12px 技术文字，小窗口布局可用，未出现浏览器异常。

## 部署

已部署至 `192.168.195.221:18101`。镜像 `runtime-os-mcp:unified-query-management-20261101-r2`，摘要 `64b8f27fcdc313065ea87b8a76f6f710b06da1a59117234756be6bcaa3602f87`。

[打包校验](package-verification.json)及[部署回执](deployment.json)证明本次最终包只改变 Studio 目录管理控制器及其内部类，6 个管理端前端文件与构建一致。部署前后 8 项手工模板、7 项 Studio 发布记录目录摘要相同；没有数据库结构迁移，没有替换 Runtime OS API、Coding Worker 等其他运行容器。r1 到 r2 的两次部署均有备份和回滚容器。
