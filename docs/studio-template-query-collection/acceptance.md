# SQL Studio 查询集合全链路验收

本记录区分页面可见、模板发现、真实执行和分析交付。工具列表出现模板或发布接口成功，均不单独作为业务闭环通过依据。

## 可复现入口

- SQL Studio 工程：`SQL Studio 查询集合全链路验收 20261101`，工程编号 `c4f87abd-8d26-4b2d-a4fd-bb09212b9987`。
- 已发布 SQL：`仓库地区分布与面积汇总`、`仓库面积明细查询`，分类为 MCP 业务分类维护中的“数据资产探索”。
- MCP 工具发布：执行能力选择“关系库查询父模板”，搜索“仓库”，可选中上述两个 SQL Studio 模板。
- 已发布查询集合：`仓库分布与面积分析查询集合`，英文标识 `studio_warehouse_20261101_template_query`。
- 验收 Agent：`SQL Studio 查询集合分析验收`，只绑定上述查询集合，未手动绑定单项 SQL 工具或执行网关，未编排业务步骤。

页面创建、勾选、校验、测试和发布的真实请求及截图保存在 [collection-ui](collection-ui/)。其中测试返回的是模板候选，不是仓库业务数据。

## 源码整改范围

1. SQL Studio 发布项保留原始 `sql_datasource:STUDIO_*` 资产标识，根据真实绑定数据源归入对应查询家族，关系库父模板据此包含兼容的已发布 SQL。未复制模板、转换标识或新建技能/查询执行引擎。
2. 角色范围与发布集合按真实资产类型分别求交集；执行时再次按“类型 + 模板编号”校验，避免不同类型同名模板扩大集合。
3. 查询家族属于发现元数据，不改变已有 SQL 发布执行修订的哈希，兼容升级前发布项。
4. SQL 执行器声明与关系库发现父模板的关联，复用既有同服务执行器继承机制；数据库授权仍独立检查，普通用户无需选择内部执行器。
5. gRPC 远端刷新后同步更新 Agent 本地工具注册表，避免管理端和实际执行进程看到不同版本的元数据。
6. 批量执行复用既有确认策略：执行前确认完整批次，参数变化使确认失效；批次确认不跳过子调用的权限、输入校验或拒绝规则。
7. 分析图在工具返回待确认时暂停，不把它投影成数据失败，也不提前启动 Python 或生成报告；复用既有任务确认、持久化与恢复机制。
8. 确认恢复不重新补参或改写已确认输入。Runtime 生成的模板批次通过当前授权发现重新编排，重新建立模板绑定证明，不从 UI 持久化命令恢复可信标记。
9. 在确认和调用前，将 Agent 配置中的数据库绑定落实为 SQL 执行器的逻辑资产路由；不依赖模型猜测，不影响 SSH 等其他协议。执行失败会清除前次确认标记，避免继续显示无工具的确认弹窗。

生产代码没有写入仓库字段、商品编号、统计结果、验收 SQL 或业务分析步骤。具体 SQL 由真实 Cline 工作区任务生成，业务问题、参数与独立期望值仅用于验收配置。

## 数据边界及独立核对

实际连接资产 248 的 TPCDS 数据库，使用独立验收数据源配置，仅允许 `warehouse` / `tpcds.warehouse` 和两项发布 SQL。原资产 248 配置不变。允许限定名称是同一物理表的规范写法，不放开其他表。

独立只读 JDBC 原始结果见 [independent-jdbc.json](independent-jdbc.json)，期望值见 [independent-expectations.json](independent-expectations.json)。这些结果没有作为数据输入传给 Agent。

| 核对对象 | 独立结果 |
| --- | --- |
| 全部仓库 | 5 条 |
| 面积非空 | 4 条 |
| 面积为空 | 1 条 |
| 面积原值合计 | 2,031,767 |
| 最小 / 最大非空面积 | 138,504 / 977,787 |
| `min_area=294242` | 仓库 1、3、4；3 条，合计 1,893,263 |

