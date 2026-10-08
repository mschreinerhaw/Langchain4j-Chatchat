# 数据能力中心

管理页面按 Trino 查询、关系库查询、图数据库查询、非结构化数据查询、交易日历、批量导入拆分。
后端位于 `com.chatchat.mcpserver.datacapability`，按业务模块组织，不增加独立启动进程。

| 模块 | 包 | 实现 |
| --- | --- | --- |
| 统一定义 | `definition` | 能力编码、业务分类、输入 Schema、结果映射、发布开关，持久化与校验 |
| 统一执行 | `execution` | 参数校验、4 个执行线程、32 个排队名额、截止时间、执行状态和结果记录 |
| Trino | `trino` | JDBC 连接、Catalog / Schema、跨源 SQL，复用 SQL 治理链路 |
| 关系库 | `relational` | SQL 模板参数、安全检查、超时、现有数据源访问规则与脱敏 |
| 图数据库 | `graph` | Neo4j Cypher，返回列、实体、关系及路径的图结构 |
| 非结构化数据 | `unstructured` | OpenSearch DSL，关键词、条件过滤及 k-NN 查询 |
| 交易日历 | `calendar` | 按市场维护日期、休市和节假日，支持单日、相邻交易日及区间查询 |
| 批量导入 | `importing` | JSON 标准模板、逐行校验、部分成功、持久化反馈与异常记录 |
| API / MCP | `admin` / `publication` | 共用能力定义和执行服务，接入已有 MCP 发布审核、并发与 License 管理 |

原有 `/api/v1/database-query` 接口、数据表和 SQL 工作台继续可用；关系库页面可展开原工作台。
旧查询不会自动复制到新定义中。旧动态日期 SQL 配置仍用于旧工作流，新日历模块独立维护多市场日期。

## 连接配置

Trino、关系库使用资产中心已有 SQL 数据源。Trino 连接需使用 `jdbc:trino://host:port`，
通过 `lib/drivers/trino/` 安装与服务端兼容的 JDBC 驱动，驱动类为 `io.trino.jdbc.TrinoDriver`。
在查询能力的 `options` 中设置 `catalog` 和 `schema`，执行时设置 JDBC 会话命名空间。
SQL 可以直接使用完整的 `catalog.schema.table` 名称联合查询。

所有数据源地址、认证信息和启用状态均在资产配置中统一维护，数据能力中心只通过 `connectionId` 引用资产。
Trino、关系库引用数据库资产；图数据库和检索引擎复用“HTTP / 图库 / 检索资产”，不创建独立连接表。
Neo4j 资产的分类选择“图数据库（Neo4j）”（`graph_database`），OpenSearch 选择“检索引擎（OpenSearch）”（`search_engine`）。
两者的方法配置为 POST，URL 填写基础地址，如 `http://localhost:7474` 或 `https://search.example.com:9200`，
不要填写具体查询接口或占位符；查询路径由能力适配器生成。
认证使用资产配置已有的请求头，例如 `Authorization: Basic ...` 或 `Authorization: Bearer ...`。
查询使用资产中当前的请求头和启用状态，HTTP 超时同时受资产超时和查询超时约束，取较短值。
选择列表仅返回资产 ID、名称、类型及状态，不返回地址和认证信息。
被数据能力引用的 JDBC 或 HTTP 资产不能直接删除，应先删除或更改能力的资产引用。

