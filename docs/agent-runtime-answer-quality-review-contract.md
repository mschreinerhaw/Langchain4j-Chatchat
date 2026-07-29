# Agent Runtime 最终答案质量评审契约

## 契约身份

- 契约名称：Agent Runtime Answer Quality Review Contract
- 质量评估协议：`answer_quality_evaluation_v1`
- 最终决策协议：`answer_decision_v1`
- 确定性聚合协议：`answer_quality_aggregation_v1`
- 候选生命周期协议：`runtime_answer_candidate_v1`
- 适用范围：InterpretationPlan 最终总结、事实守卫改写、Answer Reviewer、答案融合和最终用户输出
- 代码入口：`AnswerCandidateCollector`、`AnswerQualityEvaluator`、`AnswerDecisionEngine`、`AgentAnswerFinalizer`

本契约是 Agent Runtime 的最终输出门控，不是提示词写作建议。模型可以生成、评审和融合答案，但模型不能独占最终答案的写入权。

## 核心原则

> 最后生成的答案不天然优于较早的答案，安全改写也不天然等于高质量答案。

Runtime 必须同时保证：

1. 原始工具证据不因总结、改写、评审或融合而改变。
2. 后续安全改写不能静默覆盖较早版本中的有效分析。
3. Reviewer 发现的问题必须进入总结质量评估，但 Reviewer 意见不构成业务事实。
4. 当多个候选各有优势时，可以生成融合答案；融合答案必须重新参与评分和硬性过滤。
5. 最终答案由确定性规则选择，不能直接采用模型声明的 `preferredId`。
6. 评审失败、超时或输出不可解析时，必须回退到已有可用候选，不能因此返回空答案。

## 标准处理链

```text
原始结构化工具证据
  -> InterpretationPlan 阶段总结
  -> 领域事实/安全守卫改写
  -> 结构化证据合并
  -> Answer Reviewer 诊断
  -> 多候选质量评估
  -> 可选的证据约束融合答案
  -> 硬性淘汰
  -> 确定性加权聚合
  -> 证据披露与最终用户输出
```

上述顺序不得解释为“后一个阶段自动覆盖前一个阶段”。每个阶段只产生候选或约束，最终选择由本契约统一完成。

## 候选生命周期

### 候选来源

Runtime 可以收集以下候选：

| 候选来源 | 含义 | 是否为事实证据 |
| --- | --- | ---: |
| `candidate` | 当前准备输出的答案 | 否 |
| `final_synthesis` | 任意 Runtime 工作流的初次最终总结 | 否 |
| `fact_grounding_rewrite` | 任意领域事实守卫修正后的总结 | 否 |
| `structured_evidence_merge` | 任意结构化证据合并后的答案 | 否 |
| `document_evidence` | 根据带引用文档证据生成的回答 | 否，引用的文档片段才是证据 |
| `quality_synthesis` | 总结评审生成的融合答案 | 否 |

候选答案是“证据的表达方式”，不是新的证据。任何候选中的事实都必须能回溯到原始工具结果、用户明确事实或带来源标识的文档/Web 证据。

### 临时存储

- 阶段候选只允许在本次最终化过程中临时存在。
- 临时候选不得写入聊天详情、Evidence Store 或长期历史记录。
- Runtime 可以在内存 metadata 中使用内部字段传递候选，但必须在最终返回前移除。
- 原始工具证据、工具轨迹和最终选择审计信息仍按各自持久化契约保存。
- 不得为方便评审而复制或覆盖原始 Evidence Object。

所有 Runtime 组件必须通过通用接口注册候选：

```java
answerCandidateCollector.register(
    runtimeMetadata,
    stage,
    answer,
    evidenceRefs,
    attributes
);
```

`stage` 是稳定的阶段语义，不是业务名称或工具名称。文档、SQL、HTTP、数据库诊断和后续 Runtime 能力共用同一个收集接口，不得为单个领域建立独立候选管道。

内部传递字段由 `AnswerCandidateCollector` 封装；它不是公共 API 字段，也不得出现在最终用户响应或持久化 metadata 中。

### 去重

候选必须在进入质量评估前按规范化正文去重。仅空白、换行或 Markdown 外壳不同的答案不能作为多个独立候选参与评分。

## Answer Reviewer 权限

Answer Reviewer 的职责是诊断：

- 是否直接回答用户的实际请求；
- 是否遗漏关键分析维度；
- 是否与 observation 冲突；
- 是否错误使用失败工具结果；
- 是否缺少必要引用；
- 是否存在事实边界、Schema 或安全问题。

Reviewer 不得：

- 修改原始工具证据；
- 把自己的意见写成业务事实；
- 仅凭 `accepted=false` 删除当前候选；
- 直接取得最终答案写入权；
- 用更保守但信息量更低的文本静默替换已有高质量分析。

