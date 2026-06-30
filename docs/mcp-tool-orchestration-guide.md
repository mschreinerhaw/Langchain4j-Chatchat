# MCP 工具编排使用说明

本文档说明当前 ChatChat MCP Server 已有工具族的职责边界、调用顺序和编排规则。目标是让 planner、runtime 和人工调试时都遵循同一套工具协议，避免模型跳过资产发现、绕过模板发现、自己拼 SQL/命令/API 请求，或在 final answer 中使用没有证据的内容。

## 总体原则

1. 先发现资产；SQL 表分析先检索本地元数据；需要执行时再发现模板并执行模板。
2. `tenantId`、`userId`、`requestId`、`conversationId` 必须随 MCP 请求透传；缺少 tenant 上下文时应 fail-fast，不允许进入执行工具。
3. 模型只能选择 MCP 返回的工具名、资产、模板和参数 schema，不能发明 `templateId`、SQL、命令、URL、endpointId、hostId、datasourceId 等具体目标字段。
4. `final_answer` 只能由最终汇总步骤生成；reviewer 只能写 `review_answer`、`review_status`、`review_reason` 等审计字段。
5. 工具结果里的结构化字段优先级高于 text 摘要；最终答案必须引用结构化证据。
6. execution 工具只消费 discovery/metadata/template 阶段产出的确定性字段，不消费模型自由生成的执行文本。
7. `availableTools` 是请求级工具边界。planner、rewriter、workflow guard、runtime validator 都必须以本次 `availableTools` 为准；工具即使在全局 registry 注册，只要本次不可见，也不能被规划或执行。

## 依赖类型协议

MCP 工具声明和 InterpretationPlan 需要区分两类依赖：

- **必选依赖**：必须先执行并成功，后续工具才能执行。例如执行类工具必须依赖资产发现、模板发现或明确的本地元数据绑定。
- **可选依赖**：只在当前用户问题确实需要时执行。例如锁等待、执行计划、慢查询、主机诊断、文档补充解释等，不应被无条件塞进固定链路。

协议字段约定：

- `depends_on` 只表示硬 DAG 顺序。只要写入 `depends_on`，runtime 就会把它当成必须完成的前置步骤。
- `plan.dependency_contracts` 表示依赖语义，推荐结构如下：

```json
{
  "from": 2,
  "to": 4,
  "required": true,
  "condition": "sql_query_execute needs schema/table binding from sql_metadata_search",
  "reason": "bind databaseName/schemaName/tableName before executing a template",
  "on_failure": "stop"
}
```

规划规则：

- `required=true` 的 dependency contract 必须同时出现在目标 step 的 `depends_on` 中。
- `required=false` 的 dependency contract 必须带 `condition` 或 `reason`，由模型判断当前问题是否需要；不需要时不要生成对应工具 step。
- 可选依赖一旦被模型选择为本次需要执行，也应该生成明确 step，并按实际数据流添加 `depends_on`、`bindings` 或 `edge_contracts`。
- 不允许把可选依赖当成固定必跑链路，否则会导致 MCP workflow 被无意义步骤拖慢或因可选步骤失败而误判整体失败。

## 工具族总览

