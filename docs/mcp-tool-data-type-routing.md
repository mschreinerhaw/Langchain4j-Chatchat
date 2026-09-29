# MCP 工具用途与问题分析路由

MCP `tools/list` 的工具 `_meta.data_type` 声明工具用途，不表示输出的 JSON 类型，也不授予执行权限。例如：

```json
{"name":"opaque_tool_id","_meta":{"data_type":"TEMPLATE_QUERY"}}
```

| data_type | 用途 |
| --- | --- |
| `ASSET_QUERY` | 查询资产信息、用途和契约 |
| `TEMPLATE_QUERY` | 查询模板信息、参数和适用范围 |
| `DATA_FETCH` | 获取供分析使用的实际数据 |
| `DOCUMENT_SEARCH` | 获取文档证据 |
| `DIRECT_QA` | 直接问答 |
| `ACTION_EXECUTION` | 执行操作，仍须校验授权与确认 |
| `UNKNOWN` | 未声明用途，禁止从工具名称推断 |

已有发布器可以根据其显式 `workflowContract.workflowRole` 在 MCP 发布层补全资产查询、模板查询与模板执行用途。SQL 只读执行、数据库查询、API 与文档发布器声明更具体的用途。第三方显式声明和扩展用途原样保留。runtime 不使用业务名称、Agent ID、模板名称或关键词维护路由表。

用途经 HTTP 工具注册与 gRPC 描述符透传。`ToolMetadata` 以 `data_type` 序列化，并兼容读取 `dataType`；旧服务未声明时仍可注册。问题分析输入只读取 Agent 绑定范围内、当前请求选择且处于 active 状态的可见工具，不调用工具，也不做候选工具召回。

问题分析器结合用户目标、会话上下文和已选工具用途生成公开任务意图。runtime 按验证后的意图路由：

- `DIRECT_ANSWER`：直接模型问答，不进入工具执行或数据分析 DAG。
- `DOCUMENT_UNDERSTANDING`：文档工作流。
- `DATA_ANALYSIS`：现有受治理的数据分析规划与 DAG。
- `ASSET_USAGE_GUIDANCE`：资产或模板用途说明工作流。
- `ACTION_EXECUTION`：现有受治理的操作工作流。

模板查询既可能支持“解释用途”，也可能是“获取数据并分析”的前置步骤，不能只凭勾选工具强制选择工作流。缺失或未知用途也不能强制降级成直接问答。多目标请求继续保留并要求澄清，不会静默丢弃目标。

分析计划解析支持裸 JSON 和外层 JSON Markdown 代码块；拒绝尾随第二个 JSON、无效任务意图和不完整计划。真正的模型调用或契约校验失败仍返回失败，不伪造计划。失败日志包含底层异常类型与堆栈，计划观察事件标记 `FAILED`，避免出现 `PLANNING_FAILED` 却显示观察成功。

问题分析是父任务拥有的子阶段：进入时发布 `RUNNING`，在父任务线程内等待模型完成，再返回计划并选择工作流；取消时发布 `CANCELLED` 并向上传播。移除了独立的四线程拒绝池和固定 30 秒 `Future.get` 超时，避免子调用还活着就结束入口。模型客户端本身的超时、重试和任务取消仍然有效。所选工作流同样须返回后才能进行评估和生成最终响应。

现有附件只包含 `ExecutionException`，无法确定当次底层异常。部署后需同时更新 MCP 服务与 API/runtime，并刷新 MCP 工具目录；若问题继续发生，应依据新增的 `causeType` 和堆栈定位，而非根据业务问题添加特例。
