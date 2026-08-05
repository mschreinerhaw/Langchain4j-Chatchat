# Agent Runtime 模板参数传递契约

模板候选的高召回、证据评定、执行满意度复核及一次修复重做遵循
[`Agent Runtime 模板候选评定与执行满意度契约`](agent-runtime-template-evaluation-contract.md)。

## 目标

模型生成的是候选执行意图，不是工具请求。模型负责选择工作流允许的工具，并在固定 JSON 中表达模板线索、业务参数和逻辑目标；Agent Runtime 负责查询或读取 MCP 契约，将其编译为确定性的工具参数，并在发起执行调用前完成校验。该契约适用于 SQL、SQL DAG、HTTP/API 和 SSH 模板执行，不区分 MySQL、Oracle、PostgreSQL、SQL Server 或 Linux 命令类型。

## 模型侧固定 JSON

```json
{
  "toolCall": {
    "toolName": "sql_query_execute",
    "action": "MYSQL_INNODB_STATUS",
    "parameters": {},
    "context": {
      "purpose": "分析 InnoDB 当前状态",
      "stepId": "step-3",
      "dependsOn": ["step-2"],
      "target": {
        "assetName": "248测试数据库",
        "env": "DEV"
      }
    }
  }
}
```

模型只负责填写该语义结构。`toolName`/`action` 表达决策，`parameters` 表达业务值，`context` 表达调用目的和依赖；模型不负责 MCP 传输字段、参数容器映射、别名转换或最终模板执行器选择。旧 `invocation` 结构只作为兼容输入。

## 模板发现后的参数协议

当模板发现结果声明 `parameterSchema.required` 或 `requiredParameters` 时，模板执行节点不能直接进入确定性调度。DAG 控制器必须先读取模板契约，再从当前用户问题中提取语义参数，输出固定协议：

```json
{
  "protocol_version": "template_parameter_protocol_v2",
  "step_id": 3,
  "template_id": "QUERY_BY_TRADE_DATE",
  "arguments": {
    "trade_date": {
      "value": "20260716",
      "source": "user_query",
      "evidence": {
        "quote": "查询 20260716 交易日数据"
      }
    }
  },
  "unresolved_parameters": []
}
```

参数也可以来自已经成功完成的工具步骤，但必须给出可由 Runtime 回查的精确位置：

```json
{
  "value": "C-2002",
  "source": "tool_result",
  "evidence": {
    "step_id": 2,
    "output_path": "$.customers[0].id"
  }
}
```

模型负责分析、整理参数画像，不拥有执行权。Runtime 在最终模板调用前核对用户原文或成功步骤输出，拒绝不存在的步骤、错误路径和值不一致的参数，然后由模板桥接层应用 Schema、默认值和类型转换。

- `template_id` 必须是模板发现返回的标量 ID。
- `arguments` 只允许包含从本轮用户问题提取的业务值；每个值必须携带 `source=user_query` 和简短证据。
- 模板默认值、类型转换、别名映射和执行容器由 Runtime 处理，模型不得复制 Schema 或自行构造 MCP 请求。
- 无法从用户问题或前置步骤取得的参数可以写入 `unresolved_parameters`，但不得把猜测值塞入请求；Runtime 应先回退模板默认值或省略可选参数。只有模板声明为必填且没有默认值的参数仍然缺失时，当前模板才不得执行。
- Runtime 使用真实 `parameterSchema` 将协议值编译为最终 `parameters`；该机制同时适用于 SQL、HTTP/API 和 SSH 模板。

## 执行优先的参数决策规范

模板参数编译必须以“让合法模板尽可能运行”为目标，且不得通过硬编码客户号、日期、模板 ID 或业务场景实现。参数来源、默认值、必填性、别名和类型均以当前模板的 `parameterSchema` 与可回查证据为准。

Runtime 必须按以下规则处理每个参数：