| 工具/工具族 | 类型 | 是否执行外部操作 | 主要用途 | 下游 |
| --- | --- | --- | --- | --- |
| `api_asset_query` | 资产发现 | 否 | 查询 API 服务资产元数据和路由线索 | `api_template_query` |
| `ssh_asset_query` | 资产发现 | 否 | 查询 SSH 主机资产元数据和路由线索 | `ssh_template_query` |
| `sql_datasource_asset_query` | 资产发现 | 否 | 查询 SQL 数据源资产元数据和路由线索 | `sql_metadata_search` / `sql_datasource_template_query` |
| `http_endpoint_asset_query` | 资产发现 | 否 | 查询 HTTP endpoint 资产元数据和路由线索 | `http_endpoint_template_query` |
| `api_template_query` | 模板发现 | 否 | 查询 API 服务模板元数据和参数 schema | API 执行工具族 |
| `ssh_template_query` | 模板发现 | 否 | 查询 SSH 命令模板元数据和参数 schema | `linux_command_execute` |
| `sql_datasource_template_query` | 模板发现 | 否 | 查询 SQL 数据源查询模板元数据和参数 schema | `sql_query_execute` |
| `http_endpoint_template_query` | 模板发现 | 否 | 查询 HTTP endpoint 请求模板元数据和参数 schema | `http_request_execute` |
| `database_query_template_query` | 模板发现 | 否 | 查询业务数据库查询模板 | 动态 `database_query` 工具 |
| `sql_metadata_search` | SQL 元数据检索 | 否 | 基于本地 RocksDB/Lucene 索引检索库、表、列、注释和执行绑定 | `sql_datasource_template_query` / `sql_query_execute` |
| `sql_query_execute` | SQL 执行网关 | 是 | 执行已授权 SQL 模板 | finalizer |
| `linux_command_execute` | SSH 执行网关 | 是 | 在逻辑主机上执行已授权命令模板 | finalizer |
| `http_request_execute` | HTTP 执行网关 | 是 | 调用已授权 HTTP endpoint 模板 | finalizer |
| 动态 `database_query` 工具 | 业务查询执行 | 是 | 执行业务预置数据库查询 | finalizer |
| 动态通知工具 | 通知执行 | 是 | 发送邮件、短信、企微、钉钉等通知 | finalizer |
| `document_search` | 文档检索 | 否/读 | 查询本地文档证据片段 | finalizer / Result Fusion |
| Web 工具族 | Web 检索/抓取 | 是/读 | 互联网搜索、站内搜索、页面分析、网页抓取 | finalizer |

## 标准编排骨架

任何涉及受控资产的任务，默认使用以下骨架：

```text
用户问题
  ↓
asset_query
  ↓
sql_metadata_search（SQL 表分析时）
  ↓
template_query
  ↓
execute
  ↓
review / semantic gate
  ↓
final_answer
```

说明：

- `template_query` 只在本次 `availableTools` 包含对应 typed template discovery 工具时出现。
- 如果 `availableTools` 不包含 `sql_datasource_template_query`，不得补造模板发现步骤；可以使用已观测到的模板契约继续执行，或在 final answer 中说明缺少模板发现能力。
- 对于 SQL 表分析，`sql_metadata_search` 是表定位和字段依据的首选步骤，不应在对话过程中频繁查询 `information_schema`。

最小 DAG 形态：

```json
[
  {
    "id": 1,
    "tool": "<domain>_asset_query",
    "input": {
      "filters": {
        "assetName": "用户明确提到的逻辑资产名",
        "env": "DEV"
      },
      "trace": {
        "plannerVersion": "v1"
      },
      "tenantId": "admin",
      "userId": "admin"
    }
  },
  {
    "id": 2,
    "tool": "<domain>_template_query",
    "dependsOn": [1],
    "input": {
      "filters": {
        "assetName": "{{step1.assets[0].asset.name}}",
        "intent": "用户目标的双语/结构化意图"
      },
      "trace": {
        "sourceStepId": 1
      },
      "tenantId": "admin",
      "userId": "admin"
    }
  },
  {
    "id": 3,
    "tool": "<domain>_execute",
    "dependsOn": [2],
    "input": {
      "template": "{{step2.templates[0].templateId}}",
      "parameters": {
        "only": "fields declared by step2.templates[0].parameterSchema"
      },
      "executionContext": {
        "assetName": "{{step1.assets[0].asset.name}}"
      },
      "tenantId": "admin",
      "userId": "admin"
    }
  }
]
```

## 资产发现工具

### 适用场景

当用户提到数据库、主机、HTTP 服务、API 服务、环境、集群、服务名、业务系统等逻辑资产时，必须先用对应 asset 工具。

| 目标 | 工具 |
| --- | --- |
| SQL 数据源 | `sql_datasource_asset_query` |
| SSH 主机 | `ssh_asset_query` |
| HTTP endpoint | `http_endpoint_asset_query` |
| API 服务 | `api_asset_query` |

### 输入要点

使用逻辑过滤字段：

```json
{
  "filters": {
    "assetName": "248测试数据库",
    "env": "DEV",
    "service": "order-service",
    "labels": ["mysql"]
  },
  "trace": {
    "plannerVersion": "v1",
    "source": "interpretation_plan"
  },
  "limit": 5,
  "tenantId": "admin",
  "userId": "admin"
}
```

### 禁止事项

不能传这些具体目标字段：

```text
hostId, host, hostname, ip, ipAddress, address,
datasourceId, jdbcUrl, url, connectionString,
endpointId, uri
```

asset 工具返回的是脱敏的候选资产和路由线索，不返回 JDBC URL、真实主机、真实 endpoint URL。

