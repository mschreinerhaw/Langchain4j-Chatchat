# Required Tool Execution Contract

本文档定义 Agent Runtime 对用户勾选工具、能力、资产范围的强制执行契约。该契约属于运行时安全边界，不是 planner prompt 建议，也不是模型可自行裁剪的业务规则。

工具执行完成后的事实使用和模型总结必须同时遵守 [`Agent Runtime 事实落地契约`](../../docs/agent-runtime-fact-grounding-contract.md)。

## Engineering Values

Agent Runtime 必须坚持契约精神和朴实价值。

契约精神：

- 用户明确勾选的工具、能力、资产范围就是执行契约。
- Runtime 必须履约，不能让模型用“我认为不需要”来否决用户选择。
- 如果工具执行失败、无权限或参数不足，系统必须记录真实 observation，而不是用总结掩盖未执行事实。
- 任何最终回答都必须能追溯到已完成的工具 observation 或明确的阻断原因。

朴实价值：

- 用户点了，就要执行。
- 执行不了，就诚实说明为什么执行不了。
- 没有执行结果，就不能包装成已经完成分析。
- 没有事实证据的回答就是臆造或猜测，不能包装成结论。
- 不为了跑通单个 case 写死业务类型、工具名或捷径逻辑。
- 系统可信度优先于模型回答的流畅度。

这条原则是 Agent Runtime 的行为底线：模型可以聪明，但不能越权；Runtime 可以自动修复计划，但不能伪造完成；最终总结可以简洁，但不能替代真实执行。

## Core Principle

用户勾选工具 = 强制执行约束，不允许模型否决。

模型只负责三件事：

- 制定执行计划 `plan`
- 对工具执行结果做 `review`
- 基于已完成工具结果生成最终总结 `final_answer`

工具是否必须执行不由模型最终决定。只要用户在页面勾选了工具、能力或资产范围，Runtime 必须把对应工具加入强制执行队列，并兜底保证执行闭环。

## Runtime Protocol

Runtime 在进入 planner/reviewer/finalizer 前必须构造统一协议：

