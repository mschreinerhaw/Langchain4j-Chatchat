# SQL Studio 真实链路复验（2026-11-01）

> 整体状态：仅部分场景历史验证，端到端待验收。本文记录的固定业务范围复验不能替代用户当前模板入口、普通 Agent 盲测和系统内图表验收。最新整改见 `docs/sql-template-remediation`。

测试对象是 `248测试数据库` 的 MySQL `tpcds`，商品主键 1、1999 年、月度数量下限 0、品类不限。没有使用客户账户数据，也没有推断金额单位。

本轮新增可复验的 SQL 链路证据校验器及只读采集脚本。继续使用已经真实生成、试执行、发布的第 3 版模板，重新提交业务 Agent 请求、重新读取独立 JDBC 基准。生成与发布保留原始时间；本轮没有重复发布或修改共享白名单。服务器继续运行已部署的 r6 业务代码，本次改动仅为验收脚本与证据文档。

## 验收门禁

[`verify-chain.mjs`](../../scripts/sql-tpcds-acceptance/verify-chain.mjs) 核对以下实际证据，任一缺失即失败：

1. 自然语言生成任务成功、模型输出已应用；模型 SQL 与保存文件逐字一致。
2. 保存版本、试执行及当前发布 SQL 的 SHA256、工程、文件和数据源一致。
3. 试执行为 `SQL_DATABASE_EXECUTION`，有成功记录、返回行、traceId 和结果摘要。
4. MCP 实际发现的输入 Schema 与发布定义一致，Agent 具有该工具绑定。
5. 当前任务真正 `SUCCEEDED`；同一 attempt 中两个工具实际执行，有请求标识、耗时、数据源/模板标识和有效参数。
6. 返回数据完整且未截断，与独立只读 JDBC 的 6 行年度、11 行月度结果逐字段对比，共 119 个字段。

生成阶段实际状态为 `PARSED; FIELDS_NOT_EVALUATED; SQL_NOT_EXECUTED`，不是字段静态校验或运行成功。字段和表是否可执行由已有数据库试执行验证。本校验器不替代权限、SQL 校验器、发布服务或 Agent 的业务判断，不认证未知的单位与业务口径。

## 运行记录

- 原模型生成、试执行、发布及生命周期：见[原始 E01–E17 证据](../sql-tpcds-e2e/03-acceptance-report.md)。最终成功任务所对应的源码、试执行、发布和 MCP 描述均在[本轮真实快照](sql-run/source-chain.json)中。
- 本轮独立只读查询：[fresh-jdbc.json](fresh-jdbc.json)，采集于 `2026-10-31T19:30:32Z`（北京时间 11 月 1 日）。金额以数据库十进制文本保留，没有浮点容差。
- 首次新任务 `381f86ee-03d0-373d-adbc-66f1ddc10313` 返回 `PARTIAL`，规划器要求澄清多个目标，没有 SQL 执行；[原始响应](clarification-task.json)。不计通过。
- 第二次任务 `46a076b3-890a-31d2-becf-432bfa1c78b6` 使用 qwen3.6-plus，规划模型请求超时，最终 `FAILED`、`request timed out`，没有 SQL 执行；[原始终态](timeout-agent-result.json)、[事件](timeout-agent-events.json)、[观察](timeout-observations.json)、[发布和 Agent 快照](timeout-source-chain.json)均保留。不计通过。
- 最终任务 `2da894bb-edfc-35dc-970d-27b6cc93f70d` 使用已授权 SQL Agent（deepseek-v4-flash），于 `2026-10-31T19:39:42Z` 真正 `SUCCEEDED`，耗时约 171 秒；[原始任务](sql-run/agent-result.json)、[完整回答](sql-run/agent-final-answer.md)、[实际调用观察](sql-run/observations-att-1-516a6b2c-96fc-4485-bcc8-6fcd408c7737.json)、[本轮源码及发布快照](sql-run/source-chain.json)。没有修改 Agent 的权限或模型配置来绕过失败。
- [本轮完整 SQL 链路对账](sql-run/exact-chain-proof.json)：年度实际调用 3761 ms、6 行/42 字段；月度 3568 ms、11 行/77 字段；共 119 字段完全一致。两个工具执行的 requestId 为 `799e9151-fafb-4b43-b28c-d7847ebd494d`。该耗时包含 Runtime 调用，不等于纯数据库耗时。参数、发布 ID 和源码摘要均在证明中。
- [Python 持久执行时间线](sql-run/coding-att-1-516a6b2c-96fc-4485-bcc8-6fcd408c7737.json) 保留首次失败（exit 1）及模型修复后的成功。最终执行 `cline-ca4960effa34b4b164b2d9cdc81b7a63154e55a651ca47f740e1049e5ab7cdca` 为 exit 0、`VALIDATED`、resultAvailable=true、executorStopped=true；[实际归档回执与源码哈希](sql-run/python-execution-proof.json)、[原始代码](sql-run/cline-ca4960effa34b4b164b2d9cdc81b7a63154e55a651ca47f740e1049e5ab7cdca.py)。代码 SHA256 与提交执行的 22240 字节完全一致。