## 模板发现工具

### 适用场景

当任务需要执行 SQL、SSH 命令、HTTP 请求、API 请求或业务数据库查询时，必须先查询模板。模型不能直接构造执行内容。

| 目标 | 工具 |
| --- | --- |
| SQL 数据源查询模板 | `sql_datasource_template_query` |
| SSH 命令模板 | `ssh_template_query` |
| HTTP endpoint 请求模板 | `http_endpoint_template_query` |
| API 服务模板 | `api_template_query` |
| 业务数据库查询模板 | `database_query_template_query` |

### 输入要点

`filters.intent` 要描述能力，不要塞执行语句：

```json
{
  "filters": {
    "assetName": "248测试数据库",
    "env": "DEV",
    "intent": "table metadata, indexes, row count, lock waits, active transactions",
    "bilingualIntent": [
      "表结构",
      "索引",
      "数据量",
      "锁等待",
      "active transactions",
      "table metadata",
      "indexes"
    ]
  },
  "trace": {
    "sourceStepId": 1
  },
  "limit": 10,
  "tenantId": "admin",
  "userId": "admin"
}
```

### 输出消费规则

只允许下游使用：

```text
templates[].templateId
templates[].parameterSchema
templates[].databaseType / targetKind / intentSignals
templates[].routing
```

不允许使用不存在的模板名。`sql_query_execute.templateId`、`linux_command_execute.template`、`http_request_execute.template` 必须来自对应 `template_query.templates[].templateId`，或来自本次请求已经观测到的授权模板契约。没有模板发现工具且没有已观测模板契约时，应停止并说明缺少模板发现能力，不能发明模板名。

## `sql_metadata_search`

### 定位

`sql_metadata_search` 是 SQL 表分析前的本地元数据检索工具。它不访问业务数据库执行 SQL，而是查询已经刷新到本地的元数据索引：

```text
数据库资产维护页
  ↓
元数据刷新
  ↓
RocksDB schema cache
  ↓
Lucene searchable index
  ↓
sql_metadata_search
```

它用于替代已经删除的 `sql_query_plan`，解决“表名已知但库/schema 不确定”“需要字段名、类型、注释作为结构依据”“需要把 SQL 执行参数绑定到正确 schema/table”等问题。

### 适用场景

1. 用户提供表名、`database.table`、`schema.table` 或 SQL Server 风格 `database.schema.table`。
2. 用户提供表注释、数据库注释、业务中文名称，需要基于注释召回真实表名。
3. SQL 模板执行前需要确定 `schemaName/databaseName/tableName`。
4. final answer 需要展示结构化字段依据，包括列名、类型、注释、主键/索引提示。

### 正确位置

```text
sql_datasource_asset_query
  ↓
sql_metadata_search
  ↓
sql_datasource_template_query（仅当本次 availableTools 包含该工具）
  ↓
sql_query_execute
```

如果本次 `availableTools` 不包含 `sql_datasource_template_query`，planner/rewriter 不得补造该步骤；只能使用已观测到的模板契约或停止在 final_answer 中说明缺少模板发现能力。

### 输入示例

```json
{
  "query": "livebos.os_historystep / 表注释 / 模块名称",
  "tableName": "os_historystep",
  "database": "livebos",
  "schema": "livebos",
  "assetName": "248测试数据库",
  "env": "DEV",
  "databaseType": "mysql",
  "includeColumns": true,
  "limit": 10,
  "tenantId": "admin",
  "userId": "admin"
}
```

SQL Server 三段式表名必须按对象解析：

```text
user.dbo.table
  database = user
  schema   = dbo
  table    = table
```

MySQL / PostgreSQL / Oracle 两段式按 `schema.table` 或 `database.table` 解析，最终以工具返回的 `sqlExecutionBinding` 为准。

### 输出如何用

重点消费：

```text
results[].location.datasourceId
results[].location.assetName
results[].location.database
results[].location.schema
results[].location.table
results[].columns[].name
results[].columns[].type
results[].columns[].comment
results[].sqlExecutionBinding
```

后续 `sql_query_execute` 应优先从 `results[].sqlExecutionBinding` 绑定参数，例如：

```json
{
  "parameters": {
    "tableName": "os_historystep",
    "schemaName": "livebos",
    "databaseName": "livebos"
  },
  "executionContext": {
    "assetName": "248测试数据库",
    "env": "DEV"
  }
}
```

