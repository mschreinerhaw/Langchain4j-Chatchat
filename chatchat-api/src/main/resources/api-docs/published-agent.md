# Agent API 对接说明

当前 Agent ID 为 `{{AGENT_ID}}`。接口按当前租户、调用用户和 Agent 授权检查访问权限。状态、答案、报告和取消接口要求任务归调用者所有。

## 调用准备

示例使用 POSIX Shell。将占位令牌替换为管理员签发的 Agent API Token。每次请求都要发送 `Authorization: Bearer <token>`。提交问题后，将 `data.taskId` 写入 `TASK_ID`，供后续接口使用。

```sh
export AGENT_BASE_URL="{{BASE_URL}}"
export AGENT_ID="{{AGENT_ID}}"
export AGENT_TOKEN="<paste-agent-api-token>"
```

JSON 响应共有 `code`（整数业务码）、`message`（字符串）、`data`（接口返回值）、`timestamp`（毫秒 Unix 时间戳）和可空的 `requestId`（字符串）。错误响应中的 `data` 可为 `null` 或省略。未提供凭证或凭证无效时返回 HTTP 401，权限不足时返回 HTTP 403。时间字段除特别标注外使用 ISO 8601。

## 上传分析附件

`POST /api/v1/published-agents/{agentId}/attachments`

将一个附件上传到调用者的个人空间，供提交问题时通过 `attachmentIds` 引用。

### 输入参数

| 位置 | 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| 路径 | `agentId` | string | 是 | 已发布且已授权的 Agent ID |
| multipart 表单 | `file` | file | 是 | 单个文件。Agent 接口上限为 16 MiB，存储配置可能进一步限制 |

### 返回参数

HTTP 200，`data` 为文件对象。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 文件 ID，用于提交任务的 `attachmentIds` |
| `kind`, `fileName`, `contentType` | string | 文件类别、名称和 MIME 类型 |
| `sha256`, `size` | string, integer | 内容摘要、字节数 |
| `state` | string | 文件状态 |
| `taskId`, `runId`, `sourceRef` | string 或 null | 任务、运行及来源引用，个人附件可为空 |
| `createdAt` | integer | 创建时间，毫秒 Unix 时间戳 |

HTTP 404 表示 Agent 不存在或未发布，413 表示文件超限，503 表示用户资产服务未启用。

### 请求示例

```sh
curl --fail-with-body --show-error \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/attachments" \
  -H "Authorization: Bearer ${AGENT_TOKEN}" \
  -F "file=@./sample.csv"
```

### 响应示例

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "kind": "DOCUMENT",
    "fileName": "sample.csv",
    "contentType": "text/csv",
    "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "size": 128,
    "state": "AVAILABLE",
    "taskId": "",
    "runId": "",
    "sourceRef": "upload",
    "createdAt": 1795910400000
  },
  "timestamp": 1795910400000
}
```

## 提交问题

`POST /api/v1/published-agents/{agentId}/questions`

异步提交问题。客户端收到 HTTP 202 后，用 `data.statusUrl` 或下文的状态接口查询进展。

### 输入参数

| 位置 | 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| 路径 | `agentId` | string | 是 | 已发布且已授权的 Agent ID |
| JSON | `question` | string | 是 | 非空问题正文 |
| JSON | `sessionId` | string | 否 | 会话 ID。省略时服务端生成 UUID，多轮对话可复用返回值 |
| JSON | `idempotencyKey` | string | 否 | 同一个逻辑请求重发时沿用原值，按调用用户和 Agent 隔离 |
| JSON | `historyWindow` | integer | 否 | 历史窗口，显式值会限制在 0 至 100 |
| JSON | `imageAnalysisIds` | string[] | 否 | 已有图像分析记录 ID 列表 |
| JSON | `parameters` | object | 否 | Agent 的扩展参数，以已发布配置为准。以 `__` 开头的键会被忽略 |
| JSON | `attachmentIds` | string[] | 否 | 当前用户的 1 至 8 个不同文件 ID。总计最多 16 MiB，单张图片最多 10 MiB |
| JSON | `reportMode` | string | 否 | `visual` 启用报告，`off` 关闭报告。默认 `visual`，显式字段优先于 `parameters.reportMode` |

### 返回参数

HTTP 202，`data` 为受理结果。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskId` | string | 后续查询及取消使用的任务 ID |
| `executionId`, `attemptId` | string | 执行 ID、执行尝试 ID |
| `sessionId`, `agentId` | string | 会话 ID、Agent ID |
| `status`, `canonicalState` | string | 业务状态、统一执行状态 |
| `statusUrl`, `answerUrl`, `cancelUrl` | string | 状态、答案、取消接口的相对路径 |
| `submittedAt` | string | 提交时间 |

