# 数据库查询流程执行规范

本文档定义 ChatChat 中“新增数据库查询”的多 SQL 编排、配置校验、执行调度、参数传递、结果组织、Agent Runtime 返回和审计规范。

该规范覆盖以下模块：

- MCP 管理页面中的数据库查询流程配置。
- `chatchat-mcp-server` 中的模板保存、校验、数据源绑定和调用适配。
- `chatchat-tools` 中的 SQL DAG 校验、拓扑调度和参数解析。
- `sql_query_execute` 返回给 Agent Runtime 和大模型的结构化结果。
- 命令执行审计中的 SQL 节点审计记录。

本规范的核心原则是：

> 用户定义业务执行语义，流程引擎负责确定性执行，大模型只负责理解结果和进行业务判断。

大模型不得临时决定 SQL 的执行顺序、依赖关系或参数传递方式。

## 协议版本

当前流程定义版本：

```text
database_query_workflow_definition.v1
```

当前执行结果版本：

```text
database_query_workflow_result.v1
```

Agent Runtime 外层结果继续遵循统一工具结果和执行图契约。

## 适用范围

本规范适用于：

- 一个数据库查询模板包含多条只读 SQL。
- SQL 之间存在串行、并行或多节点汇聚关系。
- 下游 SQL 需要引用用户输入、系统上下文、固定值或上游查询结果。
- 每个结果集具有独立业务含义、数据粒度和模型使用方式。
- 查询流程需要完整、可追踪、可审计地返回给大模型。

当前版本不包含：

- 任意脚本表达式。
- 通用 JavaScript、Groovy、Python 执行节点。
- HTTP、MCP、SSH 等非 SQL 节点。
- 任意循环、动态回跳或递归子流程。
- 由大模型动态修改流程依赖。

条件分支、重试、聚合节点、子流程和跨工具工作流属于后续扩展范围。

## 总体模型

一个数据库查询流程分为三层：

```text
数据库查询流程
├── 集合层：业务工具定义
│   ├── 工具名称与显示名称
│   ├── 工具描述
│   ├── 功能实现步骤
│   ├── 输入参数 Schema
│   └── 数据源与运行策略
│
├── 编排层：SQL 依赖图
│   ├── SQL 节点
│   ├── 前置依赖
│   ├── 参数映射
│   ├── 失败策略
│   ├── 空结果策略
│   └── 结果集语义
│
└── 结果层：模型可理解的执行结果
    ├── 工具语义
    ├── 执行摘要
    ├── 真实执行层级
    ├── 节点状态与依赖
    ├── 结果集语义
    └── 实际查询数据
```

## 集合层契约

数据库查询流程必须维护以下集合层字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `toolName` | string | 是 | MCP 模板稳定标识，只允许字母、数字、下划线和连字符。 |
| `title` | string | 是 | 面向业务用户的显示名称。 |
| `datasourceId` | string | 是 | 已维护且启用的数据库资产 ID。 |
| `description` | string | 是 | 工具用途、适用场景、业务对象和数据口径。 |
| `implementationSteps` | string | 是 | 集合层业务实现步骤，说明整个结果集合如何形成。 |
| `inputSchema` | object | 是 | 模型调用参数 Schema。 |
| `sqlSteps` | array | 是 | 至少包含一个启用的 SQL 节点。 |
| `maxRows` | integer | 是 | 模板默认最大返回行数。 |
| `timeoutSeconds` | integer | 是 | 模板默认单节点超时。 |
| `enabled` | boolean | 是 | 是否允许发现和执行。 |

`description` 和 `implementationSteps` 是模型理解集合业务语义的必要字段，不能只维护 SQL 而省略这两项。

## SQL 节点契约

每个 SQL 节点使用稳定的 `sqlCode` 标识。节点名称可修改，节点编码一旦被其他节点依赖后应保持稳定。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sqlCode` | string | 是 | 流程内唯一节点编码。 |
| `sqlName` | string | 是 | 节点业务名称。 |
| `sqlDescription` | string | 是 | SQL 用途及结果集说明。 |
| `sqlContent` | string | 是 | 只读 SQL。 |
| `executionOrder` | integer | 是 | 页面展示顺序和同层稳定排序依据，不替代依赖关系。 |
| `workflowEnabled` | boolean | 是 | 是否使用依赖图执行。 |
| `dependencies` | string[] | 否 | 前置节点编码列表。 |
| `parameterMappings` | object[] | 否 | 当前节点的参数来源定义。 |
| `parameters` | object | 否 | 当前节点固定参数。 |
| `timeoutSeconds` | integer | 否 | 节点超时，未设置时继承集合层。 |
| `maxResultRows` | integer | 否 | 节点最大返回行数，未设置时继承集合层。 |
| `failureStrategy` | string | 是 | `STOP` 或 `CONTINUE`。 |
| `emptyResultStrategy` | string | 是 | `CONTINUE`、`SKIP_DEPENDENTS` 或 `STOP`。 |
| `resultSemantic` | object | 是 | 结果集业务语义。 |
| `returnToModel` | boolean | 是 | 是否把节点数据直接返回给模型。 |
| `enabled` | boolean | 是 | 是否参与流程执行。 |

## 依赖图规范

SQL 流程必须是有向无环图 DAG。

支持以下结构：

```text
串行：A -> B -> C