### 索引刷新要求

`sql_metadata_search` 依赖本地索引，不应该在每次对话中频繁查询 `information_schema`。数据库资产维护页必须配置元数据索引范围：

- JDBC 当前数据库
- 指定数据库/Schema，可多选或手动输入
- owner/user 范围，适用于 Oracle/SQL Server 等需要 owner/schema 的数据库
- 手动刷新或按刷新间隔自动刷新

刷新后数据进入 RocksDB 和 Lucene。RocksDB 是 schema cache，Lucene 是检索入口；二者都不是业务数据库的唯一真相源。

## `sql_query_execute`

### 定位

SQL 执行网关。用于执行已授权 SQL 模板，必须受 template registry 约束。

### 正确输入

```json
{
  "templateId": "MYSQL_TABLE_METADATA",
  "parameters": {
    "tableName": "t_ad_dict_entr_supn",
    "schemaName": "rdsm_ad"
  },
  "executionContext": {
    "assetName": "248测试数据库",
    "env": "DEV"
  },
  "timeoutSeconds": 30,
  "maxRows": 1000,
  "purpose": "分析表结构和诊断依据",
  "tenantId": "admin",
  "userId": "admin"
}
```

### 严格规则

1. `templateId` 必须来自 `sql_datasource_template_query.templates[].templateId` 或本次已经观测到的授权 SQL 模板契约。
2. `parameters` 只能包含该模板 `parameterSchema` 声明的字段。
3. 禁止传 `parameters.sql`、`rawSql`、`statement`、`query`。
4. 禁止把 SQL 片段塞进 `tableName`、`whereClause`、`filterExpression` 等参数。
5. 表元数据、索引、数据量、锁等待、事务信息是不同 scope，必须用对应模板分步执行，不能用 `MYSQL_INNODB_TRX` 代替表结构模板。

### 表分析推荐 DAG

```text
1. sql_datasource_asset_query
   定位 248测试数据库

2. sql_metadata_search
   从本地索引解析 t_ad_dict_entr_supn 所在 schema/database，并返回列名、类型和注释

3. sql_datasource_template_query
   查询 table location / table metadata 模板

4. sql_query_execute
   执行表结构模板，获取 columns / comments / keys

5. sql_datasource_template_query
   查询 index / table size / row count 模板

6. sql_query_execute
   执行索引、行数、大小模板

7. sql_datasource_template_query
   查询 lock / transaction 模板

8. sql_query_execute
   执行锁等待、事务模板

9. final_answer
   结构化展示元数据依据、索引依据、数据量依据、锁/事务依据
```

## `linux_command_execute`

### 定位

SSH 命令执行网关。只允许执行 `ssh_template_query` 返回的命令模板。

### 正确链路

```text
ssh_asset_query
  ↓
ssh_template_query
  ↓
linux_command_execute
```

### 输入示例

```json
{
  "template": "CHECK_DISK_USAGE",
  "parameters": {
    "path": "/data"
  },
  "executionContext": {
    "assetName": "app-server-dev",
    "env": "DEV"
  },
  "reason": "排查磁盘空间",
  "tenantId": "admin",
  "userId": "admin"
}
```

### 禁止事项

1. 不能传 `hostId`、`hostname`、`ip`。
2. 不能传自由 shell 命令。
3. 不能把命令片段塞进模板参数。
4. 高风险命令必须走确认策略。

## `http_request_execute`

### 定位

HTTP 请求执行网关。通过逻辑 endpoint 路由调用已授权模板。

### 正确链路

```text
http_endpoint_asset_query
  ↓
http_endpoint_template_query
  ↓
http_request_execute
```

### 输入示例

```json
{
  "template": "GET_SERVICE_HEALTH",
  "parameters": {
    "serviceName": "order-service"
  },
  "executionContext": {
    "assetName": "order-health-endpoint",
    "env": "DEV"
  },
  "reason": "检查服务健康状态",
  "tenantId": "admin",
  "userId": "admin"
}
```

### 禁止事项

不能传 `endpointId`、`url`、`uri`、`host`、`hostname`、`ipAddress`。HTTP 请求参数必须来自 `http_endpoint_template_query.templates[].parameterSchema`。

## API 工具编排

### 当前形态

API 不再按单个服务直接暴露 per-service MCP tool。必须使用：

```text
api_asset_query
  ↓
api_template_query
  ↓
API runtime / 后续执行层
```