1. 能从用户原文或成功前置步骤提取，并能通过模板参数协议映射、证据校验和类型校验的值，覆盖该参数的模板默认值。
2. 未提取到、名称无法映射、证据不足或不属于当前 Schema 的值，直接丢弃，不进入执行请求，也不阻断模板执行。
3. 已映射但类型、日期格式或枚举校验失败的覆盖值：参数有默认值时丢弃覆盖值并使用默认值；参数可选且无默认值时省略该参数。
4. 未被有效覆盖的其余参数使用模板声明的默认值。默认值由 Runtime/模板桥接层应用；模型不得复制或改写默认值。
5. 仅当参数被模板声明为必填、没有默认值，且没有可验证的有效覆盖值时，当前模板返回结构化参数错误，不发起远程调用。
6. 一个模板的参数错误只结束该模板子任务；批量或多模板执行层必须继续运行其他模板，并分别保留成功、空结果和失败信息。

| 编译结果 | Runtime 行为 | 是否阻断批量执行 |
| --- | --- | --- |
| 有效且有证据的覆盖值 | 覆盖默认值并执行 | 否 |
| 无法映射、未知或证据不足 | 丢弃覆盖值，使用默认值或省略可选参数 | 否 |
| 类型/枚举/日期无效，且有默认值 | 丢弃覆盖值，应用默认值 | 否 |
| 可选、无默认值且无有效值 | 省略该参数 | 否 |
| 必填、有默认值且无有效值 | 应用默认值 | 否 |
| 必填、无默认值且无有效值 | 当前模板返回参数错误，不调用远端 | 否，仅当前模板失败 |

确定性编译逻辑等价于：

```text
effectiveParameters = schemaDefaults
for each proposedOverride:
  if declaredBySchema && evidenceVerified && valueValid:
    effectiveParameters[name] = convertedValue
  else:
    drop proposedOverride
for each requiredParameter:
  if no effective value and no schema default:
    fail current template child only
execute all remaining runnable template children
```

示例：模板声明 `khh` 默认值为 `070200046604`。用户明确给出客户号并能映射到 `khh` 时覆盖该默认值；模型仅生成无法映射的 `customerId` 或该值缺少证据时，Runtime 丢弃它并使用 `khh` 的默认值，模板仍应运行。若 `pageSize` 的覆盖值无法转换为整数但模板默认值为 `50`，则使用 `50`，不得因该覆盖值终止执行。

## 标准请求结构

```json
{
  "templateId": "MYSQL_INNODB_STATUS",
  "template": "MYSQL_INNODB_STATUS",
  "parameters": {},
  "executionContext": {
    "assetName": "248测试数据库",
    "env": "DEV"
  }
}
```

- `templateId`/`template`：相同的非空标量字符串，只能来自发现结果的 `templates[i].templateId`。不得传模板对象、数组、Schema 或占位符。
- `parameters`：仅包含本次执行的业务参数值，必须满足所选模板的 `parameterSchema`。所有业务参数都放在该对象内。
- `executionContext`：仅包含逻辑路由上下文，例如 `assetName`、`env`、数据库角色或租户上下文；不得包含 JDBC、主机地址等具体端点。
- `parameterSchema`、`requiredParameters`、`parameterContract`、`invocationExample`、`selectedTemplate`：只读发现元数据，只用于编译和校验，禁止发送给执行器。

## Binding 规范

模板发现到执行步骤的标准绑定为：

```json
{
  "from": 2,
  "output_path": "$.templates[0].templateId",
  "to": 3,
  "input_path": "$.templateId",
  "type": "jsonpath",
  "required": true
}
```

`input_path` 是推荐字段，必须写入完整目标路径；`input_field` 仅作为兼容别名保留。例如资产名应绑定到
`$.filters.assetName` 或 `$.executionContext.assetName`，不能一边写根节点 `assetName`，一边在嵌套字段保留
`{{bindings.assetName}}`。新计划禁止使用绑定占位符；Runtime 只对历史计划递归兼容解析，并在 MCP 调用前拒绝任何
仍未解析的占位符。

模板发现的传输成功不等于业务成功。当返回模板数量为 0 时，Runtime 必须返回
`NO_MATCHING_TEMPLATE`，标记 `businessSatisfied=false`，并禁止依赖的模板执行步骤继续运行。

