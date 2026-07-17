# chatchat-runtime-news 契约说明

> 契约版本：`internal/v1`  
> 服务默认地址：`http://localhost:8091`  
> 调用方：`chatchat-mcp-server`

## 1. 通用约定

### 1.1 认证

所有 `/internal/**` 请求必须携带：

```http
Authorization: Basic base64(username:secret)
Accept: application/json
```

有 JSON 请求体时还必须携带：

```http
Content-Type: application/json
```

MCP Server 与 News Runtime 的 `chatchat.internal-credential` 用户名、密钥和解密配置必须一致。认证失败返回 HTTP `401`：

```json
{"code":401,"message":"Invalid internal credential"}
```

### 1.2 响应信封

成功响应统一使用 `ApiResponse<T>`：

```json
{
  "code": 200,
  "message": "Success",
  "data": {},
  "timestamp": 1784275200000,
  "requestId": null
}
```

调用方必须同时检查 HTTP 状态码和信封中的 `code`。`code >= 400` 视为失败，不得仅凭 HTTP 200 判断成功。

### 1.3 时间、字符集与空值

- JSON 使用 UTF-8。
- 时间使用 ISO-8601 UTC 时间，例如 `2026-07-17T06:30:00Z`。
- 未设置的可选字段可以是 `null`，数组字段建议传空数组而不是逗号分隔字符串。
- 未知 JSON 字段不得作为调用成功的前提；新增可选字段属于向后兼容变更。

## 2. 内部管理接口

基础路径：`/internal/v1/news`

| 方法 | 路径 | 请求体 | data 类型 | 用途 |
| --- | --- | --- | --- | --- |
| GET | `/health` | 无 | Health | 内部健康检查 |
| GET | `/sources` | 无 | `NewsSourceView[]` | 查询资讯源 |
| POST | `/sources` | `NewsSourceUpsert` | `NewsSourceView` | 新增资讯源 |
| PUT | `/sources/{id}` | `NewsSourceUpsert` | `NewsSourceView` | 更新资讯源 |
| DELETE | `/sources/{id}` | 无 | `null` | 删除尚无采集记录的资讯源 |
| GET | `/sources/{id}/rule` | 无 | `NewsRuleView` | 获取网页提取规则 |
| PUT | `/sources/{id}/rule` | `NewsRuleUpsert` | `NewsRuleView` | 保存网页提取规则 |
| POST | `/sources/{id}/collect` | 无 | `NewsCollectResult` | 管理员立即采集 |
| POST | `/tools/{toolName}` | `ToolInput` | `ToolOutput` | 执行查询工具 |

管理接口由 MCP 后台调用，不注册为 Agent 可调用工具。

### 2.1 NewsSourceUpsert

```json
{
  "sourceCode": "sse_announcements",
  "sourceName": "上海证券交易所公告",
  "sourceType": "WEB_LIST",
  "entryUrl": "https://www.sse.com.cn/disclosure/listedinfo/announcement/",
  "allowedDomain": "sse.com.cn",
  "scheduleCron": "0 */15 * * * *",
  "enabled": false,
  "configuration": {
    "sleepMillis": 1000,
    "timeoutMillis": 20000,
    "zoneId": "Asia/Shanghai",
    "language": "zh-CN",
    "attachmentSelector": "a[href$='.pdf']",
    "attachmentAllowedDomains": "static.sse.com.cn"
  }
}
```

规则：

- `sourceCode` 全局唯一且不可为空。
- `sourceType` 只能是 `RSS`、`API`、`EXCHANGE_HOME`、`NEWS_HOME`、`CLS_TELEGRAPH`、`CNINFO_ANNOUNCEMENTS`、`WEB_LIST`、`WEB_SINGLE_PAGE`。
- `entryUrl` 必须是合法 HTTP/HTTPS URL。
- `scheduleCron` 是六段 Spring Cron。
- 新建来源建议 `enabled=false`，验证规则后再启用。
- Agent 不得直接传递 WebMagic 线程数、抓取深度或任意底层执行参数。

### 2.2 NewsSourceView

响应在 Upsert 字段外增加：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 资讯源 ID |
| `capabilityId` | long | 固定归属 News MCP 能力 |
| `lastCollectedAt` | instant/null | 最近一次采集时间 |
| `collectedRecords` | long | 已保存采集记录数 |
| `updatedAt` | instant | 更新时间 |

### 2.3 NewsRuleUpsert

```json
{
  "listSelector": null,
  "linkSelector": "a[href*='/disclosure/']",
  "titleSelector": "h1, .title",
  "contentSelector": ".article-content, .content, body",
  "authorSelector": ".source",
  "publishTimeSelector": ".date, .time",
  "urlPattern": "(?i)^https?://[^?#]+/.*(?:notice|announcement|disclosure).*$"
}
```

- 选择器采用 CSS Selector 语法。
- `urlPattern` 采用 Java 正则语法，保存时必须可以被 `Pattern.compile` 编译。
- `WEB_LIST` 必须提供 `linkSelector`、`titleSelector`、`contentSelector`。
- `WEB_SINGLE_PAGE` 必须提供 `titleSelector`、`contentSelector`。
- 内置正则模板由 MCP Server 管理；News Runtime 只保存最终正则文本。

### 2.4 NewsCollectResult

```json
{
  "executionId": "mcp-admin-uuid",
  "sourceId": 12,
  "discoveredCount": 20,
  "acceptedCount": 15,
  "duplicateCount": 3,
  "rejectedCount": 2,
  "failedCount": 0,
  "errorMessage": null
}
```