### 使用规则

1. `api_asset_query` 只返回 API 服务资产、模板线索和脱敏信息。
2. `api_template_query` 返回 `templates[].templateId` 和参数 schema，但不返回 URL、headers、body template。
3. 模型不能构造 URL、header、body，也不能发明 API tool name。
4. 若没有匹配模板，应报告“无已授权 API 模板”，而不是回退到 HTTP raw request。

## 业务数据库查询工具

### 工具形态

`DatabaseQueryMcpToolPublisher` 会将启用的业务数据库查询配置注册为动态 MCP 工具。它们是面向业务场景的预置查询，不等同于通用 SQL 数据源。

推荐链路：

```text
database_query_template_query
  ↓
动态 database_query 工具
```

### 使用规则

1. 适合“查询某个业务指标/报表/固定业务列表”。
2. 不适合自由表结构诊断；表结构诊断走 `sql_datasource_*` + `sql_metadata_search` + `sql_query_execute`。
3. 参数必须符合动态工具 input schema。

## 文档检索和 RAG

### `document_search`

用于从本地文档库取有限 topK 证据片段，不是全库扫描。

适用场景：

1. SQL plan 的 `retrievalPlan.retrievalNeeded=true`。
2. 用户问题涉及业务术语、指标定义、制度文档、操作手册。
3. SQL 只能给出数据，不能解释指标含义。

推荐链路：

```text
sql_metadata_search
  ├─ sql_datasource_template_query → sql_query_execute
  └─ document_search（需要业务解释或制度依据时）
       ↓
result fusion / final_answer
```

使用规则：

1. 查询词要具体，包含业务对象、表名、指标名、系统名、日期或版本。
2. 空结果最多改写一次；仍为空就停止检索。
3. final answer 只能引用返回的 citations/snippets，不能补写未检索到的文档依据。

## Web 工具族

当前注册的 Web 工具有：

```text
web_search
finance_site_search
generic_web_site_search
crawl_url
web_crawler
web_page_analyze
site_intelligence_resolver
retrieve_financial_evidence
search_and_extract
```

### 通用互联网检索链路

```text
web_search
  ↓
web_page_analyze
  ↓
模型选择相关 URL
  ↓
generic_web_site_search 或 web_crawler/crawl_url
  ↓
final_answer
```

### 金融/公告检索链路

```text
web_search 或 finance_site_search
  ↓
retrieve_financial_evidence
  ↓
web_page_analyze / web_crawler
  ↓
final_answer
```

### 站点智能解析链路

```text
site_intelligence_resolver
  ↓
generic_web_site_search
  ↓
web_page_analyze / crawl_url
```

### 使用规则

1. `web_search` 只返回标题、URL、摘要，不负责抓取全文。
2. 抓取页面前应先用 `web_page_analyze` 或搜索结果筛选候选 URL。
3. 财报、公告、交易所披露优先用 `finance_site_search` / `retrieve_financial_evidence`。
4. 普通站点搜索用 `generic_web_site_search`。

## 通知工具

通知工具是动态注册的，每个启用的通知渠道会暴露一个 MCP tool。

### 输入

```json
{
  "receiver": "ops@example.com",
  "title": "告警标题",
  "content": "告警正文",
  "level": "WARNING",
  "sourceTaskId": "task-001",
  "tenantId": "admin",
  "userId": "admin"
}
```

### 使用规则

1. 通知属于外部副作用，必须在发送前展示 `receiver`、`title`、`content`、`level`。
2. 仅当用户明确要求通知或 workflow 明确要求告警时调用。
3. 不得把未验证的诊断猜测直接发送为告警事实。

## 典型任务编排

### 1. 分析数据库表

```text
sql_datasource_asset_query
  ↓
sql_metadata_search
  ↓
sql_datasource_template_query(TABLE_LOCATION / TABLE_METADATA)
  ↓
sql_query_execute
  ↓
sql_datasource_template_query(INDEX / ROW_COUNT / TABLE_SIZE)
  ↓
sql_query_execute
  ↓
sql_datasource_template_query(LOCK / TRANSACTION)
  ↓
sql_query_execute
  ↓
final_answer
```

最终答案必须结构化展示：

```text
表定位依据
字段元数据
主键/索引
行数/大小
锁等待/事务
无法获取的项目及原因
```

### 2. 排查服务器问题

```text
ssh_asset_query
  ↓
ssh_template_query
  ↓
linux_command_execute
  ↓
final_answer
```

