# TPC-DS 真实链路验收记录

测试资产为 `248测试数据库`（`f0421bd7-1be8-4d5c-8f65-b216d4f997cb`），MySQL `tpcds`。只读查询 `store_sales`、`date_dim`、`item`，业务范围明确为商品主键 1，日期 1999-01-01 至 1999-12-31；年度无参数服务另返回六年汇总。此数据是零售测试数据，金额币种和单位未声明，不当作金融客户数据或人民币。

SQL 由正式模型生成后保存，生产模板没有手写替换 SQL。独立 JDBC 基准由另一个只读连接执行，独立使用 YEAR/MONTH/BETWEEN 表达业务口径。初次全表聚合在真实 10 秒试执行上限内超时，随后明确调整需求为单商品范围重新调用模型；失败记录保留在服务器和私有验收日志。

## 当前真实证据

- 工程：`b6f7520b-2a55-430a-9351-a601e32dcfa3`。
- 无参数生成：`a348834c-8980-4be9-ad14-75dbea53debc`；多参数生成：`7f505192-43bf-4eb6-a7d5-fecd2f1fe586`。
- Agent：`tpcds_sql_studio_acceptance_20261031`。仅授权两个模板工具；没有绑定通用原始 SQL 工具或整个 MCP 服务。
- 综合分析任务：`61513e04-05f0-327b-862f-7da8d834a3e6`；运行：`att-1-22202adc-7040-47bf-b3f6-ae53521ba1c4`。
- 两个实际工具调用的 requestId 为 `9be24df5-dadf-4b72-8862-923ed45a8444`，同时出现在 SQL operation.sourceTaskId。代码任务、执行 operationId 及产物分别记录在后续 Python 证据中。

`sql-chain-live.json` 包含模型任务、参数 Schema、真实试执行预览、发布版本、Agent 实际参数、完整返回记录及 trace；`mcp-tool-descriptors.json` 是正式发现接口返回的实际描述，不是手工构造的工具定义。

## E01–E17

| 用例 | 状态 | 实际依据 |
| --- | --- | --- |
| E01 无参数生成 | 通过 | 正式模型生成并保存 yearly-model-generated.sql；真实执行返回六年汇总 |
| E02 带参数生成 | 通过 | 模型生成 monthly-model-generated.sql，日期参数真实限定结果 |
| E03 多参数映射 | 通过 | 五个命名参数；类型、日期必填、默认数量、可空品类在发布前显式审阅；真实参数矩阵覆盖正常和错误输入 |
| E04 真实试执行 | 通过 | SQL_DATABASE_EXECUTION，真实行数、耗时、摘要和限量预览；错误参数不获得成功证据 |
| E05 草稿 | 通过 | 正常保存并以同 operationId 重试，未创建重复草稿 |
| E06 无参数发布 | 通过 | 既有 release 管线发布，真实 Agent 返回六行数据 |
| E07 参数发布 | 通过 | MCP 实际 Schema 与发布定义一致，Agent 调用返回十一行数据 |
| E08 Agent 发现 | 通过 | 正常工具授权后自动发现，无手工编写 Agent 工具 Schema |
| E09 无参数调用 | 通过 | 原任务 1c21cbb7-42fd-3d3e-988e-a8e17054d524 成功；综合任务再次调用，六行、42 字段与 JDBC 一致 |
| E10 参数调用 | 通过 | 综合任务实际传入商品、1999 年起止日期及默认数量 50；十一行、77 字段与 JDBC 一致 |
| E11 多工具综合分析 | 通过（含数据质量告警） | 最终任务 d31c32ef-41a1-3c45-81e4-a870faf6ba21 同次运行实际调用两个模板，6+11 行、119 字段与 JDBC 一致，生成真实 Python 对账报告 |
| E12 修改再发布 | 通过 | 相同工具名称，第 2 版默认数量从 0 改为 50；Agent 未重新绑定，实际查询得到 33 笔筛选记录对应的十一行聚合，与独立基准一致 |
| E13 停用 | 通过（边界见说明） | Release OFFLINE、SQL active=false；原 Agent 只能发现年度工具；正常 Runtime 调用真实拒绝 MCP_TOOL_NOT_FOUND |
| E14 回滚 | 通过 | 重新真实试执行后恢复默认 0、发布第 3 版；Agent 未重绑，实际十一行 77 字段与原口径 JDBC 完全一致，见 monthly-restored-jdbc-agent-comparison.json |
| E15 Skills 发现 | 通过 | 部署镜像内六项元数据；Cline 原生 parser 与真实任务识别微软 mcp-builder |
| E16 按需读取 | 通过 | cline-microsoft-live.json 中真实 NODE_CLINE SKILLS_LOADED；原正文与资源分次读取，未加载不相关 Azure 技能 |
| E17 Python 分析和图表 | 执行、产物与数值核验通过；业务语义未认证 | Sandbox exit=0、executorStopped=true；原始代码及两张 PNG 逐字节 SHA256 核验；7 指标、33 月度数值、9 对账数值与 JDBC 精确一致。保留 FAILED 的数据完整性检查与 INCONCLUSIVE 的运行时业务验证 |

## 独立对账与口径

年度 1999 年：66 行销售记录、数量 3362、净收款 136947.06、利润 -65502.46。

原月度默认数量 0：65 行、数量 3362、净收款 136714.55、利润 -63358.97。独立查询确认有 1 行数量 NULL，其净收款为 232.51、利润为 -2143.49，解释了年度与原月度的差额。不能据此报告数据库勾稽错误。