`采集完成` 不等于全部写入成功；调用方应检查各计数字段和 `errorMessage`。立即采集是后台管理行为，不能通过查询工具间接修改来源配置。

## 3. 工具调用契约

请求路径：`POST /internal/v1/news/tools/{toolName}`

统一请求体：

```json
{
  "rawInput": null,
  "parameters": {},
  "requestId": "request-uuid",
  "userId": null,
  "conversationId": null,
  "context": {}
}
```

### 3.1 web_search

面向 Agent 的基础资讯搜索能力，数据只来自 News 索引。

| 参数 | 类型 | 必填 | 默认/范围 |
| --- | --- | --- | --- |
| `query` | string | 是 | 非空 |
| `num_results` | number | 否 | 默认 10，1～50 |

`data` 包含 `query`、`provider=chatchat-runtime-news`、`mode=news_index`、`count`、`results`、`reference_urls`。

### 3.2 news_search

| 参数 | 类型 | 必填 | 默认/范围 |
| --- | --- | --- | --- |
| `query` | string | 是 | 非空 |
| `sourceIds` | long[] | 否 | 全部来源 |
| `startTime` | ISO-8601 string | 否 | 无下界 |
| `endTime` | ISO-8601 string | 否 | 无上界 |
| `categories` | string[] | 否 | 全部分类 |
| `size` | number | 否 | 默认 10，1～50 |

`data` 包含 `count`、`items` 和去重后的 `reference_urls`。

### 3.3 news_latest

| 参数 | 类型 | 必填 | 默认/范围 |
| --- | --- | --- | --- |
| `sourceIds` | long[] | 否 | 全部来源 |
| `hours` | number | 否 | 默认 24，1～720 |
| `size` | number | 否 | 默认 10，1～50 |

### 3.4 news_source_status

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sourceId` | string/number | 否 | 不传时返回全部来源状态 |

该工具只读，返回 `sourceId`、`sourceCode`、`sourceName`、`sourceType`、`enabled`、`lastCollectedAt`、`collectedRecords`。

## 4. ToolOutput

```json
{
  "success": true,
  "data": {},
  "message": "News index search completed",
  "errorMessage": null,
  "exceptionType": null,
  "errorDetails": null,
  "executionTimeMs": null,
  "tokenUsage": {},
  "metadata": {}
}
```

工具业务失败时 HTTP 调用本身仍可能成功，但 `ToolOutput.success=false`。调用方必须检查该字段。

OpenSearch 未启用时返回稳定错误码：

```json
{
  "success": false,
  "data": {
    "success": false,
    "code": "NEWS_STORE_UNAVAILABLE",
    "message": "News资讯存储未启用或OpenSearch连接未配置",
    "tool": "news_search",
    "capability": "news"
  },
  "errorMessage": "NEWS_STORE_UNAVAILABLE: News资讯存储未启用或OpenSearch连接未配置"
}
```

## 5. 证据项契约

`web_search.results[]`、`news_search.items[]` 和 `news_latest.items[]` 使用统一证据结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `documentId` | string | 文档或分片唯一 ID |
| `title` | string | 资讯标题；附件分片可包含附件文件名 |
| `url` / `sourceUrl` | string | 当前文档原始地址 |
| `content` | string | 正文或附件分片文本 |
| `summary` | string/null | 可选摘要 |
| `sourceId` / `sourceName` | long/string | 来源标识 |
| `publishTime` | instant/null | 发布时间 |
| `categories` / `tags` | array | 分类与标签 |
| `documentKind` | string | `news_article` 或 `attachment_chunk` |
| `parentDocumentId` | string/null | 附件所属资讯 ID |
| `attachmentFileName` | string/null | 附件文件名 |
| `chunkIndex` | number/null | 附件分片序号 |
| `evidence` | object | 用于模型引用的稳定证据对象 |

`evidence` 至少包含：

```json
{
  "title": "原始资讯标题",
  "url": "https://example.com/news/123",
  "sourceName": "资讯来源",
  "publishTime": "2026-07-17T06:30:00Z",
  "attachmentTitle": "report.pdf",
  "attachmentUrl": "https://example.com/files/report.pdf"
}
```

契约要求：

- 普通资讯必须返回可验证的 `evidence.title` 和 `evidence.url`。
- 附件分片必须同时关联原资讯 URL；存在附件地址时返回 `attachmentUrl`。
- `reference_urls` 是本次结果中原资讯 URL 与附件 URL 的去重集合，模型生成结论时应优先引用这些地址。
- 调用方不得把内部 `documentId` 当作外部证据地址。

## 6. 兼容性规则

以下变更向后兼容：

- 响应对象新增可选字段；
- 增加新的来源类型或工具，但不改变现有名称；
- 增加新的错误详情或 metadata；
- 提高内部实现的解析格式和检索策略。

以下变更需要升级契约版本：

- 删除或重命名现有字段、工具或路径；
- 修改字段类型或时间格式；
- 改变认证方式；
- 改变 `success`、`code` 或证据 URL 的判断语义。

## 7. 超时与重试

- MCP 到 News Runtime 默认超时为 30 秒。
- 查询请求可在网络失败或 5xx 时由调用方有限重试；创建、更新和立即采集不能无条件自动重试。
- 采集执行时间可能超过普通查询超时。后续若改为异步任务，必须新增任务资源和状态接口，不能悄悄改变当前同步响应语义。
- 附件下载和 OpenSearch Bulk 在服务内部执行各自的有限重试与退避，调用方不得通过高频重复请求替代内部重试。