本次最终请求没有要求图表，后续 Python 是普通分析结果，不能称作新的图表或“已发布 Python 模板”执行。已发布 Python 服务的真实闭环与数值证明仍在[独立的 SQL/Python 专项](../studio-sql-python-e2e/README.md)，两种执行模式明确区分。

本轮 [NODE_CLINE / SKILLS_LOADED 事件](sql-run/skill-load-events.json)记录按需读取 `builtin:python-data-analysis` 1.3.0；没有加载无关 Azure 技能，也未将自有分析能力标为微软来源。

本轮 Python 的业务验证原样为 `INCONCLUSIVE`、businessValidationPassed=false：报告收到聚合数据，未获得可证明的参数来源/明细归因及金额单位。外部链路校验能核对实际执行参数及独立 JDBC 的 NULL 数量明细，不能将这些未交付给报告的材料倒填为模型已知事实；未把语义认证改成通过。

原始业务事实仍须保留：年度有 66 条销售记录，月度筛选有 65 条；数量 NULL 的一条记录解释净收款差额 232.51、利润差额 -2143.49。缺少的 3 月没有补为真实零值，不据此断言数据库错误。具体独立 NULL 数量查询结果包含在本轮 JDBC 基准中。

## Skills 来源与职责

| 能力 | 本批实际来源与适用范围 | 证据及限制 |
| --- | --- | --- |
| Python 数据分析 | Runtime OS `python-data-analysis` 1.3.0、`python-code-development` 1.0.0 | [真实 SQL→发布 Python→Agent 结果](../studio-sql-python-e2e/README.md)；不是微软原生 Pandas 包 |
| 可视化、数据探索、统计方法 | 自有 Python 分析技能及其按需引用的 data-methods / 图表方法 | 真实 Python 图表与数值已独立对账；未知单位和业务语义保留 INCONCLUSIVE |
| MySQL SQL 开发与数据查询 | 既有 SQL Studio 生成/试执行/模板发布管线 | SQL 服务负责经过验证的可复用查询；没有将 Azure/T-SQL 技能改写为 MySQL 技能 |
| MCP 方法、原生技能组织 | 微软原生 `mcp-builder`、`skill-creator` | [原生 Cline 按需读取证据](../sql-tpcds-e2e/cline-microsoft-live.json)；不替代发布服务，不授予工具权限 |
| ADX、Azure ML | 微软原生 `azure-kusto`、`azure-ai-ml-py` | 独立的可选技能资产；本次 MySQL 不加载、不执行 Azure 能力 |

已内置的 4 项微软技能来自 `microsoft/skills` 固定提交 `3f5b461b2314e01c400564051e92469114c5b68f`，本轮重新校验 SKILL.md、全部资源与许可证共 25 个文件，[全部 SHA256 匹配](native-skill-integrity.json)。正文、frontmatter、目录和资源保留原生格式，Java 只传元数据，Cline 按需读取。技能没有绑定 SQL 模板生命周期；SQL/Python 数据服务由独立的模板权限与执行边界控制。

新增来源审查：[`microsoft/microsoft-sql` 固定提交](https://github.com/microsoft/microsoft-sql/tree/eeb1c6867c2d128763516a1aad41671c593cc189)，MIT；[五份原始文件的路径、大小和 SHA256](microsoft-sql-audit.json)。[官方 Azure SQL 技能](https://github.com/microsoft/microsoft-sql/blob/eeb1c6867c2d128763516a1aad41671c593cc189/plugins/microsoft-sql/skills/azure-sql/SKILL.md) 明确限定 Azure SQL Database；[T-SQL 正确性技能](https://github.com/microsoft/microsoft-sql/blob/eeb1c6867c2d128763516a1aad41671c593cc189/plugins/microsoft-sql/skills/t-sql-correctness/SKILL.md) 会纠正 MySQL 的 `LIMIT` 等习惯，因此未引入本次 MySQL 技能目录。不是将整库打包后注入 Cline，也没有修改其原始规则使其貌似兼容。

通用能力可以由现有技能与适用的微软技能组合提供；来源如上明确列示。没有编造微软官方名为 Python Data Analysis / Data Visualization 的通用包，也未声称已运行 Azure、Fabric 或 Data Factory。

## 复现与检查

操作说明见[真实环境复验脚本](../../scripts/sql-tpcds-acceptance/README.md)。本地门禁回归使用真实归档记录与受控损坏副本，12 项通过，覆盖缺少实际调用、失败试执行、源码变化、Schema 漂移、撤权、错误 attempt、日期偏移、数据不符等假阳性。它不替代在线验收。

```powershell
node --test scripts/sql-tpcds-acceptance/verify-chain.test.mjs
node scripts/sql-tpcds-acceptance/verify-microsoft-sql-source.mjs
node scripts/sql-tpcds-acceptance/verify-chain.mjs docs/sql-chain-revalidation/sql-run docs/sql-chain-revalidation/fresh-jdbc.json target/replayed-sql-chain-proof.json
```

本轮未修改 Java 需求分析、领域技能编译、SQL 公共参数编译器、权限门禁或业务任务状态。既有模型超时、复杂多目标规划及业务语义未认证限制仍保留；不声明全仓测试通过。