并行：A -> B
       A -> C

汇聚：B -> D
       C -> D
```

依赖关系表示：

```json
{
  "sqlCode": "CUSTOMER_ASSET",
  "dependencies": ["CUSTOMER_BASE"]
}
```

执行器必须在保存和执行前校验：

- 节点编码非空且流程内唯一。
- 依赖节点存在并且已启用。
- 节点不能依赖自身。
- 依赖图不能包含环。
- 上游结果参数的 `sourceNode` 必须同时出现在当前节点的 `dependencies` 中。
- 停用被依赖节点时，保存必须失败并指出具体依赖关系。

`executionOrder` 只用于页面展示、同一执行层内的确定性排序和旧模板兼容。真实执行顺序必须由依赖图的拓扑排序结果决定。

## 兼容模式

为避免旧版多 SQL 模板升级后从串行静默变成并行，执行器必须区分两种模式。

### 顺序兼容模式

当所有节点均未启用 `workflowEnabled` 时：

- 按 `executionOrder` 串行执行。
- 系统内部把相邻节点转换为隐式依赖链。
- 返回 `executionMode=SEQUENTIAL`。
- 返回旧模式标识 `database_query_multi_sql`。

### 依赖编排模式

当流程启用依赖编排时：

- 严格按照显式 `dependencies` 执行。
- 无依赖节点属于第一个执行层。
- 同一层节点允许并行执行。
- 返回 `executionMode=DEPENDENCY_GRAPH`。
- 返回模式标识 `database_query_workflow`。

用户必须在页面主动开启“依赖编排”，系统不能通过 SQL 数量猜测执行模式。

## 参数来源规范

每个 SQL 节点可以独立配置参数。参数来源分为四类。

### 用户输入

```json
{
  "parameter": "customerNo",
  "sourceType": "USER_INPUT",
  "sourceKey": "customerNo",
  "required": true
}
```

参数从工具调用的 `parameters` 中读取。`sourceKey` 为空时默认使用 `parameter`。

### 系统上下文

```json
{
  "parameter": "operatorId",
  "sourceType": "SYSTEM_CONTEXT",
  "sourceKey": "currentUser",
  "required": true
}
```

系统上下文由运行时注入，当前可包含调用用户、数据源 ID 和数据源名称。不得在流程定义中硬编码具体用户名。

### 上游结果

```json
{
  "parameter": "customerNo",
  "sourceType": "UPSTREAM_RESULT",
  "sourceNode": "CUSTOMER_BASE",
  "sourceExpression": "$.rows[0].customer_no",
  "required": true
}
```

上游结果只允许使用受限路径读取，不允许执行脚本。当前路径支持：

- Map 字段访问，例如 `$.rows`。
- 数组下标访问，例如 `$.rows[0]`。
- 组合访问，例如 `$.rows[0].customer_no`。

禁止 `${}` 文本替换、反射调用、函数调用和任意表达式执行。

### 固定值

```json
{
  "parameter": "status",
  "sourceType": "STATIC",
  "defaultValue": "ACTIVE",
  "required": true
}
```

固定值只能作为预编译参数参与 SQL 绑定，不能拼接进 SQL 文本。

### 参数解析顺序

节点参数按照以下顺序合并：

```text
集合层用户输入
-> 节点固定参数
-> 节点 parameterMappings 解析结果
-> 动态日期参数补充
-> JDBC 预编译绑定
```

后定义的显式节点映射可以覆盖集合层同名参数。

必填参数无法解析时，该节点必须以 `FAILED` 结束，错误中必须包含节点编码和参数名。系统不得使用无业务依据的默认值继续执行。

## 调度算法

流程引擎执行步骤如下：

```text
1. 读取所有启用节点
2. 校验节点编码和依赖关系
3. 检测循环依赖
4. 执行拓扑分层
5. 找出当前所有可执行节点
6. 解析每个节点的独立参数
7. 在并行度限制内执行同层节点
8. 保存节点状态和结果
9. 应用失败策略与空结果策略
10. 调度下一执行层
11. 汇总流程状态
12. 生成 Agent Runtime 结构化结果和执行图
13. 写入流程审计与节点命令审计
```

并行度使用配置项控制：

```yaml
chatchat:
  tools:
    database-query:
      workflow:
        max-parallelism: ${CHATCHAT_DATABASE_QUERY_WORKFLOW_MAX_PARALLELISM:4}