HTTP 400 表示问题为空或参数不合法，404 表示 Agent 不存在或未发布，503 表示请求引用了附件但用户资产服务未启用。

### 请求示例

```sh
curl --fail-with-body --show-error \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/questions" \
  -H "Authorization: Bearer ${AGENT_TOKEN}" \
  -H "Content-Type: application/json" \
  --data '{"question":"整理已授权资料中的主要发现","reportMode":"visual"}'
```

上传了附件时，可以把上传结果的 `data.id` 放入问题请求的 `attachmentIds` 数组。

### 响应示例

```json
{
  "code": 202,
  "message": "Published Agent question accepted",
  "data": {
    "taskId": "task-123",
    "executionId": "execution-123",
    "attemptId": "attempt-1",
    "sessionId": "6f2e7d6a-21b8-4ea4-a4d6-4cb81fa31c57",
    "agentId": "{{AGENT_ID}}",
    "status": "RUNNING",
    "canonicalState": "EXECUTING",
    "statusUrl": "/api/v1/published-agents/{{AGENT_ID}}/questions/task-123/status",
    "answerUrl": "/api/v1/published-agents/{{AGENT_ID}}/questions/task-123/answer",
    "cancelUrl": "/api/v1/published-agents/{{AGENT_ID}}/questions/task-123",
    "submittedAt": "2026-11-28T12:00:00Z"
  },
  "timestamp": 1795867200000
}
```

## 查询状态与增量事件

`GET /api/v1/published-agents/{agentId}/questions/{taskId}/status`

第一次读取可省略游标。后续将本次 `data.eventCursor` 作为 `afterSequence`。若 `hasMoreEvents` 为 `true`，继续取下一页；`terminal` 为 `true` 后，可读取最终答案并按需取完剩余事件。

### 输入参数

| 位置 | 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| 路径 | `agentId` | string | 是 | 提交任务时使用的 Agent ID |
| 路径 | `taskId` | string | 是 | 提交结果中的 `data.taskId` |
| 查询 | `afterSequence` | integer | 否 | 读取序号严格大于该值的事件，默认 0，负值按 0 处理 |
| 查询 | `eventLimit` | integer | 否 | 默认 50，最大 200。非正数按默认值处理 |

### 返回参数

