# chatchat-agents 模板设计与契约

本文档定义 `chatchat-agents` 模块的模板设计、计划执行边界和强制工具执行契约。

## 模块职责

`chatchat-agents` 是 Agent Runtime、planner、InterpretationPlan、工具编排、结果 review 和最终总结的核心模块。

模型只负责三件事：

- 制定执行计划 `plan`
- 对工具结果做 `review`
- 基于已完成工具结果生成 `final_answer`

工具是否必须执行，不由模型最终裁决。

## 当前模板设计

- `InterpretationPlan` 是模型计划的结构化表达。
- Runtime 读取用户勾选工具、能力和资产范围，生成 `requiredToolExecutions`。
- planner 可以补充、排序、参数化工具步骤，但不能删除用户强制要求的工具。
- rewriter 可以修复计划，但必须保留 pending required tools。
- finalizer 只能基于已完成工具 observation 或明确阻断原因生成回答。

## Required Tool Execution Contract

用户勾选工具 = 强制执行约束。

Runtime 必须生成并维护如下协议：

```json
{
  "executionPolicy": {
    "userSelectedToolsMustRun": true,
    "modelCanSuggestSkip": false,
    "finalAnswerRequiresToolCompletion": true
  },
  "requiredToolExecutions": [
    {
      "toolName": "asset_query",
      "source": "USER_SELECTED",
      "required": true
    }
  ]
}
```

## Runtime Gate

`final_answer` 只能在所有 required tools 形成终态 observation 后生成。

终态 observation 包括：

- 成功 observation
- 失败 error observation
- 权限不足 permission denied observation

如果仍有 required tool 未执行闭环，Runtime 必须返回：

```text
PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED
```

## 计划修复契约

- 如果模型计划遗漏 required tool，Runtime 必须自动修复计划或阻断执行。
- 如果模型生成 `final_answer` 早于 required tool 终态，Runtime 必须拒绝该总结。
- 如果 InterpretationPlan 生成失败，但 mandatory workflow tools 仍未闭环，任务必须以失败终态结束，不能卡在运行中。

## Tool Scope Contract

当用户或 Runtime 已选择具体工具时，工具自身声明的作用域、目标类型和执行契约优先于模型生成的参数。模型给错 `targetKind`、`finalDecision` 或候选集时，Runtime 应按工具契约修正可修正参数。

## MCP Interaction Contract

MCP 交互必须遵循两条主路径：

- `工具类型/能力描述 -> 找模板 -> 判断模板 -> 执行模板 -> 总结`
- `找资产 -> 判断资产 -> 读取资产下关联模板 -> 判断模板 -> 执行模板 -> 总结`

模板名不是 Agent Runtime 直接调用的 workflow 工具名。模板名必须作为 `template` / `templateId` 交给实际执行者。若模型把模板名写成 `tool_name`，Runtime 必须根据模板元数据中的执行绑定修复为真实执行者；不能修复时必须失败并记录 observation。

## No Hardcoding Rule

禁止用业务类型硬编码执行路径。必须通过 `requiredToolExecutions`、工具元数据、模板元数据和 Runtime policy 表达执行要求。
> Intent Ensemble Retrieval contract: see `../../docs/intent-ensemble-retrieval-contract.md`.
> Planner/Rewriter must emit scored `filters.intentCandidates`; Runtime/Resolver selects all candidates with `score >= 0.75`, falls back to Top 2 only when none reaches threshold, merges multi-query expansions and the original user question into `queryTerms/retrievalSignals`, and must not invent `assetName` from aggregate natural language.
