# Agent 检索质量评测与发布门禁

## 目标

线上运行轨迹与离线金标集统一使用 `agent_evaluation_v2`，避免离线评分通过但线上口径不同。标准发布 E2E 必须同时评估：

- 检索：Precision、Recall、MRR 及三者均值；无关候选和低排名命中都会降分。
- 工具选择：期望工具与实际工具的 Precision、Recall、F1；漏选和多选都会降分。
- 参数准确率：将期望参数和实际输入递归展开为字段路径，按正确字段值占比计分；期望参数是允许实际请求携带附加可选参数的子集。
- 证据完整率：期望证据命中率、引用命中率、工具证据覆盖率的均值；不存在的引用会单独触发失败。

总分为上述四个维度的算术平均。单条用例只有在四维阈值、总分、答案关键词、grounding、引用真实性和治理检查全部通过时才通过。

## 线上线下一体化

- 离线：金标用例构造 `AgentEvaluationCase`，对回放或合成的 `AgentRunTrace` 调用 `AgentEvaluationService`。
- 线上：对已持久化运行调用 `POST /api/v1/agent/runtime/runs/{runId}/evaluation`。接口读取真实 trace，并调用同一个 `AgentEvaluationService`。
- 聚合：`AgentQualityGateService` 对离线套件或线上采样窗口聚合，输出 `agent_quality_gate_v1`，包括用例通过率、四维均值、失败 runId 和具体阈值失败原因。
- 闭环：线上失败 runId 应进入回归金标集；修复后先通过离线门禁，再通过部署环境在线评测，最后才允许发布。

## 标准发布阈值

默认阈值如下，均可通过发布脚本参数提高：

| 指标 | 默认阈值 | PowerShell 参数 |
| --- | ---: | --- |
| 用例通过率 | 1.00 | `MinimumQualityCasePassRate` |
| 检索质量 | 0.90 | `MinimumRetrievalQuality` |
| 工具选择 | 0.95 | `MinimumToolSelectionAccuracy` |
| 参数准确率 | 0.95 | `MinimumParameterAccuracy` |
| 证据完整率 | 0.95 | `MinimumEvidenceCompleteness` |
| 单用例总分 | 0.95 | `MinimumOverallQuality` |

`ProductionAgentQualityEvaluationGateE2E` 是无环境依赖的离线门禁；启用部署拓扑测试后，`ProductionDeployedTopologyE2E` 还会从交互响应取得 `agentRunId`，调用线上评测接口并检查同样的四维契约。没有持久化 runId、没有证据引用、工具或参数不符合金标，都会阻断发布。

## 金标维护规则

- 工具名可使用稳定的逻辑后缀，例如 `financial_data_search`，评分器会兼容 MCP 命名空间前缀。
- 参数只声明业务上必须准确的字段；不要把随机 ID、时间戳等非确定字段写入金标。
- 检索期望优先使用稳定 `refId`；来源 ID 不稳定时，可用内容相关词和 `maxRank`。
- 每次线上质量事故至少新增一个能复现问题的金标用例，并保留错误工具、错误参数或无关候选作为负样本测试。

## 2026-08-15 候选验证记录

- 标准 Reactor 与 E2E：1,846 个测试，0 失败，0 错误，6 个条件跳过；四维离线质量门禁通过。
- 覆盖率：行 67.36%、分支 46.45%，低于既定 70%/60% 发布标准。
- 结论：本次功能与质量评测改造通过，但候选版本整体仍为 `NO-GO`。不得通过降低覆盖率阈值放行；还需补足代码覆盖，并在真实腾讯 WSA、部署拓扑和容量环境中以零跳过方式执行严格发布门禁。