HTTP 200，`data` 为状态快照与一页公开事件。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskId`, `executionId`, `attemptId`, `sessionId`, `agentId` | string | 任务、执行、尝试、会话和 Agent 标识 |
| `attemptNumber` | integer | 执行尝试序号 |
| `status`, `canonicalState` | string | 业务状态、统一执行状态 |
| `terminal`, `answerAvailable` | boolean | 是否终态、终态是否已有答案摘要。最终结果以答案接口为准 |
| `error` | string 或 null | 任务错误摘要 |
| `createdAt`, `updatedAt` | string | 任务创建及更新时间 |
| `cancelUrl`, `answerUrl` | string | 取消及答案接口的相对路径 |
| `events` | object[] | 公开事件，字段见下表 |
| `eventCursor`, `hasMoreEvents` | integer, boolean | 下一次使用的游标、本页之后是否还有事件 |

| `events[]` 字段 | 类型 | 说明 |
| --- | --- | --- |
| `eventId`, `sequence`, `type`, `occurredAt` | string, integer, string, string | 事件标识、序号、类型和时间 |
| `status`, `scope`, `parentEventId` | string 或 null | 事件状态、范围和上级事件 |
| `executionId`, `attemptId`, `toolName` | string 或 null | 关联执行、尝试及工具 |
| `payload` | JSON 值或 null | 受限的公开事件内容，结构随类型变化 |
| `latencyMs`, `retryCount` | integer 或 null | 耗时（毫秒）、重试次数 |
| `errorCode` | string 或 null | 事件错误码 |

HTTP 404 表示找不到该 Agent 下的任务。

### 请求示例

```sh
curl --fail-with-body --show-error \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/questions/${TASK_ID}/status?afterSequence=0&eventLimit=50" \
  -H "Authorization: Bearer ${AGENT_TOKEN}"
```

### 响应示例

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task-123",
    "executionId": "execution-123",
    "attemptId": "attempt-1",
    "attemptNumber": 1,
    "sessionId": "6f2e7d6a-21b8-4ea4-a4d6-4cb81fa31c57",
    "agentId": "{{AGENT_ID}}",
    "status": "RUNNING",
    "canonicalState": "EXECUTING",
    "terminal": false,
    "answerAvailable": false,
    "error": null,
    "createdAt": "2026-11-28T12:00:00Z",
    "updatedAt": "2026-11-28T12:00:01Z",
    "cancelUrl": "/api/v1/published-agents/{{AGENT_ID}}/questions/task-123",
    "answerUrl": "/api/v1/published-agents/{{AGENT_ID}}/questions/task-123/answer",
    "events": [],
    "eventCursor": 0,
    "hasMoreEvents": false
  },
  "timestamp": 1795867201000
}
```

## 获取最终答案

`GET /api/v1/published-agents/{agentId}/questions/{taskId}/answer`

建议在状态接口返回 `terminal: true` 后调用。用 `ready` 判断是否有可读取的结果，用 `status` 判断是完整成功、部分交付、需要补充输入还是其他状态。

### 输入参数

| 位置 | 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| 路径 | `agentId` | string | 是 | 提交任务时使用的 Agent ID |
| 路径 | `taskId` | string | 是 | 提交结果中的 `data.taskId` |

### 返回参数

HTTP 200，`data` 为答案及报告描述。无答案时仍可能返回 HTTP 200 和 `ready: false`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskId`, `executionId`, `attemptId`, `sessionId`, `agentId` | string | 任务、执行、尝试、会话和 Agent 标识 |
| `status`, `canonicalState` | string | 业务状态、统一执行状态 |
| `terminal`, `ready` | boolean | 是否终态、是否已有答案或可下载报告 |
| `answer`, `error` | string 或 null | 答案内容、任务错误摘要 |
| `references` | object[] | 答案的引用项，结构随任务变化 |
| `updatedAt` | string | 更新时间 |
| `report` | object 或 null | 报告可用时的下载描述 |
| `report.downloadUrl`, `report.bundleUrl` | string | HTML 与 ZIP 的相对路径 |
| `report.source` | string | `archived_html` 为原生归档，`answer_html` 为文本答案渲染 |
| `report.entryPath` | string | ZIP 解压后打开的 HTML 入口 |

HTTP 404 表示找不到该 Agent 下的任务。

### 请求示例

```sh
curl --fail-with-body --show-error \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/questions/${TASK_ID}/answer" \
  -H "Authorization: Bearer ${AGENT_TOKEN}"