常见意图映射：

| 用户意图 | 模板方向 |
| --- | --- |
| CPU 高 | cpu/top/process 模板 |
| 内存高 | memory/free/process 模板 |
| 磁盘满 | disk/du/df 模板 |
| 网络异常 | network/port/connectivity 模板 |
| 日志排查 | log tail/search 模板 |

### 3. 调用内部 HTTP/API 能力

HTTP endpoint：

```text
http_endpoint_asset_query
  ↓
http_endpoint_template_query
  ↓
http_request_execute
  ↓
final_answer
```

API 服务：

```text
api_asset_query
  ↓
api_template_query
  ↓
API runtime / authorized executor
  ↓
final_answer
```

### 4. SQL + 文档融合回答

```text
sql_datasource_asset_query
  ↓
sql_metadata_search
  ├─ sql_datasource_template_query → sql_query_execute
  └─ document_search
       ↓
final_answer
```

适合“指标怎么算”“这张表字段业务含义是什么”“结合制度解释异常”等问题。

### 5. Web 证据回答

```text
web_search
  ↓
web_page_analyze
  ↓
web_crawler / crawl_url
  ↓
final_answer
```

如果用户指定金融公告或上市公司披露：

```text
finance_site_search
  ↓
retrieve_financial_evidence
  ↓
final_answer
```

## 错误处理和重规划

| 错误 | 处理 |
| --- | --- |
| `TENANT_MISSING` | 停止执行，不 retry，不重建 session |
| asset 未匹配 | 允许补充更精确 filters 或返回候选资产 |
| template 未匹配 | 不发明模板；说明无授权模板，或改写 intent 后最多重试一次 |
| template scope mismatch | 当 template_query 可用时回到 template_query，按目标 scope 重新选模板；不可用时停止并报告缺少模板发现能力 |
| SQL 参数含 raw SQL | 拒绝执行，移除 raw SQL；当 template_query 可用时重新发现模板，不可用时停止 |
| schema/table 未定位 | 走 `sql_metadata_search` / table location 模板 |
| document_search 空结果 | 改写一次查询；仍为空则报告证据不足 |
| execute 工具失败 | 根据结构化错误决定 STOP / RETRY_ONCE / REBUILD_SESSION |

## Planner 必须遵守的字段所有权

| 字段/对象 | 唯一来源 |
| --- | --- |
| `assets[]` | asset_query |
| `templates[]` | template_query 或已观测授权模板契约 |
| `templateId` | template_query 或已观测授权模板契约 |
| SQL 结构化结果 | `sql_query_execute` |
| SSH 命令结果 | `linux_command_execute` |
| HTTP 调用结果 | `http_request_execute` |
| 文档证据 | `document_search` |
| Web 证据 | Web 工具族 |
| `review_answer` | reviewer |
| `final_answer` | finalizer |

## 最重要的禁止清单

1. 禁止模型直接写 SQL 并塞进 `parameters.sql`、`rawSql`、`statement`、`query`。
2. 禁止把 SQL 片段伪装成 `tableName`、`whereClause`、`filterExpression`、`condition`。
3. 禁止模型直接写 shell 命令。
4. 禁止模型直接写 URL、headers、body template。
5. 禁止模型直接传 `hostId`、`datasourceId`、`endpointId`、JDBC URL、IP。
6. 禁止 reviewer 写 `final_answer`。
7. 禁止 planner/rewriter 规划不在本次 `availableTools` 中的 MCP 工具，即使该工具已在全局 registry 注册。
8. 禁止用实例级模板代替表级模板，例如用 `MYSQL_INNODB_TRX` 回答表结构。

## 推荐落地到 planner 的强约束

复杂 SQL 分析任务的推荐链路：

```text
sql_datasource_asset_query
sql_metadata_search
sql_datasource_template_query（仅当 availableTools 中存在）
sql_query_execute（仅当 templateId 和参数契约已确定）
final_answer
```

受控执行任务必须满足：

```text
execute.template/templateId ∈ template_query.templates[].templateId 或已观测授权模板契约
execute.parameters ⊆ 模板 parameterSchema.properties
execute.executionContext 来自 asset_query 或用户逻辑上下文
```

最终回答必须满足：

```text
final_answer 只能引用已完成 step 的 structuredContent
缺失的数据要明确列入“未获取依据”
不能把可能原因写成已确认事实
```
