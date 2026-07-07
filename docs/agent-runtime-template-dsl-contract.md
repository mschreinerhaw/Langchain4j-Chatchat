# Agent Runtime Template DSL 契约规范

本文档定义 ChatChat Agent Runtime 与 MCP Server 之间的标准多步骤模板 DSL 契约。该契约适用于 Linux 命令模板、SQL 脚本模板、数据库查询模板和后续可扩展的运维巡检模板。

该 DSL 的目标不是让模型自由拼接命令或 SQL，而是让模板注册、检索、执行、审计和最终证据总结都使用同一份结构化协议。

## 协议版本

当前标准版本：

```text
agent_runtime_template_dsl.v1
```

实现侧对应常量：

```java
AgentRuntimeTemplateDsl.SCHEMA_VERSION = "agent_runtime_template_dsl.v1"
```

## 适用范围

该 DSL 适用于：

- Linux / SSH 命令模板，执行工具为 `linux_command_execute`。
- 多步骤 SQL 脚本模板，执行工具为 `sql_script_execute` 或等价注册名。
- 数据库查询模板中的多步骤 SQL 场景，执行工具应路由到支持多步骤的 SQL script executor。
- SQL 运维巡检模板，例如实例状态、锁等待、连接数、慢 SQL、空间使用率等组合检查。

该 DSL 不用于：

- 让模型绕过模板发现，直接生成 raw shell、raw SQL、raw URL。
- 把不受控的用户输入拼成可执行文本。
- 替代资产发现、模板发现、权限校验和审计。

## 标准结构

标准 DSL 必须是 JSON object，并包含非空 `steps` 数组。

```json
{
  "templateCode": "DM_INSTANCE_STATUS",
  "templateName": "达梦数据库实例状态分析",
  "templateType": "DB_SQL",
  "targetType": "DM",
  "description": "采集数据库实例、会话、锁、表空间等运行状态信息",
  "riskLevel": "LOW",
  "timeoutSeconds": 60,
  "executionMode": "SEQUENTIAL",
  "continueOnError": true,
  "steps": [
    {
      "stepCode": "INSTANCE_INFO",
      "stepName": "实例信息",
      "stepType": "SQL",
      "order": 1,
      "required": true,
      "timeoutSeconds": 10,
      "command": "SELECT INSTANCE_NAME, HOST_NAME, STATUS, STARTUP_TIME FROM V$INSTANCE",
      "analysisHint": "判断数据库实例是否正常运行、启动时间是否异常、主机信息是否符合预期。"
    }
  ],
  "analysisPolicy": {
    "summaryRequired": true,
    "evidenceRequired": true,
    "outputSections": [
      "总体结论",
      "异常项",
      "关键证据",
      "风险等级",
      "处理建议"
    ]
  }
}
```

## 顶层字段契约

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `templateCode` | string | 建议必填 | 模板稳定编码。未提供时执行器可使用注册模板编码兜底。 |
| `templateName` | string | 建议必填 | 模板展示名称，用于检索、review 和审计展示。 |
| `templateType` | string | 建议必填 | 模板类型，例如 `LINUX_CMD`、`DB_SQL`、`DATABASE_QUERY`、`OPS_CHECK`。 |
| `targetType` | string | 可选 | 目标类型，例如 `LINUX`、`MYSQL`、`DM`、`ORACLE`、`POSTGRESQL`。 |
| `description` | string | 可选 | 模板说明。可参与模板检索，但不能替代 steps。 |
| `riskLevel` | string | 可选 | 风险等级，例如 `LOW`、`MEDIUM`、`HIGH`。 |
| `timeoutSeconds` | number | 可选 | 模板总超时时间。执行器可结合资产默认超时取更严格值。 |
| `executionMode` | string | 可选 | 当前标准值为 `SEQUENTIAL`。未来可扩展 `PARALLEL`、`CONDITIONAL`。 |
| `continueOnError` | boolean | 可选 | 单步骤失败后是否继续。默认按执行器策略处理，标准建议显式声明。 |
| `steps` | array | 必填 | 非空步骤数组。执行器必须按 `order` 排序执行。 |
| `analysisPolicy` | object | 可选 | 给模型的分析输出策略，不参与执行授权。 |

