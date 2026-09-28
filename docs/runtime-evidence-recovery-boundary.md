# 分析 API 的证据获取与补证边界

`chatchat-api` 的分析接口调用共享 `DefaultAnalysisWorkflowRuntime`。Runtime 的
`EvidenceStateInspector` 只观察空检索、明确截断，以及带已知边界的未取全章节或序列。
`sectionComplete=false` 必须同时提供 `sectionBoundaryKnown=true`；序列对应
`sequenceBoundaryKnown=true`。缺失或未知的边界不能解释为内容不完整。
查询中出现“安装”“步骤”、片段数量、coverage、语义评分、验证拒绝和来源可信度意见
均不再触发 Runtime 的自动补证。

补证采用已注册且支持该采集问题的工作流，在原有授权范围内执行。
`AnalysisContext.attributes.evidenceRecoveryMaxRounds` 默认为 5，上限为 6，设为 0
可关闭自动补证。工作流返回相同内容时停止；预算耗尽、没有可用工作流或补证异常时
均保留已有证据和原有验证结论。取消和线程中断继续传播。

API 的 `AnalysisExecutionOutcome.metadata` 返回独立状态：

- `evidenceState`：证据数量、采集问题及 `AVAILABLE` / `PARTIALLY_AVAILABLE` /
  `UNAVAILABLE`，不代表充分性或可信度。
- `evidenceRecoveryStatus`：`NOT_NEEDED`、`DISABLED`、`NO_WORKFLOW`、`COMPLETE`、
  `FAILED`、`EXHAUSTED`、`NO_NEW_EVIDENCE` 或 `BUDGET_EXHAUSTED`。
- `evidenceRecoveryTrace`：各次补证的执行状态。
- `runtimeRoute=CONTINUE_ANALYSIS`：继续交还分析层，不因补证失败转换为发布许可或运行失败。

补证结果通过 `AnalysisWorkflow.continueAfterRecovery` 交给原分析工作流，不重放
可能包含副作用的主执行流程。该方法的兼容默认实现保留原来的 verification 和 synthesis，
返回合并后的证据，并标记 `synthesisEvidenceScope=PRIMARY_ANALYSIS_ONLY`。需要利用新增
材料重做分析的工作流应实现此方法；默认实现不会声称旧结论已使用新增材料。

`EvidenceGap` 暂时保留为补证 SPI 的兼容输入；Runtime 只使用其采集类原因，
对外的 `evidenceState` 不包含 coverage 或业务判定字段。分析意见和人工补证请求不应
伪装成截断等采集事实，业务流程可直接发起对应请求。

归档失败单独记录为 `evidenceArchiveStatus=FAILED`，不清空证据或改写分析判断。
如果业务 Contract 要求归档或特定证据才能发布，发布层必须依据该 Contract 单独执行
门禁。分析 API 返回成功表示执行请求返回了结果，不表示证据获采信或允许正式发布。
