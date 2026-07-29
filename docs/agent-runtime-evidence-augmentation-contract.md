# Agent Runtime Evidence Augmentation Contract v1

最终答案进入用户输出前，还必须遵守
[`Agent Runtime 最终答案质量评审契约`](agent-runtime-answer-quality-review-contract.md)。
证据增强契约决定是否继续探索和允许回答的范围；答案质量评审契约负责在不改变原始证据的前提下比较、融合和选择最终表达。

## 契约身份

- 契约名称：Agent Runtime Evidence Augmentation Contract
- 契约版本：`evidence_augmentation_decision_v1`
- 适用范围：Agent Runtime 的证据分析、计划演进、循环停止和最终答案交付
- 代码入口：`EvidenceAugmentationPolicy.CONTRACT_VERSION`
- 稳定性：v1 冻结

本契约是 Runtime 硬规则，不是 Planner、Reviewer 或 Finalizer 的提示词建议。模型可以识别证据缺口并建议下一步，但不能改变本契约的回答许可和循环终止语义。

## 核心原则

> Agent Loop 是证据增强循环，不是证据门禁。

Agent Loop 的目标是持续获取信息、验证假设、修正计划并提高答案可靠性。证据不完整首先触发继续探索；探索结束后，只要已有可用事实，就必须输出事实支持范围内的阶段性分析。

Runtime 必须分别处理三个概念：

| 概念 | 含义 | 影响 |
| --- | --- | --- |
| Evidence Availability | 是否存在非空、可追溯的事实结果 | 决定是否存在事实分析基础 |
| Evidence Completeness | 事实是否覆盖任务的全部维度 | 影响置信度、限制和后续行动 |
| Answer Capability | 当前事实能够支持哪些结论 | 决定回答范围，不由完整度直接否决 |

不可变规则：

> Evidence existence determines analyzable scope. Evidence completeness determines confidence and limitations; it does not directly determine answer permission.

只要 MCP 工具返回非空结果，即使 Tool Result Reviewer 将结果评价为不完整、部分满足或需要继续检索，该结果仍然是可用事实。Reviewer 的评价不能删除原始结果，也不能把部分证据转换成无证据。

## 标准决策

`decision` 只能使用以下稳定枚举值：

| 决策 | `answerAllowed` | `continueLoop` | 语义 |
| --- | ---: | ---: | --- |
| `COMPLETE` | `true` | `false` | 当前证据足以完成任务，进入最终合成 |
| `RETRIEVE_MORE` | `true` | `true` | 存在实质缺口，并且仍有工具、次数和时间预算，继续探索 |
| `ANALYZE_WITH_LIMITATIONS` | `true` | `false` | 已有可用事实，但继续探索不可行或预算已耗尽，输出阶段性分析和限制 |
| `NO_EVIDENCE` | `false` | `false` | 证据必需型任务在探索后仍没有任何可用事实 |
| `EXACT_RESULT_UNAVAILABLE` | `false` | `false` | 用户明确要求精确结果，但没有取得支持该精确结果的事实 |
| `BLOCKED_AUTHORIZATION` | `false` | `false` | 高风险操作缺少必要授权 |

`NO_EVIDENCE`、`EXACT_RESULT_UNAVAILABLE` 和 `BLOCKED_AUTHORIZATION` 表示允许阻断，不表示必须生成固定拒答模板。Runtime 仍应返回已经确认的失败事实、授权状态、缺口和可执行的下一步。

## 决策优先级

Runtime 必须按以下顺序作出确定性决策：

1. 缺少必要授权：`BLOCKED_AUTHORIZATION`。
2. 证据已满足任务：`COMPLETE`。
3. 存在实质缺口且仍可探索：`RETRIEVE_MORE`。
4. 已存在非空事实：`ANALYZE_WITH_LIMITATIONS`。
5. 任务不强制外部证据：`ANALYZE_WITH_LIMITATIONS`，并明确标注假设或通用建议。
6. 严格精确结果无法取得：`EXACT_RESULT_UNAVAILABLE`。
7. 证据必需型任务完全无事实：`NO_EVIDENCE`。