全仓库计数 5 与面积非负明细计数 4 的差异来自空值筛选口径，不能把缺失面积填零，也不能据字段名推定单位并进行换算。

## 已保留的失败证据

- 早期 Cline SQL 生成任务因对象存储达到最小剩余空间阈值，文件停留在 STAGED。未篡改任务状态或直接插入 SQL；校验哈希后把历史上传 JAR 归档到另一文件系统，保留原路径符号链接和所有 Docker 挂载源。恢复后重新运行真实生成任务。见 [storage-recovery.json](storage-recovery.json)。
- [agent-first-discovery-only](agent-first-discovery-only/)：Agent 发现两项模板，但没有执行器；任务技术状态成功不代表业务通过。
- [agent-analysis-before-local-refresh](agent-analysis-before-local-refresh/)：执行端仍为旧注册快照，未查询业务数据。
- [agent-parameter-change-before-local-refresh](agent-parameter-change-before-local-refresh/)：同一注册快照缺陷下的参数变更尝试，未通过。
- [agent-analysis-before-batch-confirmation](agent-analysis-before-batch-confirmation/)：已经选择真实 SQL 模板，但批量执行把待确认误记为失败，未取得业务数据。
- [agent-analysis-before-graph-confirmation](agent-analysis-before-graph-confirmation/) 与 [agent-parameter-change-before-graph-confirmation](agent-parameter-change-before-graph-confirmation/)：批次已经返回待确认，但分析图错误进入了取数失败分支，未取得业务数据。
- [agent-analysis-before-confirmation-resume](agent-analysis-before-confirmation-resume/) 与 [agent-parameter-change-before-confirmation-resume](agent-parameter-change-before-confirmation-resume/)：出现真实确认弹窗，但恢复补参使确认失效，直接重放批次丢失 Runtime 绑定上下文；内核拒绝执行，未取得数据库业务记录。测试进入重复确认后，已通过正常任务控制接口拒绝这两个验收任务，保留失败记录。
- [agent-analysis-before-bound-routing](agent-analysis-before-bound-routing/) 与 [agent-parameter-change-before-bound-routing](agent-parameter-change-before-bound-routing/)：受控批次进入 MCP 执行入口，但仅携带 `env=DEV`，缺少已配置的绑定数据库名称，产生多数据源歧义；失败状态还保留了前次确认标记。没有取得数据库业务记录，测试任务已通过正常控制接口拒绝。

183 项针对性回归测试通过，明细见 [regression-tests.json](regression-tests.json)。确认等待缺陷有修复前失败、修复后通过的可复现测试，见 [confirmation-regression.json](confirmation-regression.json)。这些测试不替代真实业务、Python 和图表验收。

## 修复后的真实链路结果

已在 `192.168.195.221:18100` 完成 SQL Studio 生成、保存、真实试执行、发布、查询集合页面勾选与发布、Agent 发现并执行、Cline Python 分析、报告资源上传和页面图表渲染。两项 SQL、查询集合和验收 Agent 均保留，用户可以从上述入口继续使用。

独立复核命令：

```powershell
node scripts/sql-tpcds-acceptance/verify-studio-query-collection.mjs
```

复核结果见 [chain-reconciliation.json](chain-reconciliation.json)。验证器只读取已经归档的实际结果，不向 Agent 提供期望答案。保存文件、试执行证据和发布 SQL 的摘要一致；查询回执的执行方式为 `PREPARED_STATEMENT`，路由到连接资产 248 TPCDS 的隔离验收数据源。

