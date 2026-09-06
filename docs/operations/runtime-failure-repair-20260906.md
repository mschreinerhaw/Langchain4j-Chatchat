# 查询失败引起的连锁故障修复

## 原因与修改

1. 已发布查询模板直接将带千分位的字符串转换为 DECIMAL，H2 遇到 `11,092.58` 报转换错误。通过模板管理 API 同步更新 `sqlTemplate` 和 `ANALYZE.sqlContent`，先校验格式再转换，缺失或不符合数值格式的数据保留 NULL。配置 SQL 见 [repair-etf-scale-numeric-query.sql](sql/repair-etf-scale-numeric-query.sql)。这是业务查询配置，Runtime 没有增加 ETF 分支或查询重写规则。
2. SQL 异常包含完整语句，超过 `runtime_dag_node_attempt.state_reason` 的 1000 字符限制，异常落库又失败。短字段保存有界摘要，完整原因保存到 LONGTEXT 元数据 `fullStateReason`，同时保留已有元数据。
3. DAG 已决定继续独立分支，但工具层仍按旧工作流的全局失败标记拒绝调用。DAG 执行入口明确声明由图管理故障隔离；工具层在权威依赖图内执行依赖检查，不再重复全局熔断。实际依赖未完成的节点仍被拒绝。非 DAG 调用仍遵守原有 stopOnError。

## 验证

- `ToolRuntimeServiceTest`：67 项通过，包括失败后独立分支继续、依赖分支拒绝、无边图以及原有非 DAG 停止规则。
- `DatabaseNodeAttemptStoreTest`：3 项通过，包括超长异常、Unicode 边界以及完整诊断保留。
- `FinancialMarketQueryExecutorTest`：9 项通过，包括千分位、普通数值、负数、错误分组和空值。
- 服务器查询草稿与保存后的模板均执行成功，返回 100 行明细；窗口汇总覆盖 1356 条记录。此处 100 行是查询自身 LIMIT，不能当作全部明细。历史规模缺失时，变化字段保持 NULL。

以上验证覆盖本次故障链，不等同于完整模型报告已通过端到端验收。