Reviewer 的 `feedback`、`issues` 和 `suggestions` 应作为总结评审输入，用于修复候选缺陷，但只具有诊断权。

## 总结融合契约

当至少存在两个正文不同的候选时，质量评估模型可以返回 `synthesizedAnswer`。

融合答案必须：

1. 保留各候选中有证据支持的有效分析。
2. 修正候选之间的数量、状态、标识符和完整性矛盾。
3. 使用 Reviewer 指出的缺陷进行补强。
4. 不引入 observation 中不存在的新事实。
5. 保留必要的证据引用、失败限制、截断状态和不确定性。
6. 形成完整、可直接交付用户的 Markdown，而不是工具清单或内部执行报告。

如果无法在不引入新事实的前提下改进候选，必须返回空的 `synthesizedAnswer`，继续从已有候选中选择。

融合答案必须作为独立候选 `quality_synthesis` 重新评分，不得因为它由“评审模型”生成就直接胜出。

## 质量维度

每个候选必须独立获得以下 `0.0` 到 `1.0` 的评分：

| 维度 | 权重 | 定义 |
| --- | ---: | --- |
| `grounding` | 0.30 | 事实是否来自可追溯 evidence/observation |
| `accuracy` | 0.25 | 是否与工具事实一致且没有无依据陈述 |
| `completeness` | 0.20 | 是否覆盖用户请求的重要部分 |
| `citation` | 0.15 | 是否保留必须的 `doc://`、`web://` 或其他证据引用 |
| `usefulness` | 0.10 | 是否清晰、可执行且适合直接交付用户 |

确定性聚合分数：

```text
aggregateScore =
    grounding   * 0.30
  + accuracy    * 0.25
  + completeness* 0.20
  + citation    * 0.15
  + usefulness  * 0.10
```

模型返回的 `score` 或 `preferredId` 仅用于审计，不参与最终确定性选择。

## 硬性淘汰规则

候选出现以下任一情况时必须淘汰：

- `empty_answer`
- `contradicts_observation`
- `uses_failed_tool_evidence`
- `missing_required_citation`
- `schema_violation`
- `unsafe`

证据必需型任务还必须淘汰：

- `grounding < 0.35`
- `citation < 0.35`

`quality_synthesis` 具有更高准入要求：

- `grounding < 0.75` 时淘汰；
- `accuracy < 0.75` 时淘汰。

融合答案不能依靠较高的完整性或可读性抵消低准确性、低证据一致性或硬性违规。

## 确定性选择与平局规则

1. 先执行硬性淘汰。
2. 在剩余候选中选择 `aggregateScore` 最高者。
3. 分数相同时按稳定来源优先级选择：

```text
deterministic_evidence  > document_evidence
                        > quality_synthesis
                        > candidate
                        > summary_stage
                        > reviewer_suggestion
```

4. 不得只因为某个候选是“最新版本”或 Reviewer 推荐版本而提高其优先级。
5. 最终选择必须记录候选得分、淘汰原因、胜出来源和选择原因。

## 回退规则

| 场景 | Runtime 行为 |
| --- | --- |
| 只有一个有效候选 | 直接保留该候选，不额外调用质量模型 |
| 质量模型不可用、超时或解析失败 | 保留当前可用候选 |
| 融合答案未达到准入阈值 | 淘汰融合答案，从原候选中选择 |
| 所有质量候选均被淘汰 | 回到事实守卫、文档证据守卫或当前候选的既有安全路径 |
| 当前候选为空但存在结构化证据 | 使用确定性证据报告，不得返回空答案 |
| 需要用户确认 | 保留确认提示，不执行总结改写 |

批量诊断在明确声明“只保留一次执行后模型调用”时，可以跳过额外 Answer Review/Quality Review，但最终总结仍必须遵守事实落地、结构化证据和证据披露契约。

## Runtime Metadata 契约

最终审计 metadata 应使用以下稳定字段：

```json
{
  "answerCandidateCollectorContractVersion": "runtime_answer_candidate_v1",
  "answerCandidateCollectedCount": 3,
  "answerCandidateCollectedStages": [
    "final_synthesis",
    "fact_grounding_rewrite",
    "structured_evidence_merge"
  ],
  "answerQualityContractVersion": "answer_quality_evaluation_v1",
  "answerDecisionContractVersion": "answer_decision_v1",
  "answerQualityAggregationVersion": "answer_quality_aggregation_v1",
  "answerQualityAvailable": true,
  "answerQualityCandidates": [],
  "answerQualityScores": [],
  "answerQualityAggregateScores": [],
  "answerQualitySelectedId": "quality_synthesis",
  "answerQualitySelectedSource": "quality_synthesis",
  "answerQualitySelectedAggregateScore": 0.96,
  "answerDecision": "quality_selected_answer",
  "answerDecisionReason": "deterministic_quality_aggregation_selected_highest_score",
  "answerRewriteSource": "quality_aggregator",
  "answerDecisionTrace": {
    "contractVersion": "answer_decision_trace_v1",
    "aggregationVersion": "answer_quality_aggregation_v1",
    "winnerId": "quality_synthesis",
    "winnerSource": "quality_synthesis",
    "candidates": []
  }
}
```