```

业务代码不得根据具体模板名称或 SQL 数量硬编码并行度。

## 节点状态

节点状态包括：

| 状态 | 说明 |
| --- | --- |
| `SUCCESS` | SQL 执行成功。 |
| `FAILED` | 参数解析或 SQL 执行失败。 |
| `SKIPPED` | 因流程停止、依赖失败或上游空结果策略而跳过。 |

流程状态包括：

| 状态 | 说明 |
| --- | --- |
| `SUCCESS` | 所有应执行节点成功。 |
| `PARTIAL_SUCCESS` | 存在失败或跳过节点，但未触发流程终止。 |
| `FAILED` | 节点失败或空结果触发终止策略。 |

## 失败策略

### STOP

节点失败后：

- 当前已经提交的同层节点允许结束。
- 后续执行层不再启动。
- 未执行节点标记为 `SKIPPED`。
- 流程状态为 `FAILED`。

### CONTINUE

节点失败后：

- 与失败节点无依赖关系的分支可以继续。
- 依赖该失败节点的下游节点不得执行，状态为 `SKIPPED`。
- 流程状态为 `PARTIAL_SUCCESS`，除非其他节点触发 `STOP`。

## 空结果策略

### CONTINUE

空结果被视为成功结果，下游继续执行。下游参数若无法从空结果解析，则按照参数必填规则失败。

### SKIP_DEPENDENTS

当前节点成功，但直接或间接依赖该节点的下游分支不得执行。

### STOP

当前节点返回空结果后终止后续执行，流程状态为 `FAILED`。

空结果不能自动解释为数值零。其业务含义必须由 `resultSemantic.emptyMeaning` 声明。

## 结果集语义

每个返回给模型的 SQL 节点必须维护结果集语义：

```json
{
  "resultSetName": "customer_asset",
  "businessEntity": "客户",
  "primaryKeys": ["customer_no"],
  "timeField": "data_date",
  "dataGranularity": "客户-日期",
  "unitDescriptions": {
    "total_asset": "元",
    "asset_ratio": "%"
  },
  "emptyMeaning": "未查询到客户资产记录，不代表客户资产为 0",
  "modelUsage": "用于判断客户资产规模、资产结构和经营价值"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `resultSetName` | 稳定结果集名称。 |
| `businessEntity` | 该结果描述的业务实体。 |
| `primaryKeys` | 与其他结果集关联的业务主键。 |
| `timeField` | 数据时间字段。 |
| `dataGranularity` | 一行数据代表的业务粒度。 |
| `unitDescriptions` | 数值字段的单位说明。 |
| `emptyMeaning` | 空结果的业务解释。 |
| `modelUsage` | 模型应如何使用该结果集。 |

`returnToModel=false` 的节点仍参与执行和上游参数传递，但其原始结果数据不能出现在模型可见的 `steps`、`resultSets` 和 `results` 中。运行时可以返回该节点的状态摘要，用于解释执行图。

## 面向模型的返回契约

工作流结果必须以结构化内容返回，文本内容只提供简短摘要，不得通过截断 JSON 字符串代替结构化数据。

完整数据放在 MCP `structuredContent.data` 中，并使用以下结构：

```json
{
  "mode": "database_query_workflow",
  "schemaVersion": "database_query_workflow_result.v1",
  "tool": {
    "name": "customer_business_analysis",
    "title": "客户综合经营分析",
    "description": "查询客户基础、资产和风险信息。",
    "implementationSteps": "1. 查询客户基础信息。\n2. 并行查询资产与风险。\n3. 汇总结果。"
  },
  "execution": {
    "executionNo": "generated-execution-id",
    "status": "SUCCESS",
    "executionMode": "DEPENDENCY_GRAPH",
    "executionLevels": [
      ["CUSTOMER_BASE"],
      ["CUSTOMER_ASSET", "CUSTOMER_RISK"]
    ],
    "executedNodeCount": 3,
    "successNodeCount": 3,
    "failedNodeCount": 0,
    "skippedNodeCount": 0,
    "durationMs": 120
  },
  "steps": [
    {
      "step": 1,
      "executionLevel": 1,
      "nodeCode": "CUSTOMER_BASE",
      "nodeName": "查询客户基础信息",
      "dependencies": [],
      "parameterMappings": [],
      "resultSetDescription": "客户身份和归属信息",
      "resultSemantic": {
        "resultSetName": "customer_base",
        "businessEntity": "客户",
        "primaryKeys": ["customer_no"]
      },
      "status": "SUCCESS",
      "rowCount": 1,
      "columns": ["customer_no", "customer_name"],
      "rows": [
        {
          "customer_no": "100001",
          "customer_name": "张三"
        }
      ]
    }
  ],
  "nodeExecutions": [
    {
      "nodeCode": "CUSTOMER_BASE",
      "dependencies": [],
      "executionOrder": 1,
      "executionLevel": 1,
      "status": "SUCCESS",
      "rowCount": 1,
      "durationMs": 20
    }
  ],
  "modelGuidance": {
    "analysisObjective": "根据客户基础、资产和风险数据判断客户价值和风险特征。",
    "resultRelationships": [
      "CUSTOMER_ASSET depends on CUSTOMER_BASE",
      "CUSTOMER_RISK depends on CUSTOMER_BASE"
    ],
    "cautions": [
      "空结果不代表数值为 0"
    ]
  }
}
```

### 返回要求

- `tool` 必须包含集合层描述和功能实现步骤。
- `execution.executionLevels` 必须反映真实拓扑层级。
- `steps` 必须按真实执行顺序组织。
- 每个步骤必须包含节点编码、名称、依赖、结果说明、结果语义、状态和数据。
- `nodeExecutions` 必须包含所有节点，包括不返回原始数据给模型的节点。
- `executionGraph.nodes` 必须对应节点执行状态。
- `executionGraph.edges` 必须对应真实 `dependencies`，不能始终返回空边集合。
- SQL 数据仍受节点 `maxResultRows` 和全局安全上限约束。
- 不允许对已经生成的结构化结果再次按字符长度截断。
- 若数据达到行数上限，必须通过 `possiblyTruncated` 明确声明。

## 模板发现契约

`business_query_template_search` 返回模板时，应包含：

- 工具名称和工具描述。
- 集合层 `implementationSteps`。
- 工作流模式和节点数量。
- SQL 节点编码、名称和业务说明。
- 节点依赖关系。
- 参数映射契约。
- 失败策略和空结果策略。
- 结果集语义。
- `sql_query_execute` 的调用绑定和必填参数。

模板发现阶段不得返回：

- 原始 SQL 文本。
- JDBC URL。
- 数据库账号或密码。
- 可绕过资产路由的物理连接信息。

模型通过模板语义选择流程，通过 `templateId` 调用 `sql_query_execute`，不能自行构造或猜测模板名称。

## SQL 安全规范

所有节点必须遵守以下限制：

- 只允许 `SELECT`、`WITH`、`SHOW`、`DESCRIBE`、`DESC` 或 `EXPLAIN` 等已批准的只读语句。
- 禁止 DDL、DML、权限修改、事务控制和存储过程调用。
- 禁止在单个节点中隐藏多条 SQL；多条查询必须拆分为明确节点。
- 参数必须使用 JDBC 预编译绑定。
- 禁止 `${}` 直接文本替换业务参数。
- 每个节点必须限制超时和最大返回行数。
- 流程必须限制最大并行度。
- 节点继承绑定数据源的数据库、Schema、表和字段权限。
- 返回结果继续应用字段脱敏与连接信息过滤。
- 流程定义、节点执行和最终结果必须可审计。

动态日期占位符由受控的日期参数服务解析，不属于任意文本替换能力。

## 审计规范

审计分为两层。

### 流程调用审计

记录：

- 数据库查询模板 ID 和名称。
- 调用用户与租户上下文。
- 数据源资产 ID。
- 输入参数摘要。
- 流程最终状态和总耗时。
- 结构化结果摘要或错误信息。

多 SQL 流程的流程调用记录不应把第一条 SQL 错误地当成整个流程的唯一执行命令。

### SQL 节点命令审计

每个实际执行节点单独记录：

- 用户名称。
- 执行数据源名称。
- 数据库查询模板。
- 节点编码和节点名称。
- 执行命令短文。
- 执行结果。
- 执行耗时。
- 执行时间。

未执行的 `SKIPPED` 节点不生成“已执行命令”记录，但其状态保留在流程调用审计中。

命令审计只保存规范化短文用于列表展示，完整请求和响应按审计详情策略保存。页面列表必须保持单行省略显示，不得为展示完整 SQL 拉宽整个表格。

## 页面交互规范

“新增数据库查询”页面必须提供：

- 依赖编排开关。
- 执行依赖图预览。
- SQL 节点新增、复制、删除和展示顺序调整。
- 节点编码、节点名称和 SQL 内容编辑。
- 前置依赖多选。
- 节点参数来源可视化配置。
- 失败策略和空结果策略选择。
- 超时和最大返回行数设置。
- 结果集语义可视化配置。
- 是否返回节点结果给模型的开关。

提交前必须校验：

- 集合层所有必填字段。
- SQL 节点编码、名称、结果说明和 SQL 内容。
- 节点编码重复。
- 依赖节点不存在、自依赖和循环依赖。
- 上游参数没有来源节点。
- 上游参数来源没有配置为前置依赖。
- 参数映射缺少参数名。

前端校验用于即时提示，后端必须执行同等或更严格的最终校验，不能信任前端结果。

## 示例流程

```json
[
  {
    "sqlCode": "CUSTOMER_BASE",
    "sqlName": "查询客户基础信息",
    "sqlDescription": "返回客户编号、名称、等级和归属机构",
    "sqlContent": "SELECT customer_no, customer_name, customer_level FROM customer WHERE customer_no = :customerNo",
    "executionOrder": 1,
    "workflowEnabled": true,
    "dependencies": [],
    "parameterMappings": [
      {
        "parameter": "customerNo",
        "sourceType": "USER_INPUT",
        "sourceKey": "customerNo",
        "required": true
      }
    ],
    "failureStrategy": "STOP",
    "emptyResultStrategy": "STOP",
    "resultSemantic": {
      "resultSetName": "customer_base",
      "businessEntity": "客户",
      "primaryKeys": ["customer_no"],
      "dataGranularity": "客户",
      "emptyMeaning": "客户不存在或当前用户无权访问该客户",
      "modelUsage": "作为其他结果集的客户身份基准"
    },
    "returnToModel": true,
    "enabled": true
  },
  {
    "sqlCode": "CUSTOMER_ASSET",
    "sqlName": "查询客户资产",
    "sqlDescription": "返回客户资产总额和资产结构",
    "sqlContent": "SELECT customer_no, total_asset FROM customer_asset WHERE customer_no = :customerNo",
    "executionOrder": 2,
    "workflowEnabled": true,
    "dependencies": ["CUSTOMER_BASE"],
    "parameterMappings": [
      {
        "parameter": "customerNo",
        "sourceType": "UPSTREAM_RESULT",
        "sourceNode": "CUSTOMER_BASE",
        "sourceExpression": "$.rows[0].customer_no",
        "required": true
      }
    ],
    "failureStrategy": "CONTINUE",
    "emptyResultStrategy": "CONTINUE",
    "resultSemantic": {
      "resultSetName": "customer_asset",
      "businessEntity": "客户资产",
      "primaryKeys": ["customer_no"],
      "dataGranularity": "客户",
      "unitDescriptions": {
        "total_asset": "元"
      },
      "emptyMeaning": "未查询到资产记录，不代表客户资产为 0",
      "modelUsage": "用于判断客户资产规模"
    },
    "returnToModel": true,
    "enabled": true
  }
]
```

## 验收标准

实现和后续修改必须至少验证：

1. 单节点流程正常执行。
2. 旧多 SQL 模板仍按顺序串行执行。
3. 一个根节点连接两个下游节点时，下游属于同一执行层。
4. 多节点汇聚必须等待全部依赖成功。
5. 缺失依赖、自依赖和循环依赖不能保存。
6. 上游结果能够通过受限路径绑定到下游参数。
7. 必填参数无法解析时节点失败且错误可定位。
8. `STOP`、`CONTINUE` 和三种空结果策略符合规范。
9. `returnToModel=false` 不泄漏节点原始数据。
10. 结构化结果包含工具语义、执行层级、节点结果和真实执行图边。
11. 每个实际 SQL 节点生成独立命令审计。
12. 前端生产构建和后端编译通过。

## 后续扩展约束

新增条件分支、节点重试、聚合节点、缓存、分页或子流程时，必须：

- 升级流程定义或结果协议版本。
- 保持旧版本执行语义不变。
- 明确新增状态和审计行为。
- 为 Agent Runtime 增加对应执行图语义。
- 不允许通过硬编码模板名实现特殊分支。
- 不允许引入任意脚本作为参数或条件表达式。

当流程扩展到 HTTP、MCP、SSH 等节点类型时，应新建通用数据查询工作流协议，而不是继续把非 SQL 逻辑塞入数据库查询流程。

