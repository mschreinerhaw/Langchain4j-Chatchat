# SQL Studio / Python Studio 数据服务专项验收

本次使用资产 **248测试数据库**（`f0421bd7-1be8-4d5c-8f65-b216d4f997cb`）的真实 MySQL `tpcds` 数据。范围为商品主键 1、1999 年门店销售；无参数年度服务同时返回 1998–2003 六年汇总。金额单位和币种未声明，不能当作人民币或客户投资数据。

## 本轮最终验收：真实链路通过

Agent 任务 `487d57af-b11a-385c-8274-b1fc3d7f4d9d` 最终状态 **SUCCEEDED**；运行 `att-1-7980ab3e-a927-4b7f-9911-e7b11328a2ab`。见 [任务与最终状态](agent-result.json)、[原始实际工具观测](agent-tool-observations.json)、[Agent 最终回答](agent-final-answer.md)。回答的 Markdown 文件仅展开了模型输出中的转义换行；原始回答完整保存在任务 JSON。

同一运行实际完成两个 SQL 查询、Python 模板发现、发布版 Python 模板执行。Python 实际收到年度 6 行、月度 11 行，输入来源引用见 [实际绑定](actual-recordset-reference-bindings.json)。没有以临时代码分析替代发布模板执行。

MCP Python 执行 ID 为 `161a2dea-d99b-4b28-9ba7-8a2982530fef`，**SUCCEEDED / exitCode=0**，耗时 2037 ms。[执行数据库回执](published-python-durable-execution.json) 与 MCP 响应的 stdout SHA256 完全相同，并确认 Sandbox 容器已移除、执行停止。[发布快照前后比对](published-template-immutability.json) 确认同一个 1.0.0 模板的源、输入/输出 Schema 和资产未改变。

[独立精确核验](exact-comparison.json) 为 **EXACT_MATCH**：119 个 SQL 字段、9 项指标、33 个月度数值、22 个图表数值全部一致。核验同时要求实际执行器、正确发布模板 ID、成功执行回执以及 SQL→Python 完整数组严格相等；发现目录、HTTP 成功、程序退出码单项均不足以通过。

[真实计划截图](agent-service-plan-real.png) 来自正式页面。[月度趋势图](published-python-monthly-trend.png) 是实际 Python 返回 figure 的独立静态导出，包含执行回执；不是伪造的应用页面。导出仅适配 Plotly 3 的标题对象和刻度显示，图表数据保持原值，三月保留断点。

后置 Cline 分析任务 `analysis-coding-36596a82aa7791cbd67c5bde46b6a86d7af5a42ce9fabec91955a7767ef26090` 也已 COMPLETED；实际按需加载 Runtime OS 自有 `builtin:python-data-analysis` 1.3.0，见 [NODE_CLINE 技能读取事件](post-analysis-skill-load-events.json)。第一次虽 exit=0 但缺少输出契约文件而被拒绝，Cline 根据反馈修复，后续执行进入 VALIDATED 并返回结果，见 [真实执行与校验](post-analysis-executions.json)。

**通过范围是完整技术链路及独立数值核对，业务语义不作全面认证。** 后置报告的 validationStatus 仍为 INCONCLUSIVE、businessValidationPassed=false，原样保留；自动报告还存在字段溯源空引用，金额币种/单位未声明。三月无观测不能补造为零，不同数量筛选口径导致的差额不能认定为数据库错误。独立 NULL 子集结论属于额外验收查询，没有事后伪装成模型已取得的明细证据。

## 已完成的生产入口

SQL Studio 的模型生成、参数审阅、真实试执行、发布、Agent 调用、更新、停用与恢复证据沿用同分支包含的 [SQL 专项验收](../sql-tpcds-e2e/03-acceptance-report.md)。本轮重新独立执行三条只读 JDBC 查询，见 [fresh-jdbc.json](fresh-jdbc.json)，不使用旧截图代替本轮数据核验。

Python Studio 通过正常 Cline 生成和审查任务交付程序，再保存、真实 Sandbox 试运行、发布并同步 MCP。原始交付见 [produced-tpcds-reconcile.py](produced-tpcds-reconcile.py)，来源及两个成功生成任务见 [generation-proof.json](generation-proof.json)。首次模型未交付文件的失败原样保留，不能作为代码交付成功。