| 用例 | 真实结果 | 证据 |
| --- | --- | --- |
| 地区汇总及 `min_area=0` 明细 | 汇总 5 条；明细为仓库 1、2、3、4 共 4 条；合计均为 2,031,767，极值均为 138,504 / 977,787 | [SQL 回执与 JDBC 对比](chain-reconciliation.json)、[实际报告](agent-analysis/visual-report.json) |
| 参数改为 `min_area=294242` | 仅返回仓库 1、3、4，共 3 条，合计 1,893,263；Agent 任务 SUCCEEDED，页面答案可见 | [实际答案](agent-parameter-change/agent-answer.md)、[页面回执](agent-parameter-change/ui-receipt.json) |
| Python 与报告文件 | 首次脚本字段访问失败，Cline 在同一上下文修复；最终脚本退出码 0、SUCCEEDED，执行器已停止，脚本和图资源实际下载字节与归档 SHA-256 一致 | [Python/资源验证](agent-analysis/python-and-artifact-validation.json)、[完整 Coding 回执](agent-analysis/coding-att-1-f2d6cb6d-dfc8-425f-a153-c7cb4932f2b4.json) |
| 系统页面图表 | 两张静态图、一张动态图均实际渲染，未出现图表呈现诊断错误 | [图表渲染回执](agent-analysis/chart-rendering.json)、[图 1](agent-analysis/rendered-chart-0.png)、[图 2](agent-analysis/rendered-chart-1.png)、[图 3](agent-analysis/rendered-chart-2.png) |

图文分析任务 `84f8f753-485b-455b-9524-d9a447ef4586` 经过一次正常页面确认后执行。参数变更任务为 `8d812fab-2c04-4479-b615-e79bae2d7ff1`。Agent 只绑定查询集合，没有在用户配置中额外选择内部执行网关。SQL 和 Python 中的仓库字段属于本次真实生成的分析产物；生产 Java 没有写入这些业务字段或结果。

**状态限制原样保留：**第一份报告的任务状态为 `PARTIAL`，报告 `analysisStatus=FAILED`，Python 回执 `validationStatus=INCONCLUSIVE`。其数量严格相等检查确实失败（5 与 4），单位、被筛除记录身份和跨地区可比性等仍有未决项。这不是 SQL 执行或图表渲染失败，也不能表述为“业务校验全部通过”。独立 JDBC 证实第五条记录面积为空，能够解释两个筛选口径的计数差异；Agent 当时没有取得该记录身份，因此报告保留了这个边界。未修改模型原始报告状态、补造数据或把缺失面积填零。

本次证据支持“248 TPCDS 仓库场景完整生产与消费链路、参数生效、真实 Python 和页面图表已贯通”。不据此推定全部数据库、角色和业务问题都已验收通过。

## 最终部署与保留范围

- 分支：`fix/studio-templates-query-collection`。API 部署源码为 `5a3ce95f9`；之后的验收脚本和证据提交不改变已部署程序。
- API：`runtime-os-api:studio-query-collection-20261101-r6`，镜像摘要 `268f7ddaaa2d74ecd5de07dba9f85d4b66c357a01f5ff1804717e9a7a66e4bca`，见 [API 部署回执](api-deployment-r6.json)。
- MCP：`runtime-os-mcp:studio-query-collection-20261101-r2`，镜像摘要 `03fd5d8e38e89b143398ce490a8e84933d3bcc6611a91ae1af53100b3a0b3b29`，见 [MCP 部署回执](deployment.json)。
- 未执行数据库结构迁移；部署检查确认原会话内容、消息索引和无关容器保持不变。API 前端 105 个文件与原部署逐字节一致，见 [打包验证](api-package-verification-r6.json)。
- 第二次空间恢复只归档本专项历史发布中的已完成数据库备份文件，逐项校验摘要并保留原路径符号链接，没有删除备份、业务对象或 Docker 挂载源，见 [归档回执](storage-recovery-r5.json)。

用户复验：进入“MCP 工具发布”，执行能力选择“关系库查询父模板”，搜索“仓库”；可勾选“仓库地区分布与面积汇总”和“仓库面积明细查询”。也可直接打开已发布的“仓库分布与面积分析查询集合”，或在 Agent 列表使用“SQL Studio 查询集合分析验收”，分别提交 [图文问题](agent-case.json) 和 [参数变更问题](agent-parameter-case.json)。需要自己的业务角色使用时，仍须按原有权限中心授权相应数据源、模板和工具；发布不自动扩大访问范围。