不得把步骤 4 和步骤 7 颠倒。任何“已有非空工具结果但因指标不全进入 `NO_EVIDENCE`”的行为都属于契约违规。

## 标准消息结构

Runtime metadata 中必须保存当前决策和完整历史：

```json
{
  "evidenceAugmentationContractVersion": "evidence_augmentation_decision_v1",
  "evidenceAugmentationDecision": "ANALYZE_WITH_LIMITATIONS",
  "evidenceAugmentationAnswerAllowed": true,
  "evidenceAugmentationContinueLoop": false,
  "evidenceAugmentationHistory": [
    {
      "contractVersion": "evidence_augmentation_decision_v1",
      "iteration": 2,
      "decision": "ANALYZE_WITH_LIMITATIONS",
      "answerAllowed": true,
      "continueLoop": false,
      "reason": "Usable evidence exists; remaining gaps affect confidence and limitations, not answer permission."
    }
  ]
}
```

字段要求：

| 字段 | 必填 | 说明 |
| --- | ---: | --- |
| `contractVersion` | 是 | 固定为 `evidence_augmentation_decision_v1` |
| `iteration` | 是 | 从 1 开始的证据循环轮次 |
| `decision` | 是 | 本契约定义的稳定枚举 |
| `answerAllowed` | 是 | Runtime 的回答许可，不由 Reviewer 覆盖 |
| `continueLoop` | 是 | 是否继续进行计划改写、检索或验证 |
| `reason` | 是 | 可审计原因；不能作为业务事实 |

## Agent Loop 闭环

```text
用户目标
  ↓
TaskContract
  ↓
计划与工具执行
  ↓
持久化原始结构化结果
  ↓
Evidence Availability / Completeness / Capability 评估
  ↓
evidence_augmentation_decision_v1
  ├─ RETRIEVE_MORE → 改写计划并继续执行
  ├─ COMPLETE → 最终合成
  ├─ ANALYZE_WITH_LIMITATIONS → 阶段性分析＋限制说明
  └─ BLOCKED* → 阻断事实＋缺口＋下一步
  ↓
Answer Lifecycle
  ↓
用户可见答案和审计 metadata
```

如果 Planner 设置 `maxRewriteTimes=0`，但 MCP 已返回非空结果且存在实质缺口，Runtime 可以保留一次受全局次数、工具调用、时间和成本预算约束的补充轮次。Planner 的局部建议不能关闭 Runtime 的证据增强职责。

循环不得无限执行。达到 Runtime 硬预算、没有匹配工具、补充计划重复或继续执行无法增加信息量时，应结束探索：

- 已有事实：`ANALYZE_WITH_LIMITATIONS`。
- 完全无事实：根据 `TaskContract.evidenceRequirement` 进入通用建议、`NO_EVIDENCE` 或 `EXACT_RESULT_UNAVAILABLE`。

## 组件权限边界

### Planner

- 生成计划、参数、依赖和候选补充路径。
- 可以声明缺口和建议重写次数。
- 不能把非空工具结果声明为不存在。
- 不能通过 `maxRewriteTimes=0` 强制 Runtime 丢弃已有证据或拒答。

### Tool Result Reviewer

- 评价相关性、完整度、冲突和缺失维度。
- 可以建议扩展查询或调用其他工具。
- 不能删除、覆盖或降格原始结构化结果。
- `satisfied=false` 不等于 `evidenceAvailable=false`。

### Runtime

- 保存原始工具结果。
- 执行本契约的确定性决策。
- 管理循环预算、重复调用抑制和授权边界。
- 保证部分证据最终进入答案合成。

### Finalizer

- `answerAllowed=true` 时必须生成有内容的回答。
- `ANALYZE_WITH_LIMITATIONS` 必须先分析已知事实，再列出限制。
- 不得把限制说明替换成整体拒答。
- 不得因 Reviewer、Quality Evaluator 或后置模型更保守而覆盖已有业务结果。

## 与其他稳定契约的关系

