# MCP 交互契约

本文档定义 Agent Runtime 与 MCP 工具交互的基本契约。

## 基本原则

MCP 交互必须走“发现对象 -> 判断对象 -> 交给执行者 -> 基于结果总结”的链路。

支持两条主路径：

1. 按工具类型描述找模板

   `工具类型/能力描述 -> 找模板 -> 判断模板 -> 执行模板 -> 总结`

2. 按资产找模板

   `找资产 -> 判断资产 -> 读取资产下关联模板 -> 判断模板 -> 执行模板 -> 总结`

## Runtime 边界

- 模板名不是 Agent Runtime 直接调用的 MCP workflow 工具名。
- 模板名是执行参数，必须交给 `sql_query_execute`、`database_query_execute`、`linux_command_execute`、`http_request_execute` 等实际执行者。
- 模型可以参与计划制定、模板判断、资产判断、结果 review 和最终总结。
- 模型不能把“发现到模板”当成“已经执行模板”。
- 没有执行 observation，不能生成业务结论。
- 没有结构化执行数据，不能生成业务结论。模板执行结果必须留下可审计的数据依据，而不是只留下模型自然语言。
- 没有事实证据的回答一律视为臆造或猜测，不能作为最终业务结论返回。

## 结构化证据契约

任何模板执行都必须返回结构化证据，Runtime 和最终总结必须保留并展示这些证据。

适用范围包括但不限于：

- SQL / 数据库查询模板：必须包含 `columns`、`rows`、`rowCount` 或等价结构。
- Linux / SSH 命令模板：必须包含 `exitCode`、`stdout`、`stderr`、`steps` 或等价结构。
- HTTP / API 请求模板：必须包含 `statusCode`、`body`、`rawBody` 或等价结构。
- 其他模板：必须至少包含结构化 JSON 输出、执行状态、关键字段摘要或错误结构。

最终答案必须满足：

- 业务判断只能引用成功工具返回的结构化证据。
- 失败工具只能作为失败事实，不得作为业务事实依据。
- 如果工具返回表格数据，答案必须展示数据明细，并把 `visualizationSpec.dataset.rows` 传给用户端。
- 如果工具返回非表格数据，答案必须展示“工具执行证据”，列出工具名、成功状态、关键结构化字段和输出摘要。
- 模型可以总结证据，但不能替代证据本身。

## 无证据即臆造

回答必须能追溯到明确事实来源。事实来源只能来自：

- 已完成工具返回的结构化结果。
- 已完成工具返回的错误、权限不足、参数不足等终态 observation。
- 用户在当前请求或历史上下文中明确给出的事实。
- 已检索文档、知识库、网页等带来源标识的证据。

以下内容不构成事实证据：

- 模型根据常识、经验或概率做出的猜测。
- 未执行工具时对工具结果的预判。
- 只发现模板、资产或工具，但没有执行结果。
- 没有来源标识的自然语言描述。
- reviewer 或 planner 的自我判断。

当证据不足时，最终答案必须明确写成“无法确认”“缺少证据”“工具未执行/执行失败”，并列出缺口；不得包装成确定性业务结论。

## 回答证据状态表达

最终答案必须让用户第一眼看出回答性质：

- 有成功工具结构化结果、带来源文档证据、知识库证据或执行证据时，标记为 `有事实依据的分析`。
- 工具失败、权限不足、参数不足或强制流程未完成时，标记为 `执行阻断/证据不足`，可以总结失败事实、缺口和排查参考，不能输出确定性业务结论。
- 没有任何可追溯事实来源时，标记为 `证据不足/推测`，可以输出参考性推测、分析思路和下一步建议，不能输出确定性业务结论。

该表达必须出现在回答正文开头，同时写入 `answerEvidenceStatus`、`answerEvidenceLabel` 等 metadata。metadata 不能替代用户可见说明。

## 模板占位步骤

如果模型把模板发现结果中的 `templateId` / `mcpToolName` 写成后续步骤的 `tool_name`，Runtime 必须按模板元数据修复：

1. 在已完成的模板发现结果中找到该模板。
2. 读取模板声明的执行绑定，例如 `sqlExecutionBinding.toolName`。
3. 将步骤工具名改为真实执行者。
4. 将模板名写入 `template` / `templateId`。
5. 合并模板返回的 `executionContext`。
6. 调用执行者并记录 observation。

如果模板没有声明执行者，或者执行者不在当前可用工具中，Runtime 必须失败并记录错误 observation，不得直接调用模板名。

## 最终总结 Gate

最终总结只能基于以下事实生成：

- 模板发现 observation
- 资产发现 observation
- 模板执行成功 observation
- 模板执行失败 observation
- 权限不足 observation

只发现模板但未执行模板时，最终答案只能说明流程阻断原因，不能输出业务分析结论。
# Agent Runtime Template DSL 契约引用

标准多步骤模板 DSL 契约见：`../../docs/agent-runtime-template-dsl-contract.md`。

Agent Runtime 侧必须遵守：

- 模板判断时优先使用 `templates[].templateDsl.steps[].stepName` 和 `templates[].templateDsl.steps[].analysisHint`。
- 执行时只把 `template` / `templateId` 和参数交给真实执行器，不把 `steps[].command` 作为模型可自由改写的执行文本。
- Linux / SSH 多步骤模板交给 `linux_command_execute`。
- SQL 多步骤 DSL 交给 `sql_script_execute` 或等价多步骤 SQL executor。
- `sql_query_execute` 仅用于单 SQL 查询，不用于 DSL 多步骤模板。
- 最终总结必须引用执行工具返回的 step 级结构化证据；只看到 `templateDsl` 但没有执行 observation 时，不得生成业务结论。