第 2 版默认数量 50：33 行、数量 2535、净收款 100958.34、利润 -53350.31。11 个实际月度聚合逐字段与独立查询一致，详见 `monthly-v2-jdbc-agent-comparison.json`。当月没有满足条件的记录，不应补造成真实零值。

## 失败与边界

保留了参数错误、全表超时、过期依赖快照、旧 head 发布冲突、组合独立目标触发澄清、显式 null 引发旧 Map.copyOf 异常、旧高风险标签导致计划等待确认等真实失败。已修复的问题及回归范围见 `02-implementation-and-checks.md`。未签名的直接 MCP 调用实际返回 TEMPLATE_ACCESS_DENIED，未伪造 Agent 身份绕过授权。

上游微软资产为四个经筛选的原生技能；Azure 技能依赖未授权的外部服务，不能在 MySQL 测试中声称已经执行 Azure 查询或训练。通用 Microsoft Pandas/可视化资产未核实，不编造同名包。真实 Python 数据分析仍使用 Runtime OS 原有技能，来源独立列明。微软 MCP Builder 真实任务产出的是代码提案，未执行该校验器，不能冒充 E17。

扩展 Java 回归有四项在基线同样失败，已记录名称与复跑证据；不能宣称全仓测试全通过。

停用负向任务 `e14ab620-8ec6-33c1-84d5-54031225ff34` 确认月度能力不可发现。Agent 尝试处理其它年度材料时遇到实际 Python 字段和输出契约错误，未产生成功的月度查询；已正常取消该负向分析。E13 的拒绝依据是同一时间正式 Runtime 调用的 MCP_TOOL_NOT_FOUND，不把被取消的整项分析报告成成功。见 `offline-release-proof.json`。

综合任务 `61513e04-05f0-327b-862f-7da8d834a3e6` 已完成两项真实查询及对账，但 Cline 的后续模型请求在 300 秒后超时，没有启动 Python。已通过正常接口取消父任务和请求取消代码任务；代码任务仍要求未知模型操作的停止证据，不伪造 executorStopped。后一次 qwen3.8-flash 任务 `5431b60c-c20f-3f92-8227-6616ec1b3aad` 产生无效 DATA_FETCH 意图而被现有问题分析器拒绝，未修改分析器来放宽验收。qwen3.6-plus 任务 `bab56176-0d04-3395-914c-9f52e55040fb` 真实完成恢复版本的两次查询；后续代码修复未遵守增量补丁协议，最终 FAILED、executorStopped=true，不作为 Python 成功证据。两项早期模型未知状态仍为 cancellationRequested=true、reconciliationRequired=true，见 `prior-task-cancellation-proof.json`，这是未修复的模型超时收敛限制。

## 最终 Python 真实结果（2026-11-01，Asia/Shanghai）

最终使用 `deepseek-v4-flash` 的 Agent 任务为 `d31c32ef-41a1-3c45-81e4-a870faf6ba21`，运行 `att-1-dd39d001-e1b0-4b31-8a3a-ac6da92881a0`；数据探索项目 `f03e9781-931f-4300-870b-1abf7c932fe4`、节点 `377f0735-8455-4078-9804-bee1a7797108`。正式状态接口返回 terminal=true、status=SUCCEEDED、resultAvailable=true。

实际 Cline 按需读取 `builtin:python-data-analysis` 1.3.0；它是 Runtime OS 自有技能，未冒称微软技能。`python-skill-load-proof.json` 保留 NODE_CLINE/SKILLS_LOADED 真实事件。此前微软原生技能的独立真实加载证据仍见 `cline-microsoft-live.json`。

代码任务 `analysis-coding-fd69bebc97ba69856d4891f8f2a5703808e92e75ee69299e418673353ea642dd` 实际经历两次 Python KeyError，Cline 根据反馈增量修复后第三次成功，第四次补充负分母差额率解释后再次成功。没有人工替换生成代码或修改业务状态。

最终执行 `cline-d6072cc5dd7287bcbcad6e8af94830d34adce9ae73bb2c2b19e2cfaa468843d2` 的代码 SHA256 为 `c9407f627b4e6070acff3da84a8c0925c4eabeeb65657e1e01c0ade5a14eb4c3`，exitCode=0、stage=VALIDATED、executorStopped=true，产物完整性 PASSED。原始程序为 `executed-analysis.py`，报告为 `python-visual-report.json`；两张图分别为 `monthly_trend_1999.png`、`reconciliation_annual_vs_monthly_1999.png`。真实网页截图见 `python-analysis-real.png`、`python-analysis-evidence-real.png`、`agent-call-chain-real.png`。

`final-yearly-jdbc-agent-comparison.json` 与 `final-monthly-jdbc-agent-comparison.json` 验证本次实际查询 119 个字段。`python-jdbc-comparison.json` 另外使用精确整数分值验证 7 个指标、33 个逐月数值、9 个对账数值；三月明确为无观测，没有补零。

**不能把数值通过解释为业务检查全部通过。** 程序的 validation.status=FAILED 是因为全年月份覆盖检查发现三月没有返回数据；运行时 validationStatus=INCONCLUSIVE、businessValidationPassed=false 保持原样。报告还指出金额单位未声明、交付数据缺少参数绑定回执和 NULL 子集明细，因此仅推断差额机制。独立验收查询已量化 NULL 子集，但没有将此额外材料事后写进模型输入、伪装成模型已掌握的证据。最终接受的是 SQL→Agent→Python 的真实技术链路及数值正确性，不是金额币种、完整年度数据覆盖或业务语义认证。