脚本 `61cdfd73-267a-4227-993e-0941a86344f3`；发布模板 `495b9f07-1df1-4b34-b121-e871a3ede1fe`；源 SHA256 为 `290bdcfeea0488973bd2b7997253f9cfc6c797551de9e00ab031799e4954274b`。脚本从 `CHATCHAT_INPUT_JSON` 接收实际年度和月度数组，使用 Decimal 对账，返回指标、月度表和动态图数据，不联网或内置数据行。

[试运行证据](studio-source-evidence.json) 将成功回执绑定到脚本版本、原始源、输入 Schema、环境快照。修改源/Schema/环境、旧成功标记缺少证据或失败后复用旧成功，均不能发布。执行后再次核对环境，防止试运行期间环境漂移。正式模板仍使用既有治理、授权和同步管线。

## 运行时修复

1. Agent 仅选择已授权的固定模板发现能力。Runtime 根据发布契约的父工具、协议、同一服务及独立执行器权限补齐执行器，不要求人工绑定隐藏工具，也不授予整个服务。
2. 增加显式 `PARAMETER_EXECUTION` 发布参数策略。Python 协议由数据库策略声明；SQL、HTTP、Shell 策略保持独立。没有策略仍拒绝，只有兼容发现依赖才能延后编译必填参数。禁止把源代码、脚本或执行目标夹带为参数。
3. Python 工作流识别发布的 `python_runtime` 环境类型。Runtime 自有代码适配器兼容精确的 LF/CRLF 资源版本，保留冻结适配器比对和实际源哈希校验。

发布前部署 PostgreSQL 的 [增量策略迁移](../../scripts/sql/python-template-binding-policy.sql)，保留其他协议所有字段；MySQL 对应迁移位于 `database/migration/mysql/V20261101_01__python_template_binding_policy.sql`。试运行证据列迁移为 `scripts/sql/python-studio-test-evidence.sql`。本轮未验证 H2 的策略数据升级。

## 独立数值基准与负向场景

| 指标 | 年度服务 | 月度服务 | 年度减月度 |
| --- | ---: | ---: | ---: |
| 净收款 | 136947.06 | 136714.55 | 232.51 |
| 利润 | -65502.46 | -63358.97 | -2143.49 |
| 数量 | 3362 | 3362 | 0 |

年度含 66 笔销售，月度最低数量 0 的筛选含 65 笔。独立 NULL 子集查询确认 1 笔数量 NULL，解释差额。三月无观测，图表保留 null，不补零或插值；不能将不同筛选口径差额认定为数据库错误。

[真实负向矩阵](negative-matrix.json) 包含 Schema 修改后的发布拒绝、缺少年度、空月度、重复月键、NaN、月份 13、失败后旧成功证据清除；五个程序负向输入均实际在 Sandbox 非零失败，随后正常试运行恢复成功。

前期 Agent 虽返回 SUCCEEDED，但只有 SQL 查询和 Python 模板发现，未执行发布模板，明确 [不接受为通过](pre-fix-boundary.json)。执行器补齐后又被缺失发布参数策略拦截，实际计划校验记录见 [pre-policy-validation-failures.json](pre-policy-validation-failures.json)。这些失败不能因发现目录有工具而改写为成功。

## Skills 独立资产

沿用已筛选、固定提交、MIT 许可证的四个微软原生技能及 25 个原始文件，来源、哈希和范围见 [Microsoft 资产记录](../sql-tpcds-e2e/01-microsoft-skill-assets.md)。原始 SKILL.md 和子目录不转换为 Runtime OS 格式；Java 交付元数据，Cline 自主按需读正文和资源。现有真实加载事件分别记录 Microsoft MCP Builder 与 Runtime OS 自有 Python 数据分析技能。

通用 Microsoft Pandas/NumPy、可视化的直接适用官方资产仍标为待补充，不冒称官方发布。本测试没有 Azure 授权，不宣称 ADX/Fabric/ML/Cosmos 实际运行。Skills 不与 SQL/Python 模板发布绑定，不授予工具或数据权限。本次未改 Java 需求分析能力或原有领域技能编译流程。

## 复现与证据

使用 [验收脚本说明](../../scripts/studio-python-acceptance/README.md) 在新的输出目录运行。最终核验脚本必须检查真实 SQL 完整记录、实际 Python 调用参数、发布模板执行回执和独立 JDBC 数值；仅发现、临时 Cline 计算或 HTTP 200 都不能通过。

页面实际截图：[Python Studio 源文件](python-studio-source-real.png)、[已发布模板配置](python-studio-publication-real.png)。

## Agent 依赖约束