```json
{
  "executionPolicy": {
    "userSelectedToolsMustRun": true,
    "modelCanSuggestSkip": false,
    "finalAnswerRequiresToolCompletion": true,
    "factGroundingContractVersion": "agent_runtime_fact_grounding_v1"
  },
  "factGroundingContract": {
    "contractVersion": "agent_runtime_fact_grounding_v1",
    "factAuthority": "TOOL_STRUCTURED_OUTPUT",
    "modelRole": "INTERPRET_AND_SUMMARIZE_WITHIN_FACT_BOUNDARY",
    "runtimeRole": "PRESERVE_VALIDATE_AND_REWRITE_ON_FACT_MUTATION"
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

字段含义：

- `userSelectedToolsMustRun`: 用户勾选项是否形成强制执行约束。
- `modelCanSuggestSkip`: 必须为 `false`。模型可以解释风险和参数缺失，但不能否决强制工具执行。
- `finalAnswerRequiresToolCompletion`: 必须工具未形成终态 observation 前，禁止进入最终总结。
- `requiredToolExecutions[].toolName`: Runtime 解析后的真实工具名。
- `requiredToolExecutions[].source`: 约束来源，例如 `USER_SELECTED`、`RUNTIME_WORKFLOW`、`RUNTIME_POLICY`。
- `requiredToolExecutions[].required`: 是否强制执行。用户勾选项必须为 `true`。

## Planning Rules

Runtime 执行顺序：

1. 读取用户勾选工具、能力、资产范围。
2. 解析为真实可执行工具名。
3. 生成 `requiredToolExecutions`。
4. 调用模型生成 `InterpretationPlan`。
5. 校验计划是否覆盖所有 pending required tools。
6. 如果计划缺失 required tool，Runtime 必须自动修复、重写或继续按强制队列执行，不能直接总结。

模型计划只能补充、排序、参数化 required tools。以下模型输出均视为无效：

- 把 required tool 替换成 `reasoning`
- 把 required tool 替换成 `final_answer`
- 声称工具“不需要执行”
- 声称工具“不在当前 MCP 服务器内”从而跳过 Runtime 已授权的桥接执行工具
- reviewer 跳过 required tool 直接进入总结

## Tool Scope Precedence

当用户或 Runtime 已选择具体工具时，工具自身声明的作用域、目标类型和执行契约优先于模型生成的参数。

例如专用 discovery tool 已经声明目标域时：

- Runtime 必须以该工具声明的 `targetKind` 为准。
- 如果模型给出的 `finalDecision` 或 `candidates[].targetKind` 与工具作用域冲突，Runtime 应修正为工具声明值。
- Runtime 不应把模型错误参数原样下发，导致已选工具因为明显可修复的路由字段失败。

这条规则仍然是通用契约，不允许写成某个业务问题、某个模板名或某个查询场景的特例。工具元数据和已选工具作用域是约束来源，模型参数只是候选输入。

## Final Answer Gate

`final_answer` 只能在所有 required tools 形成终态 observation 后生成。

终态 observation 包括：

- 执行成功 observation
- 执行失败 error observation
- 权限不足 permission denied observation

非终态状态不满足 Gate，例如：

- 仅有模型推理
- 仅有计划步骤
- 等待用户确认
- 没有工具 trace 或 runtime observation

如果仍有 required tool 未形成终态 observation，Runtime 必须拒绝最终总结，并返回：

```text
PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED
```

## No Hardcoding Rule

禁止通过硬编码业务类别来决定工具是否必须执行，例如：

- 哪类问题必须查资产
- 哪类问题必须查 SQL
- 哪类问题必须查文档
- 哪类查询模板必须桥接某个业务工具

正确方式是使用统一协议表达 Runtime 约束。业务含义、工具类别、资产范围都只能影响 `requiredToolExecutions` 的生成和参数化，不能绕过通用 Gate。

## Component Responsibilities

Planner:

- 生成可执行 `InterpretationPlan`
- 覆盖 Runtime 提供的 pending required tools
- 为 required tools 提供参数、依赖和绑定

Reviewer:

- 只审查已完成工具 observation
- 不允许跳过 required tools
- 不允许把未执行工具解释为“无需执行”

Runtime:

- 读取用户勾选项
- 生成 `requiredToolExecutions`
- 校验 plan 覆盖强制工具
- 必要时修复或强制推进工具执行
- 在 required tools 未形成终态 observation 前阻断 `final_answer`

Finalizer:

- 只基于已完成工具 observation 总结
- 遇到 `PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED` 时返回阻断结果，不合成业务结论
- 如果没有可追溯事实证据，必须返回证据不足说明，不能生成确定性业务判断
- 必须让模型基于原始结构化事实进行总结，而不是用固定模板替代模型分析。
- 必须校验模型是否新增、改名、替换或否定工具事实；发现篡改时基于原始证据触发重写。

## Evidence Grounding Rule

所有最终回答都必须有事实依据。没有事实依据的回答按臆造处理。

有效事实依据包括：

- required tool 的成功结构化 observation。
- required tool 的失败、权限不足或参数不足 observation。
- 用户明确提供的事实。
- 带来源标识的文档、知识库或网页证据。

无效依据包括：

- planner 的计划文本。
- reviewer 的主观判断。
- 模型常识、经验推断或概率猜测。
- 未执行工具时对结果的预设。
- 只有模板发现或资产发现、没有执行 observation 的信息。

当证据不足时，Finalizer 只能输出阻断说明、证据缺口和下一步所需工具/参数，不能输出业务结论。

## Answer Evidence Disclosure

最终回答必须在用户可见正文中显式区分证据状态，不能只写入 metadata。

回答开头必须声明以下三类之一：

- `有事实依据的分析`：已经存在成功工具返回的结构化结果，或存在带来源标识的文档、知识库、网页、执行证据。
- `执行阻断/证据不足`：工具执行失败、权限不足、参数不足、强制工具流程未完成，回答可说明失败事实、流程状态和排查参考，不能作为确定性业务结论。
- `证据不足/推测`：没有可追溯事实来源，回答可作为待验证的推测、分析思路或下一步建议，不能作为确定性业务结论。

Finalizer 必须同时写入：

```json
{
  "answerEvidenceDisclosureVersion": "answer_evidence_disclosure_v1",
  "answerRequiresEvidenceDisclosure": true,
  "answerEvidenceStatus": "GROUNDED_ANALYSIS | EXECUTION_BLOCKED | EVIDENCE_INSUFFICIENT",
  "answerEvidenceLabel": "有事实依据的分析 | 执行阻断/证据不足 | 证据不足/推测"
}
```

用户端展示时必须保留正文中的证据状态声明。任何没有工具结果、没有来源证据、没有用户明确事实支撑的业务判断，都必须标记为 `证据不足/推测`。这类内容可以作为参考性分析和后续行动建议，但不得包装成确定性分析。

## Invariant

模型是规划者和总结者，不是执行权限裁判。

用户勾选是强制执行契约，Runtime 必须兜底保证执行闭环。