要求：

- metadata 中可以保存候选摘要、分数和决策轨迹；`answerPreview` 最多保留 1000 个字符。
- metadata 不得保留 `AnswerCandidateCollector` 的内部传递字段或完整临时候选正文。
- `answerQualityLlmSelectedId` 只表示模型偏好，不表示 Runtime 最终选择。
- `answerQualitySelectedId` 才表示确定性聚合的最终胜出候选。

## 与其他契约的关系

| 契约 | 关系 |
| --- | --- |
| `agent_runtime_fact_grounding_v1` | 定义不可修改的工具事实边界；本契约不得放宽该边界 |
| `evidence_augmentation_decision_v1` | 决定是否继续检索或进入阶段性分析；本契约只负责答案表达质量 |
| `mcp_result_evidence_policy_v1` | 判定是否存在非空 MCP 结果 |
| `answer_evidence_disclosure_v1` | 在最终正文中展示证据状态和限制 |
| `runtime_answer_candidate_v1` | 管理答案候选生命周期 |

优先级：

1. 授权、安全和用户确认。
2. 原始结构化工具证据。
3. 事实落地和证据增强决策。
4. 本答案质量评审契约。
5. 写作风格偏好。

较高优先级不得被质量分数覆盖。

## 禁止行为

- 只保留最后一次改写结果，丢弃更早的有效分析。
- 把“安全版本”自动当作“最佳版本”。
- Reviewer 已指出当前答案缺陷，却仍因 Reviewer 无改写权而完全忽略反馈。
- 直接采用模型的 `preferredId`。
- 用完整性、篇幅或语言流畅度掩盖事实错误。
- 融合多个候选时引入 observation 中不存在的新表、新字段、新指标或新结论。
- 把阶段总结、Reviewer 意见或融合答案写入 Evidence Store 作为原始证据。
- 将完整临时候选长期写入聊天历史，造成存储膨胀。
- 质量模型失败后返回空答案或覆盖已有可用结果。

## 验收场景

### 场景一：初次总结完整，但包含事实矛盾

- 初次总结包含完整业务分析。
- 其中数量与工具结果冲突。
- 安全改写修正数量但丢失部分分析。
- 期望：生成融合答案，保留有效分析并修正数量；原始初次总结不得直接胜出。

### 场景二：安全改写退化为清单

- 改写版本仅列出字段或工具结果。
- Reviewer 指出未回答用户核心问题。
- 期望：Reviewer 反馈进入总结融合；最终答案恢复有证据支持的分析。

### 场景三：融合答案更完整但缺少证据

- `quality_synthesis` 的完整性和实用性很高。
- `grounding` 或 `accuracy` 低于 `0.75`。
- 期望：融合答案被硬性淘汰，保留更可靠的原候选。

### 场景四：质量模型不可用

- 已有至少一个可用候选。
- 质量模型超时、失败或返回非法 JSON。
- 期望：保留当前候选并记录质量评估不可用，不返回空答案。

### 场景五：临时候选清理

- Runtime 收集多个阶段候选。
- 最终选择完成。
- 期望：最终 metadata 不包含候选收集器内部字段；原始 Evidence 和决策审计仍可追溯。

## 自动化验证要求

每次修改本契约或相关实现，至少覆盖：

- 多阶段候选被收集并去重；
- 文档、SQL、HTTP 等不同 Runtime 组件可通过同一收集接口注册候选；
- Reviewer 反馈进入质量评估；
- 融合答案作为独立候选重新评分；
- 低 grounding/accuracy 的融合答案被硬性淘汰；
- 确定性权重和来源平局规则稳定；
- 质量评估失败时保留当前答案；
- 临时候选不会进入最终 metadata；
- 原始工具证据不被候选或融合答案覆盖。

当前主要测试：

- `AnswerQualityEvaluatorTest`
- `AgentAnswerFinalizerEvidenceAnswerTest`
- `AgentOrchestratorTest`

## 版本演进

- 评分维度、权重、硬性阈值或来源优先级发生不兼容变化时，必须升级相应协议版本。
- 协议版本变化必须同步更新代码常量、metadata、提示词、本文档和自动化测试。
- 可以新增可选审计字段，但不得改变旧字段的事实语义。
- 历史运行记录保留其原始协议版本，不得静默重算或改写。