不得把 `$.templates[0]`、`$.templates[0].parameterSchema` 或整个发现响应绑定到 `templateId`。如果模型仍绑定了一个可识别的模板对象，Runtime 只能提取协议定义的标量 ID；无法唯一提取时必须在调用前失败，不能使用 `String.valueOf` 猜测。

## 校验与失败语义

1. Planner/rewriter 提示模型遵守该结构，但提示词不作为安全边界。
2. Plan Validator 对明显的对象式模板 ID、Schema-as-parameters 和非对象上下文报错。
3. Runtime 规范化别名、投影标量模板 ID、移除只读元数据并校验类型。
4. 如果当前执行上下文没有对应模板契约，Runtime 只能从本次 Agent 允许的工具集合中选择匹配的 MCP 模板发现工具，执行一次 `interpretation_plan_argument_resolution` 查询；不得绕过工作流权限，也不得无限递归修正。
5. Runtime 根据返回的 `parameterSchema`、`parameterContract`、执行器和路由上下文完成别名映射、默认值回退、必填校验及最终请求组装。只有“必填且无默认值”的参数缺少有效值时，当前模板必须明确失败；该失败不得终止同批其他模板，且任何情况下都禁止编造参数。
6. MCP 网关执行相同的防御性校验。违规请求返回 `TEMPLATE_ARGUMENT_CONTRACT_FAILED`，不得继续模板查找，也不得伪装成 `template not found`。
7. 模板 ID 合法但模板确实不存在或未启用时，才允许返回 `template not found or disabled`。

## 参数修正边界

处理顺序固定为：

```text
ToolCall JSON 解析
  → 本地 Schema 校验
  → 本地确定性修正
  → MCP 动态契约/标识解析（仅已注册、已授权的解析能力）
  → 结构化 INVALID_TOOL_ARGUMENTS
  → 再次校验
  → MCP 工具执行
```

本地 `ToolArgumentCompiler` 负责字符串/整数/数字/布尔值转换、`yyyyMMdd` 日期规范化、枚举大小写、默认值、Schema 别名、未知字段过滤和必填校验。无效覆盖值存在默认值时回退默认值，可选且无默认值时省略；只有必填且无默认值时返回当前模板的结构化错误。它不分析自然语言，也不把“最近一个月”“今天”等语义表达猜成具体值。

Runtime 应记录被丢弃覆盖值及其原因，例如 `droppedUnverifiedParameterOverrides`、`INVALID_OVERRIDE_DROPPED_DEFAULT_APPLIED` 或 `INVALID_OPTIONAL_OVERRIDE_DROPPED`；日志不得包含不必要的敏感参数明文。`TEMPLATE_PARAMETER_PROTOCOL_INCOMPLETE` 仅表示模板仍缺少必填且无默认值的参数，不能用于证据不足但可回退默认值的情况。

MCP 只用于动态信息：最新工具契约、模板/数据源/业务实体 ID、元数据，以及工具定义中显式注册的参数解析器。普通 JSON 格式错误不得交给 MCP 修复。当前 Runtime 的隐式契约查询最多执行一次，并且查询工具必须存在于本次 Agent 的 `allowedTools`；失败后直接返回结构化错误，不形成递归调用。

## 模块职责

- `AgentPlanner`：相当于 `ToolCallPlanner`，让模型只生成工具/action/业务参数及调用顺序。
- `ToolArgumentCompiler`：解析 ToolCall DSL，执行本地 Schema 编译并生成结构化校验错误。
- `ToolRuntimeService`：相当于 `McpToolExecutor`，负责经过授权的实际调用、协议适配、超时和执行结果。
- `InterpretationPlanRuntime`：只编排上述模块，维护 DAG 依赖、契约解析边界和执行状态，不理解自然语言业务意图。

该流程保证模型输出波动最多导致一次明确的契约错误或由 Runtime 安全纠正，不会把对象字符串当作模板 ID，也无需为每种数据库单独修复。