## Step 字段契约

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `stepCode` | string | 建议必填 | 步骤稳定编码。未提供时执行器可生成 `STEP_<order>`。 |
| `stepName` | string | 建议必填 | 步骤名称。必须进入模板检索和模型 review 元数据。 |
| `stepType` | string | 建议必填 | 步骤类型，例如 `SHELL`、`SQL`、`HTTP`、`MCP_TOOL`。 |
| `order` | number | 可选 | 步骤顺序。未提供时按数组位置从 1 开始。 |
| `required` | boolean | 可选 | 是否关键步骤。默认可由执行器兜底；建议显式声明。 |
| `timeoutSeconds` | number | 可选 | 单步骤超时。 |
| `command` | string | 必填 | 实际模板步骤内容。SQL 和 shell 都统一写入 `command`。 |
| `sql` | string | 兼容字段 | 可作为 `command` 的别名，执行器规范化为 `command`。 |
| `shell` | string | 兼容字段 | 可作为 `command` 的别名，执行器规范化为 `command`。 |
| `analysisHint` | string | 建议必填 | 给模型的步骤解释依据。必须进入模板检索和执行结果结构化证据。 |

## 字段别名兼容

执行器可以兼容以下别名，但标准导入导出应优先使用 canonical 字段：

| Canonical | 兼容别名 |
| --- | --- |
| `templateCode` | `template` |
| `templateName` | `name` |
| `stepCode` | `code` |
| `stepName` | `name` |
| `stepType` | `type` |
| `command` | `sql`、`shell` |

导出 DSL 时必须使用 canonical 字段，避免后续检索、审计和跨模块消费出现歧义。

## 检索契约

标准 DSL 必须能被模板检索命中，并能被模型用于模板判断。

模板索引必须消费以下字段：

- 顶层：`schemaVersion`、`templateCode`、`templateName`、`templateType`、`targetType`、`executionMode`、`riskLevel`。
- `analysisPolicy` 中的自然语言值和结构化标签。
- 每个 step 的 `stepCode`、`stepName`、`stepType`、`analysisHint`、`command`。

模板发现结果必须返回模型可见的 `templateDsl` 元数据：

```json
{
  "templateId": "CHECK_MYSQLD_PROCESS",
  "templateDsl": {
    "schemaVersion": "agent_runtime_template_dsl.v1",
    "dsl": true,
    "templateCode": "CHECK_MYSQLD_PROCESS",
    "templateName": "MySQL daemon process check",
    "templateType": "LINUX_CMD",
    "targetType": "LINUX",
    "executionMode": "SEQUENTIAL",
    "continueOnError": true,
    "riskLevel": "LOW",
    "stepCount": 1,
    "steps": [
      {
        "stepCode": "MYSQLD_PROCESS",
        "stepName": "MySQL daemon process",
        "stepType": "SHELL",
        "order": 1,
        "required": true,
        "analysisHint": "Judge whether mysqld or mariadbd management daemon is running."
      }
    ],
    "analysisPolicy": {
      "outputSections": ["summary", "evidence"]
    }
  }
}
```

模型 review 模板时，必须优先使用：

```text
templates[].templateDsl.steps[].stepName
templates[].templateDsl.steps[].analysisHint
templates[].intentSignals
templates[].parameterSchema
templates[].execution / binding
```

模型不得仅凭模板标题或模板编码做最终选择。

## 执行工具契约

### `linux_command_execute`

`linux_command_execute` 必须按 DSL steps 执行。

执行链路：

```text
ssh_asset_query
  -> ssh_template_query
  -> linux_command_execute(template)
  -> parse template DSL
  -> steps 按 order 顺序执行
  -> 返回结构化 step 证据
```

如果模板正文不是 DSL，执行器可以降级为 legacy 单步或多命令 steps，但仍必须把执行结果组织成 `steps`。

### `sql_script_execute`

`sql_script_execute` 是多步骤 SQL DSL 的标准执行器。

执行链路：

```text
sql_datasource_asset_query
  -> sql_datasource_template_query
  -> sql_script_execute(templateId)
  -> parse template DSL
  -> steps 按 order 顺序执行
  -> 返回每条 SQL 的 columns / rows / rowCount / error
```

如果某个 step 失败：

- `required=true` 且执行器策略要求停止时，后续步骤不得继续。
- `continueOnError=true` 且 step 非关键时，可以继续执行后续步骤。
- 失败步骤必须保留结构化错误，不得被自然语言摘要吞掉。

### `sql_query_execute`

`sql_query_execute` 是单 SQL 查询执行器，不是多步骤 DSL 执行器。

它只能消费：

