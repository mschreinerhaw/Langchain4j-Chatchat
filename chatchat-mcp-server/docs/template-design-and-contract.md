# chatchat-mcp-server 模板设计与契约

本文档定义 `chatchat-mcp-server` 模块的模板设计、MCP 工具契约、数据库查询模板契约和通知工具契约。

## 模块职责

`chatchat-mcp-server` 发布 MCP 工具，维护资产与模板注册，执行模板检索，暴露数据库查询模板、通知告警工具和治理元数据。

## 当前模板设计

- 模板索引应以资产为主线，关联可执行模板。
- 数据库查询索引应返回数据库资产、执行上下文和关联模板。
- 模板发现工具必须声明 `targetKind`、`assetType`、参数 schema 和执行方式。
- 工具 metadata 是 Runtime 修正模型参数的重要依据。
- MCP 交互主链路是 `工具类型/能力描述 -> 找模板 -> 判断模板 -> 执行模板 -> 总结` 或 `找资产 -> 判断资产 -> 读取资产下关联模板 -> 判断模板 -> 执行模板 -> 总结`。

## 数据库查询模板契约

数据库查询模板必须坚持资产优先：

- 数据库资产是一级索引对象。
- 模板挂在数据库资产下，通过 `associatedTemplates` 暴露。
- `datasourceAsset` 必须描述执行资产，例如资产名、工具名、环境和数据库类型。
- `sqlExecutionContext` 必须包含 `assetName`、`env`、`environment`、`databaseType`、`dbType` 等执行所需上下文。
- 每个关联模板必须描述 `templateId`、`mcpToolName`、业务分组、意图、风险等级和 execution binding。
- 数据库查询模板返回的是可执行模板名称，Runtime 必须把它作为 `template` / `templateId` 交给 `sql_query_execute` 或 `database_query_execute` 这类实际执行者。
- 模板名称不是 Agent Runtime 要直接调用的 workflow 工具名。
- 模板必须声明实际执行者，例如 `sqlExecutionBinding.toolName=sql_query_execute`。

## 数据库查询动态日期参数契约

数据库查询模板允许使用由 MCP Runtime 统一解析的动态日期参数。模型只允许选择参数和模板，不允许自行计算、拼接或改写日期值。

### 参数命名

| 参数 | 含义 | 格式 | 解析规则 |
| --- | --- | --- | --- |
| `${today}` | 当天自然日 | `yyyyMMdd` | 使用 Runtime 当前日期。 |
| `${natural_date}` | 当天自然日别名 | `yyyyMMdd` | 与 `${today}` 等价。 |
| `${month}` | 当前月份 | `yyyyMM` | 使用 Runtime 当前月份。 |
| `${month_start}` | 当月第一天 | `yyyyMMdd` | 当前月份的第一天。 |
| `${month_end}` | 当月最后一天 | `yyyyMMdd` | 当前月份的最后一天。 |
| `${trade_date}` | 当前交易日 | `yyyyMMdd` | 当天是交易日取当天，否则取最近可用交易日。 |
| `${trade_date-1}` | 上一个交易日 | `yyyyMMdd` | 以当天为基准向前偏移 1 个交易日。 |
| `${trade_date+1}` | 下一个交易日 | `yyyyMMdd` | 以当天为基准向后偏移 1 个交易日。 |

### SQL 模板约束

- SQL 模板可以使用 `${...}` 形式直接声明动态日期占位符，例如 `WHERE stat_date = ${trade_date}`。
- SQL 模板也可以使用命名参数，例如 `WHERE stat_date = :trade_date`；当参数名属于动态日期参数集合时，Runtime 必须在执行前自动补齐。
- 用户输入参数和动态日期参数必须分离维护。动态日期参数的默认来源应声明为 `defaultSource`，例如 `trade_date`、`trade_date-1`、`month`。
- 页面测试值、模型入参和 Agent 计划不得要求用户手工填写 `trade_date`、`today`、`month_start` 等动态日期参数。
- 动态日期解析必须发生在 SQL 安全校验和真实数据库执行之前。

### 交易日数据源

- 交易日计算由 MCP Runtime 负责，优先使用绑定数据库资产可访问的交易日表。
- 当前约定交易日表为 `dsc_cfg.t_xtjyr`，Runtime 可以缓存交易日表以降低频繁查询成本。
- 如果交易日表不可用，Runtime 必须返回结构化错误或明确降级策略，不得让模型临时编造交易日。

### 示例

```sql
SELECT *
FROM customer_asset
WHERE stat_date = ${trade_date}
  AND branch_id = :branch_id
```

Runtime 执行前解析为：

```sql
SELECT *
FROM customer_asset
WHERE stat_date = 20260707
  AND branch_id = :branch_id
```

其中 `branch_id` 仍由用户输入或权限上下文绑定，`trade_date` 由 Runtime 统一计算。

## MCP 工具契约

- MCP 工具必须校验 `finalDecision` 和 `candidates[].targetKind` 一致性。
- 专用工具应声明自己的目标域，例如数据库查询模板工具对应 `business_database_query` / `database_query`。
- 工具失败必须返回结构化错误和 repair hint。
- 模板执行工具不得返回虚假成功。

## 通知工具契约

通知告警配置中的每个可执行项应发布成 MCP 工具。调用工具即代表发送通知请求，必须返回成功、失败或权限不足 observation。

## Required Tool Principle

MCP Server 不判断模型是否“需要”执行工具。它只按工具契约执行、拒绝或返回修复提示。
> Intent Ensemble Retrieval contract: see `../../docs/intent-ensemble-retrieval-contract.md`.
> MCP Server targetKind schemas must allow semantic retrieval fields including `intentCandidates`, `intent_candidates`, `queries`, `expandedQueries`, `expanded_queries`, `queryTerms`, and `retrievalSignals`; these fields are retrieval signals, not exact routing labels.

## Agent Runtime Template DSL 契约

标准多步骤模板 DSL 契约见：`../../docs/agent-runtime-template-dsl-contract.md`。

MCP Server 侧必须遵守：

- 发布 Linux 命令、SQL 脚本、数据库查询和运维巡检模板时，支持 `agent_runtime_template_dsl.v1`。
- 模板索引必须消费 DSL 顶层字段、`analysisPolicy` 和 `steps[].stepName` / `steps[].analysisHint` / `steps[].command`。
- 模板发现结果必须返回 `templateDsl` 元数据，供模型 review 和 Runtime 修复执行路径使用。
- `linux_command_execute` 和 `sql_script_execute` 必须按 DSL steps 执行并返回 step 级结构化证据。
- `sql_query_execute` 只执行单 SQL；多步骤 SQL DSL 必须路由到 `sql_script_execute` 或等价多步骤执行器。
