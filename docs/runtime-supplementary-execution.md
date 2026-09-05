# 补算、文本提取与 SQL 分页消费

## 已接入统一图的操作

`CALCULATE` 和 `EXTRACT_TEXT` 已进入 `UnifiedQuestionAnalysisGraph` 的补证循环，执行结果返回同一个全局 Findings 节点；没有生成分片报告后层层总结。

### 新公式补算

```json
{"operation":"CALCULATE","datasetReference":"assets","expression":"(a-b)/b","inputs":{"a":"current-total","b":"previous-total"}}
```

变量只能绑定该数据集的 Runtime `verifiedCalculations` 中已执行的计算结果，不能由模型直接提供数值。同名结果拒绝歧义绑定。复用算术表达式执行器，限制公式长度和变量数，拒绝未知变量、除零和非算术语法。返回计算值、公式、输入谱系和计算指纹。

新公式状态为 `COMPUTED_REQUIRES_SEMANTIC_REVIEW`，`conclusionEligible=false`。算术正确不意味着单位、统计粒度和业务含义已经授权。当前没有自动为模型提出的新公式创建或发布语义契约，也没有跨数据集隐式关联。

### 长文本逐片提取

```json
{"operation":"EXTRACT_TEXT","datasetReference":"logs","record":1,"field":"text","fromChar":0}
```

读取 Runtime 原始字符串字段，每片最多 4000 个 UTF-16 字符，尽可能在换行处分片。每片模型只提取至多 8 个候选事件，每个候选必须包含长度不超过 500 字符的原文引用。Java 检验引用并计算原始记录标识、字段和字符偏移；模型标签仍是待审查的解释。

每次提取最多处理 64 片，整个统一图执行的文本提取新模型调用总预算为 64。返回 `nextChar`、`totalChars`、`sourceComplete`，可以在补证循环中继续读取。达到预算后保留未覆盖范围，不能把已提取事件数量视为全文真实事件总数。跨分片边界的事件可能不完整，这一限制随结果传递。

分片检查点绑定运行隔离域、记录、字段、偏移、问题及文本内容。复用检查点时重新验证原文引用；错误候选拒绝并保留先前成功片。模型调用和恢复次数属于运行元数据，不进入下一轮模型证据，以免恢复导致提示变化、破坏后续检查点复用。

## SQL 服务端分页消费入口

`SqlQueryExecuteService.executeStreaming(arguments, pageConsumer)` 已实现同步分页交付：

- 沿用数据源、模板、SQL、表范围、脱敏、超时、行数上限和审计流程。
- 使用只读、向前游标及 fetch size 500，关闭自动提交以支持需要事务的 JDBC 游标。
- 脱敏后每 500 行调用消费者，消费者返回后才继续读；服务只保留 20 行预览。
- `streamedRows` 为已成功交付数量；`streamCompleted` 标记是否读完本次受限查询。达到查询行数上限仍标记可能截断。
- 消费失败停止查询并关闭 JDBC 资源；不透明重试已经交付的流，避免重复记录。

**此入口尚未被现有 MCP 查询工具或分析 Runtime 自动调用。** 跨进程页传输、隔离的持久页引用及 Runtime 的按页源适配还没有实现。因此当前只能确认 JDBC 服务层的分页消费能力，不能宣称聊天端到端数据库流式分析已完成。JDBC 驱动是否真正使用服务端游标还取决于驱动配置，不能将 fetch size 当作所有数据库的流式保证。

## 验证

H2 集成测试验证 1201 行按 500/500/201 页交付、脱敏、20 行预览及消费者失败；算术测试验证 Runtime 数值绑定、0.2 的补算结果、未知输入和除零拒绝；文本测试验证多片提取、原始偏移、检查点复用和伪造引用拒绝，并验证请求从统一图进入提取后返回全局 Findings。