- 一条 `sql`。
- 或一个授权 `templateId` 渲染出的一条 SQL。

如果模板包含多个 SQL step 或 DSL `steps`，应路由到 `sql_script_execute`，不能塞给 `sql_query_execute`。

### 数据库查询动态工具

动态 `database_query` 工具如果绑定的是单查询模板，可以按现有单查询工具执行。

如果绑定的是 DSL 多步骤 SQL 模板，必须满足以下任一条件：

- 直接调用支持 DSL steps 的数据库查询执行器。
- 或桥接到 `sql_script_execute` 并透传 datasource、template、parameters、executionContext。

动态数据库查询工具不得把 DSL 多步骤模板压扁成一段自然语言，也不得只执行第一条 SQL 后宣称完成。

动态 `database_query` 工具涉及日期范围时，必须遵守 MCP Server 的数据库查询动态日期参数契约。模型只选择模板和业务参数，`today`、`month`、`month_start`、`month_end`、`trade_date`、`trade_date±N` 等日期值由 MCP Runtime 在执行前统一解析。

## 结构化结果契约

任何 DSL 执行结果都必须保留 step 级证据。

标准结构至少包含：

```json
{
  "success": true,
  "schemaVersion": "tool_execution_result.v1",
  "execution": {
    "steps": [
      {
        "index": 1,
        "type": "sql",
        "success": true,
        "durationMs": 12,
        "input": {
          "stepCode": "SESSION_STAT",
          "stepName": "Session统计",
          "stepType": "SQL",
          "required": false,
          "analysisHint": "分析当前连接数、活跃连接数、空闲连接数。"
        },
        "output": {
          "columns": ["STATE", "CNT"],
          "rows": [
            {"STATE": "ACTIVE", "CNT": 3}
          ],
          "rowCount": 1
        },
        "diagnostics": {
          "stepCode": "SESSION_STAT",
          "stepName": "Session统计",
          "rowCount": 1
        }
      }
    ]
  },
  "diagnostics": {
    "templateDsl": {
      "schemaVersion": "agent_runtime_template_dsl.v1",
      "templateCode": "DM_INSTANCE_STATUS",
      "stepCount": 4
    },
    "analysisPolicy": {
      "evidenceRequired": true
    }
  }
}
```

最终回答只能基于已返回的结构化结果做业务判断。只发现 DSL 模板但没有执行，不构成业务事实证据。

## 导入导出契约

导入 DSL 时必须执行：

1. JSON 语法校验。
2. `steps` 非空校验。
3. 每个 step 的 `command/sql/shell` 至少存在一个。
4. `templateType` 与目标注册表匹配校验。
5. SQL / shell 安全策略校验。
6. 参数 schema、风险等级、治理 metadata 校验。

导出 DSL 时必须：

1. 输出 canonical 字段。
2. 保留 `analysisPolicy`。
3. 保留 steps 的 `stepCode`、`stepName`、`stepType`、`order`、`required`、`timeoutSeconds`、`command`、`analysisHint`。
4. 不导出密钥、密码、host key、连接串明文、token、cookie 等敏感资产字段。

## 禁止事项

- 禁止把业务场景硬编码成某个模板名或执行路径。
- 禁止模型直接生成 raw SQL、raw shell、raw HTTP request 并交给执行工具。
- 禁止跳过资产发现和模板发现。
- 禁止 `sql_query_execute` 执行多步骤 DSL。
- 禁止模板发现结果只返回标题、描述而不返回 `templateDsl` 元数据。
- 禁止执行结果只返回自然语言摘要，不返回 step 级结构化证据。
- 禁止在某个 step 失败后丢弃失败事实。
- 禁止把 `analysisHint` 当作执行内容；它只给模型做结果分析和模板 review。

## 最小合规检查

每次修改 DSL 相关实现，至少覆盖以下检查：

1. DSL 模板导入校验。
2. DSL 模板导出 canonical 字段。
3. DSL 的 `stepName` / `analysisHint` 能进入 Lucene 或等价检索索引。
4. 模板发现结果能返回 `templateDsl` 元数据。
5. `linux_command_execute` 能按 steps 执行并返回 step 结构。
6. `sql_script_execute` 能按 steps 执行并返回每个 SQL step 的结构化结果。
7. `sql_query_execute` 遇到多 SQL 或 DSL 多步骤时不应吞掉或隐式只执行第一步。
8. 最终总结只消费执行结果，不把模板发现当成执行证据。
