# MCP 交互契约

本文档定义 `chatchat-mcp-server` 对模板、资产和执行工具的发布契约。

## 基本链路

MCP Server 必须支持两条交互链路：

- `工具类型/能力描述 -> 找模板 -> 判断模板 -> 执行模板 -> 总结`
- `找资产 -> 判断资产 -> 读取资产下关联模板 -> 判断模板 -> 执行模板 -> 总结`

## 模板发布契约

- 模板发现结果必须暴露 `templateId`、名称、描述、参数 schema 和执行绑定。
- 资产优先索引必须把模板挂在资产下，通过 `associatedTemplates` 暴露。
- 数据库查询模板必须返回 `sqlExecutionContext` 或等价执行上下文。
- 模板返回的 `mcpToolName` / `templateId` 是模板名称，不是 Agent Runtime 的 workflow 工具名。

## 执行绑定契约

模板必须声明实际执行者，例如：

```json
{
  "templateId": "query_edayQuqtMoni",
  "sqlExecutionBinding": {
    "toolName": "sql_query_execute",
    "templateId": "query_edayQuqtMoni",
    "executionContext": {
      "assetName": "达梦测试服务器",
      "env": "DEV",
      "databaseType": "dm"
    }
  }
}
```

MCP Server 侧执行工具收到模板名后，必须完成内部路由和执行。执行失败、权限不足、参数不足都必须返回结构化错误，不能返回虚假成功。

## 结构化输出契约

任何模板执行工具都必须返回结构化数据，不能只返回一段自然语言说明。

- SQL / 数据库查询：返回 `columns`、`rows`、`rowCount`、执行上下文和治理信息。
- Linux / SSH 命令：返回 `exitCode`、`stdout`、`stderr`、`steps`、执行图或诊断信息。
- HTTP / API 请求：返回 `statusCode`、`body`、`rawBody`、目标端点和执行信息。
- 通知、告警等副作用工具：返回 `success`、`statusCode`、目标通道、请求摘要、响应体或错误结构。

这些结构化字段是 Agent 最终总结的证据来源。没有结构化执行数据，Agent 不能生成业务判断。

## 证据边界

MCP Server 输出必须支持“无证据即臆造”的 Agent 侧判定。

- 成功执行必须返回可审计结构化数据。
- 执行失败必须返回结构化错误、失败原因和可定位上下文。
- 权限不足、参数不足、目标不存在等情况必须作为终态 observation 返回。
- 不能用自然语言总结替代结构化执行结果。
- 不能返回虚假成功，让 Agent 基于不存在的数据生成结论。

如果 MCP Server 无法提供结构化证据，应明确返回失败或证据不足状态。Agent 只能基于该状态说明阻断原因，不能生成业务结论。

## 总结边界

MCP Server 不生成业务最终结论。它只提供发现结果、执行结果和错误 observation。最终总结由 Agent 在工具执行闭环后完成。
