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