| 契约 | 关系 |
| --- | --- |
| `task_contract_v1` | 定义任务类型、证据要求和是否允许假设 |
| `mcp_result_evidence_policy_v1` | 判断 MCP 是否存在非空结果 |
| `evidence_augmentation_decision_v1` | 决定继续探索、完成、带限制分析或阻断 |
| `task_result_assessment_v1` | 描述执行、证据和任务完成质量 |
| `runtime_answer_candidate_v1` | 管理答案候选的生成、验证和选择生命周期 |
| `answer_evidence_disclosure_v1` | 把证据状态和限制展示给用户 |
| `agent_runtime_fact_grounding_v1` | 约束最终答案不得新增、改名或篡改工具事实 |

优先级：

1. 授权和安全策略。
2. 原始工具结构化事实。
3. 本证据增强决策。
4. Task Result Assessment。
5. Reviewer 和 Quality 建议。

Reviewer、Quality Evaluator 和提示词不得覆盖前三级。

## 禁止行为

- MCP 已返回行、字段、指标或对象，却以“证据不足”为由拒绝整个分析任务。
- 因缺少一个指标而丢弃其他已返回指标。
- 把 `PARTIAL_EVIDENCE` 映射为 `answerAllowed=false`。
- 用 Reviewer 的自然语言判断覆盖原始 MCP 输出。
- 补充检索失败后隐藏前一轮成功结果。
- 达到循环预算后输出空答案。
- 把 Planner 计划、假设或 Reviewer 评价当作事实证据。
- 为单一业务、工具名或问题关键词编写绕过本契约的硬编码分支。

## 验收场景

### 场景一：部分结果且可以继续探索

- MCP 返回非空行。
- 缺少一个分析维度。
- 存在可用工具和预算。
- 期望：`RETRIEVE_MORE`。

### 场景二：部分结果且无法继续探索

- MCP 返回非空行。
- 缺少部分指标。
- 工具或预算已耗尽。
- 期望：`ANALYZE_WITH_LIMITATIONS`、`answerAllowed=true`，答案包含已知分析和限制。

### 场景三：Reviewer 不接受但原始数据非空

- MCP 执行返回非空数据。
- Reviewer 输出 `satisfied=false`。
- 期望：证据仍为 available；不得进入 `NO_EVIDENCE`。

### 场景四：工具真实失败且没有结果

- MCP 超时或失败。
- 没有任何结构化数据。
- 证据必需型任务且无其他事实。
- 期望：`NO_EVIDENCE`，展示失败事实和下一步。

### 场景五：高风险操作未授权

- 工具调用需要确认。
- 用户尚未授权。
- 期望：`BLOCKED_AUTHORIZATION`，不得执行操作。

### 场景六：通用设计任务

- `TaskContract.evidenceRequirement=OPTIONAL`。
- 没有工具证据。
- 期望：允许给出通用方案并明确假设，不因没有 MCP 结果拒答。

## 版本与兼容规则

v1 冻结内容：

- 契约版本字符串。
- 六个决策枚举名称及其基本语义。
- `answerAllowed` 和 `continueLoop` 的含义。
- “非空 MCP 结果不允许因完整度不足而整体拒答”的硬规则。
- Runtime 对原始工具结果的保存责任。

兼容性要求：

- v1 内只能增加可选 metadata 字段，不能删除或重命名现有字段。
- 不得改变现有决策的 `answerAllowed` / `continueLoop` 基本语义。
- 新增决策或改变优先级必须发布新契约版本。
- 契约版本变化必须同步修改代码常量、Runtime metadata、提示词、文档和自动化测试。
- 历史记录必须保留其原始 `contractVersion`，不得静默升级或改写。

## 自动化验证要求

至少覆盖：

- 决策优先级单元测试。
- 非空部分证据强制允许回答。
- `maxRewriteTimes=0` 时的有界增强覆盖。
- 真正无结果时保留阻断边界。
- 最终合成不得返回拒答或空答案。
- metadata 中当前决策、历史和契约版本一致。

当前基线：`chatchat-agents` 全量测试必须通过。