Neo4j 使用事务 HTTP 接口 `/db/{database}/tx/commit`，支持只读 Cypher 子集，
禁止写操作、过程调用、注释及多语句；请使用具有读取权限的账户。
结果保留 `_graph.nodes` 和 `_graph.relationships`，参数使用 `$name`，
服务端通过子查询额外限制返回行数。协议参考 [Neo4j HTTP API](https://neo4j.com/docs/http-api/current/query/)。

OpenSearch 使用指定索引的 `/_search` 接口；不会创建索引或生成向量。
向量索引与 k-NN 配置由检索引擎维护，调用方提供向量数组。
向量过滤的支持取决于索引引擎，参考 [OpenSearch k-NN 查询](https://docs.opensearch.org/latest/query-dsl/specialized/k-nn/index/)。

## 能力定义

```json
{
  "code": "company_search",
  "title": "公司查询",
  "description": "按公司名称查询统一数据",
  "type": "TRINO",
  "categoryId": null,
  "connectionId": "trino_datasource_id",
  "query": "SELECT company_id, name FROM company WHERE name = {{name}}",
  "inputSchema": {
    "type": "object",
    "properties": { "name": { "type": "string" } },
    "required": ["name"]
  },
  "resultMapping": { "companyId": "company_id", "companyName": "name" },
  "options": { "catalog": "hive", "schema": "finance" },
  "timeoutSeconds": 30,
  "maxRows": 100,
  "enabled": true,
  "apiPublished": true,
  "mcpPublished": true
}
```

SQL 参数 `{{name}}` 表示完整标量值，无需加引号，禁止输入 SQL 片段。
字符串会转义单引号；为保证跨数据库的转义一致性，字符串参数不接受反斜杠或 NUL。
输入 Schema 复用现有 `TemplateParameterValidator`，校验必填项、类型、枚举和已支持的约束，
只将声明的参数传给执行器。SQL 超时范围为 1–60 秒，其他查询为 1–300 秒。
返回行数范围为 1–10000，SQL 还受现有 `chatchat.tools.database-query.max-rows` 上限约束。
结果映射是“输出字段 → 原始结果字段”的映射，空对象返回全部原字段；JDBC 字段大小写按实际数据库返回填写。

OpenSearch 查询参数必须占据一个完整 JSON 字符串值，以保留数组、对象与数值类型：

```json
{
  "query": {
    "knn": {
      "embedding": {
        "vector": "{{vector}}",
        "k": 10,
        "filter": { "term": { "market": "{{market}}" } }
      }
    }
  }
}
```

`size` 和 `timeout` 由能力配置控制。检索超时、分片失败或图查询错误记为失败，不把部分结果标记为成功。

## API 与 MCP

所有 HTTP 接口复用现有 Bearer 登录认证与 `databaseMcp` 功能授权，未发布或停用的 API 拒绝业务执行。
测试接口允许执行未发布的草稿或已保存定义。MCP 发布工具名为 `data_query_{code}`，
输入 Schema 来自能力定义，调用时再次检查当前启用状态和 MCP 发布状态。

| 方法 | 路径（前缀 `/api/v1/data-capabilities`） | 用途 |
| --- | --- | --- |
| GET / POST | `/` | 列表，可按 `type` 筛选；创建定义 |
| PUT / DELETE | `/{code}` | 修改／删除，同步 MCP 发布 |
| POST | `/test` | 草稿测试，请求 `{ "definition": {...}, "parameters": {...} }` |
| POST | `/{code}/test` | 已保存查询测试，直接传参数对象 |
| POST | `/{code}/invoke?async=true` | 异步业务执行，返回执行 ID 与状态 |
| POST | `/{code}/invoke` | 同步业务执行 |
| GET | `/executions/{id}` | 执行状态和结果 |
| GET | `/{code}/executions` | 最近 50 条执行记录 |
| POST | `/publication/refresh` | 重新同步 MCP 工具列表 |
| GET | `/connections?type=GRAPH` | 已有 HTTP 数据源资产引用列表，只读，支持类型筛选 |
| GET | `/connections/sql` | SQL 数据源选择列表，隐藏凭据 |
| GET / POST | `/calendar/days` | 按 `market/start/end` 查询日期；批量保存日期数组 |
| GET | `/imports/template?type=TRINO` | 获取单个能力模板 |
| POST | `/imports` | 请求 `{ "definitions": [...], "dryRun": true }` 校验；`false` 导入 |
| GET | `/imports`、`/imports/{id}` | 最近 50 个批次／批次逐行反馈 |

状态为 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`TIMED_OUT`。
执行记录包含开始与结束时间、耗时、错误，以及 `resultJson` 内的行数据、行数、截断标识和元数据。
统一截止时间包含排队等待；超时取消工作任务并保留终态，迟到结果不能覆盖它。
驱动不支持中断时，远端查询终止还依赖 JDBC 超时和数据库自身限制。
执行队列属于当前进程，重启不自动重试；历史记录保存在数据库。

## 交易日历与批量导入

维护日期使用如下数组；重复保存同一市场日期会更新记录，同一请求内重复日期会被拒绝。

```json
[
  { "market": "SSE", "date": "2026-01-01", "trading": false, "holiday": "元旦" }
]
```

日历定义使用 `type=TRADING_CALENDAR`，`options={"market":"SSE"}`；
`query` 取 `isTradingDay`、`previousTradingDay`、`nextTradingDay` 或 `tradingDays`。
前三项输入 `date`，区间输入 `start/end`，日期格式为 `YYYY-MM-DD`。
上一／下一交易日严格排除指定日期。范围或相邻日期间存在未维护日期时返回错误，
不根据周末规则推测交易日。市场代码可分别使用 `SSE`、`SZSE`、`HKEX` 等。

批量导入文件为能力定义数组，单次最多 1000 行。下载模板默认停用且不发布，
需替换连接 ID 并填写实际业务分类、参数和查询。已有或批内重复能力编码逐行报错，
不会覆盖已有定义；修改使用单条 PUT 接口。校验不会写入能力，但保存校验反馈。
正式导入逐行独立提交，合法行成功、异常行记录行号及原因。
若后续 MCP 发布失败，已导入定义仍保留，批次通过 `publicationError` 反馈原因；修正后调用发布刷新接口。

## 数据库与验证

已有数据库升级前执行对应的 `database/migration/{h2|mysql|postgresql}/V20261008_01__mcp_data_capability_center.sql`，
迁移只新增四张表及执行历史索引，不创建数据源连接表，也不迁移旧查询。全新安装的初始化 SQL 已包含这些表。
迁移脚本执行一次；现有开发配置仍支持 JPA `ddl-auto=update`。

```powershell
mvn -pl chatchat-mcp-server -am '-Dfrontend.skip=true' '-Dtest=QueryConnectionServiceTest,QueryTemplatesTest,CapabilityImportServiceTest,CapabilityExecutionServiceTest,TradingCalendarIntegrationTest,QueryHttpAdaptersTest,TrinoQueryAdapterTest,CapabilityMcpPublisherTest,CapabilityAdminControllerTest,SqlQueryExecuteServiceTest,SqlSafetyServiceTest,McpLicenseCoverageContractTest,DatabaseSchemaGeneratorTest,HttpEndpointConfigServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
cd chatchat-mcp-server/web-app-mcp
npm.cmd run build
```

测试包含 H2 日期持久化、HTTP 协议桩、类型保留、参数转义、导入部分成功、发布撤销、
状态与截止时间、调用上下文传播、现有 SQL 回归和三种数据库 DDL 一致性。
真实 Trino、Neo4j 与 OpenSearch 的连通性需在填写部署连接后通过页面测试查询验证。