```

### 响应示例

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task-123",
    "executionId": "execution-123",
    "attemptId": "attempt-1",
    "sessionId": "6f2e7d6a-21b8-4ea4-a4d6-4cb81fa31c57",
    "agentId": "{{AGENT_ID}}",
    "status": "SUCCESS",
    "canonicalState": "SUCCEEDED",
    "terminal": true,
    "ready": true,
    "answer": "已整理资料中的主要发现。",
    "references": [],
    "error": null,
    "updatedAt": "2026-11-28T12:00:05Z",
    "report": {
      "downloadUrl": "/api/v1/published-agents/{{AGENT_ID}}/questions/task-123/report",
      "bundleUrl": "/api/v1/published-agents/{{AGENT_ID}}/questions/task-123/report?bundle=true",
      "source": "answer_html",
      "entryPath": "report.html"
    }
  },
  "timestamp": 1795867205000
}
```

## 下载报告

`GET /api/v1/published-agents/{agentId}/questions/{taskId}/report`

使用 `data.report.downloadUrl` 获取 HTML，或用 `data.report.bundleUrl` 获取包含本地资源的 ZIP。原生报告引用样式、脚本或图片时，下载 ZIP 并解压，打开 `data.report.entryPath`。网页集成时，在禁用同源权限的沙箱中展示生成的 HTML。

### 输入参数

| 位置 | 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| 请求头 | `Authorization` | string | 是 | `Bearer <Agent API Token>` |
| 路径 | `agentId` | string | 是 | 提交任务时使用的 Agent ID |
| 路径 | `taskId` | string | 是 | 提交结果中的 `data.taskId` |
| 查询 | `bundle` | boolean | 否 | 默认 `false` 返回 HTML，`true` 返回 ZIP |

### 返回参数

HTTP 200 的响应体是文件字节，`Content-Disposition` 提供下载文件名。

| `bundle` | `Content-Type` | 默认文件名 |
| --- | --- | --- |
| `false` | `text/html; charset=UTF-8` | `report.html` |
| `true` | `application/zip` | `report.zip` |

HTTP 403 表示凭证类型或权限不符合要求，404 表示任务或报告不存在、尚未就绪或已关闭报告，503 表示报告存储不可用。

### 请求示例

下载 HTML。

```sh
curl --fail-with-body --show-error \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/questions/${TASK_ID}/report" \
  -H "Authorization: Bearer ${AGENT_TOKEN}" \
  --output report.html
```

下载 ZIP。

```sh
curl --fail-with-body --show-error \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/questions/${TASK_ID}/report?bundle=true" \
  -H "Authorization: Bearer ${AGENT_TOKEN}" \
  --output report.zip
```

### 响应示例

```http
HTTP/1.1 200 OK
Content-Type: application/zip
Content-Disposition: attachment; filename="report.zip"
```

## 取消任务

`DELETE /api/v1/published-agents/{agentId}/questions/{taskId}`

请求取消进行中的任务。若返回 `terminal: false`，继续查询状态。已处于终态时，接口返回当前任务状态。

### 输入参数

| 位置 | 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| 路径 | `agentId` | string | 是 | 提交任务时使用的 Agent ID |
| 路径 | `taskId` | string | 是 | 提交结果中的 `data.taskId` |

### 返回参数

HTTP 200，`data` 为取消处理后的任务状态。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskId`, `executionId`, `attemptId`, `agentId` | string | 任务、执行、尝试和 Agent 标识 |
| `status`, `canonicalState` | string | 业务状态、统一执行状态 |
| `terminal` | boolean | 是否已进入终态 |
| `updatedAt` | string | 更新时间 |

HTTP 404 表示找不到该 Agent 下的任务。

### 请求示例

```sh
curl --fail-with-body --show-error -X DELETE \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/questions/${TASK_ID}" \
  -H "Authorization: Bearer ${AGENT_TOKEN}"
```

### 响应示例

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "taskId": "task-123",
    "executionId": "execution-123",
    "attemptId": "attempt-1",
    "agentId": "{{AGENT_ID}}",
    "status": "CANCELLED",
    "canonicalState": "CANCELLED",
    "terminal": true,
    "updatedAt": "2026-11-28T12:00:03Z"
  },
  "timestamp": 1795867203000
}
```