当前 Runtime 的模板发现包含候选选择与必填参数审核；需要上游结果数组的模板，发现/选择节点本身也需要等待数据查询。初次计划将两条 SQL 和 Python 发现并行，真实发现返回 `PARAMETERS_REQUIRED`。该轮已通过正常任务 API 取消，未接受随后的临时 Cline 分析作为模板服务验收；见 [真实时序失败](discovery-dependency-boundary.json)。

除依赖顺序之外，原参数选择器只收到问题与资产信息，尚未提供上游结果引用。本次增加通用的完整 recordset 引用：模型选择 `parameterBindings` 中的步骤与路径；Java 从本轮成功输出读取完整行，并通过已有执行桥接器核对 `tool_result` 来源证据。模型只看到列定义和两行预览，不负责复制或生成数据。截断、不完整、不可用步骤或无效路径均不能绑定。

通过正常 Agent 配置及发布接口增加依赖说明，见 [Agent 配置](agent-configuration.json)。仍由模型生成 DAG，Java 不固定年度/月度业务步骤，也没有人工提交执行计划、填造数据或手工绑定隐藏执行器。此约束也包含在复现脚本创建的 Agent 提示中。

## 部署与回归

最终代码提交 `788ec8a2d0c2c9e6ac8834a8c35f08e0f9a7bfbe`，服务器 API / MCP 镜像分别为 `runtime-os-api:studio-python-services-20261101-r6`、`runtime-os-mcp:studio-python-services-20261101-r6`。API JAR SHA256 为 `39584afffe3c324a8e7fbf287069108b8fb9db3dd93e8efe589d6fc1684ab537`，MCP JAR SHA256 为 `adafe6fb0d43900db936a37417bd4678fb33ece7531e4b76097cd7e90ca9981a`；上一轮已应用的策略迁移 SHA256 为 `45beb80a72fc045e9b221c21dbe5c8dde763709fba094baac472e3ec62b2b29e`。部署前备份和活动任务门禁保留，实际部署确认会话内容、消息索引和其他容器未变化，见 [最终部署回执](deployment-r6.json) 和 [策略迁移回执](deployment-r3.json)。

[146 项相关 Java 回归](regression-checks.json) 全通过；前端构建和 72 个 Vue 组件结构检查通过。旧发现/临时计算的实际观测输入最终核验脚本时被拒绝。此前 SQL 专项已经记录的四项基线失败保持原记录，不能将本轮选择的检查解释为全仓测试全部通过。


## 参数协议交接与风险边界

实际原生绑定已经返回年度 6 行、月度 11 行及正确来源路径，见 [交接前引用](pre-handoff-recordset-reference-bindings.json)。但后续 DAG 控制器重新生成了错误路径，执行桥接器正确拒绝 `TEMPLATE_PARAMETER_EVIDENCE_MISMATCH`，该轮没有执行发布模板，也未计入通过，见 [交接失败与正常取消](protocol-handoff-boundary.json)。

修复为：对于 Runtime 已绑定的模板，仅复用同一分支中成功、已审核、唯一且 READY 的参数协议，由 Runtime 标记目标执行步骤。控制器不再重写这些已审核的值与路径。失败、未审核、歧义或带绑定错误的协议不能复用；现有桥接器仍逐项核对工具结果和 Schema，未放宽来源校验。业务参数映射仍由模型选择。

另有一轮整体计划被模型标记中风险，现有风险门禁因此拦截 SQL 查询，见 [风险边界](plan-risk-confirmation-boundary.json)。[正式治理查询](actual-tool-governance.json) 确认四项能力均为 low/readonly/auto_execute，因此只通过正式 Agent 配置补充真实的只读统计、无网络、无持久副作用边界，没有修改风险策略或关闭确认校验。本轮未改通用中风险确认恢复流程，不能将该次失败描述成成功确认。


## Python 授权键一致性

上一轮已经实际调用 Python MCP 执行端，但独立授权检查拒绝，见 [授权失败](authorization-key-boundary.json) 和 [该轮真实工具观测](authorization-key-failed-tool-observations.json)。目录与发布绑定使用规范类型 `python_runtime`，Python 执行桥接仍读取旧键 `python`，导致已授权模板不可执行。

修复直接复用 `TemplateAssetCatalogService.PYTHON`，没有更改角色、用户或模板授权列表，没有删除授权检查。[29 项 MCP 回归](mcp-authorization-regression-checks.json) 全通过；其中验证了规范 Python 授权成功、空授权或只有 SQL 类型授权仍拒绝，客户端提交 `_authorizedTemplateIds` 也不能伪造授权。API/Runtime 核心另有 146 项通过记录。
