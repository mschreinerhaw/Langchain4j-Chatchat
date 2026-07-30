# Agent Runtime 模板候选评定与执行满意度契约

## 契约身份

- 契约名称：Agent Runtime Template Candidate Evaluation and Execution Satisfaction Contract
- MCP 候选策略：`mcp_high_recall_candidates_runtime_semantic_review_v1`
- Runtime 候选选择协议：`runtime_template_selection.v2`
- 模板执行满意度协议：`template_execution_satisfaction.v1`
- 修复策略：`ONE_REPAIRED_PLAN_EXECUTION`

本文档定义 MCP 模板发现、Agent Runtime 候选评定、模板参数绑定、SQL/API/HTTP/SSH
模板执行、执行结果复核和一次修复重做之间的统一契约。

本契约的基本边界是：

> MCP Server 负责授权范围内的高召回和确定性执行；模型负责基于证据进行语义评定；
> Agent Runtime 负责候选准入、参数校验、执行门控、重试上限和审计。

模型不拥有授权、安全策略、数据源选择或任意工具执行权。

## 适用范围

本契约适用于以下模板发现和执行链路：

- SQL 数据源模板：`sql_datasource_template_query -> sql_query_execute/sql_script_execute`
- 数据库业务查询模板：`database_query_template_query -> database_query/sql_query_execute`
- API 服务模板：`api_template_query -> api_template_execute`
- HTTP 端点模板：`http_endpoint_template_query -> http_request_execute`
- SSH/Linux 模板：`ssh_template_query -> linux_command_execute`

模板参数的来源、证据和编译规则同时遵守
[`Agent Runtime 模板参数传递契约`](agent-runtime-template-argument-contract.md)。

## 职责边界

| 层级 | 必须负责 | 不得负责 |
| --- | --- | --- |
| MCP Server | 权限、租户、环境、资产类型、启用状态、执行能力和显式排除等硬约束；返回结构化候选及执行绑定 | 以相关性分数替代 Runtime 最终语义判断；生成业务最终结论 |
| 检索索引 | 为候选提供 Lucene/BM25 等弱排序先验和检索诊断 | 把未命中直接解释为未授权、不可执行或业务上不相关 |
| 模型 Reviewer | 根据用户目标和返回证据评定候选；判断执行结果是否满足业务目标；指出缺失参数 | 引入 MCP 未返回的模板；猜测参数；修改授权、环境或安全边界 |
| Agent Runtime | 校验模型决策、投影候选集、绑定参数、选择真实执行器、限制重做次数、保存证据链 | 把模型自然语言当作工具执行事实 |
| 执行工具 | 使用已授权模板、参数和执行上下文进行确定性执行，返回结构化成功或错误结果 | 自行扩大资产范围；以 HTTP 200 或传输成功冒充业务成功 |

## 标准状态流

```text
用户目标
  -> MCP 硬约束候选召回
  -> Runtime 模型候选评定
  -> Runtime 投影已选模板
  -> 确定性参数与执行上下文校验
  -> SQL/API/HTTP/SSH 模板执行
  -> Runtime 模型执行满意度复核
       -> 满足：进入后续分析或最终总结
       -> 不满足：最多一次参数修复或模板重选
            -> 修复后满足：进入后续分析
            -> 仍不满足：终止执行闭环并披露证据缺口
```

模板发现 observation 不是业务执行证据。只有执行工具的结构化结果才能支持业务结论。

## MCP 高召回候选契约

### 硬约束

MCP Server 必须在返回候选前执行以下硬约束：

- 当前租户、用户和角色有权发现该模板。
- 模板和对应资产处于启用状态。
- `env`、资产范围、模板类型和执行器类型兼容。
- 模板具备可解析的执行绑定。
- 数据库模板具备可确定的数据源或 `executionContext`。
- 模板未出现在 `excludeTemplateIds` 中。
- 模板不违反只读、风险等级或其他治理策略。

不满足硬约束的模板不得交给模型评定。

### 弱排序先验

Lucene/BM25、注册表相关度、分类相关度和同义词命中只作为弱排序先验，不是语义准入条件。

模板发现结果的 `selectionPolicy` 必须表达以下语义：

```json
{
  "engine": "mcp_high_recall_candidates_runtime_semantic_review_v1",
  "runtimeSemanticReviewRequiredWhenMultiple": true,
  "mcpRelevanceIsAdmissionFilter": false
}
```

当索引无命中但存在满足硬约束的候选时，MCP 可以返回这些候选并标记
`fallbackUsed=true`。不得仅因索引无命中返回 `NO_MATCHING_TEMPLATE`。

### 返回数量

高召回不等于无限制传输。MCP 可以按照调用方 `limit` 截断返回，但必须设置可审计的
候选数量、返回数量和截断状态。候选规模过大时，应优先通过资产、类型、业务分类和执行能力等
硬范围缩小集合，再交给 Runtime 评定。

## Runtime 候选证据评定

当模板发现返回多个候选时，Reviewer 应对每个候选输出：

```json
{
  "selected_template_ids": ["query_margin_balance_latest"],
  "rejected_template_ids": ["query_market_overview"],
  "template_evaluations": [
    {
      "template_id": "query_margin_balance_latest",
      "relevance": 0.96,
      "evidence_fit": 0.94,
      "parameter_readiness": 0.90,
      "total_score": 0.94,
      "decision": "accept",
      "reasons": [
        "返回融资余额、融券余额和观察日期，覆盖用户所需指标"
      ],
      "missing_parameters": []
    }
  ]
}
```

评分维度为：

| 维度 | 含义 |
| --- | --- |
| `relevance` | 模板业务语义与用户目标的匹配程度 |
| `evidence_fit` | 模板声明的输出字段、数据口径和时间范围能否形成所需证据 |
| `parameter_readiness` | 必填参数是否已经由用户输入或前置工具证据提供 |
| `total_score` | Reviewer 对前三项和风险、执行成本等因素的综合评分 |

分值范围为 `0.0..1.0`。兼容百分制输入时，Runtime 将其归一化为 `0.0..1.0`。

如果 Reviewer 没有显式返回 `selected_template_ids`，Runtime 可以从
`template_evaluations` 中选择 `decision=accept|selected` 且 `total_score >= 0.6`
的候选，并按分数降序投影。

### 不可突破的准入规则

- 只有 MCP 当前响应 `templates[]` 中存在的模板 ID 才能被选择。
- 模型发明、拼接或引用历史响应中的模板 ID，不得进入当前候选集。
- `rejected_template_ids` 只影响当前证据链，不修改模板注册状态。
- MCP 的 `decisionScore` 不能覆盖 Runtime 的证据评定结果。
- Runtime 必须先投影已选候选，再解析后续依赖绑定。

投影后的发现结果写入：

```json
{
  "runtimeTemplateSelection": {
    "schemaVersion": "runtime_template_selection.v2",
    "candidateCount": 2,
    "selectedCount": 1,
    "selectedTemplateIds": ["query_margin_balance_latest"],
    "rejectedTemplateIds": ["query_market_overview"],
    "candidateEvaluations": [],
    "selectionAuthority": "runtime_evidence_model_review",
    "mcpScoresAreWeakPriors": true
  }
}
```

后续 `$.templates[0].templateId` 等绑定必须读取投影后的候选集，不能继续读取 MCP 原始第一名。

如果 Reviewer 没有返回任何候选准入决定，Runtime 会记录
`runtimeTemplateSelectionApplied=false` 和原因。该行为仅用于兼容旧 Reviewer；
新协议接入在多候选场景必须返回显式选择或逐候选评定。

## 执行前确定性门控

进入执行工具前，Runtime 必须完成：

1. 模板 ID 存在于已完成的模板发现结果中。
2. 模板声明的真实执行器在本次 `availableTools` 中。
3. `parameterSchema.required` 中的所有参数均已绑定。
4. 每个业务参数都能回查到用户原文、成功工具输出或允许的确定性推导。
5. 参数类型、别名和容器结构已经按模板 Schema 编译。
6. SQL 数据源或 API/HTTP/SSH 目标上下文唯一且已授权。
7. 不存在 raw SQL、raw URL、raw shell 或未解析占位符绕过模板契约。

任一门控失败时，不得调用执行工具。

## 模板执行满意度复核

执行工具返回后，Reviewer 必须区分：

- 传输是否成功。
- 工具操作是否成功。
- 返回数据是否非空、有效且具有正确结构。
- 数据时间、范围和字段是否满足用户目标。
- 是否存在参数错误、权限错误、业务错误码或证据缺口。

Reviewer 输出：

```json
{
  "template_execution_satisfied": false,
  "reason": "接口返回缺少客户标识，当前结果不能回答用户问题",
  "missing_parameters": ["customerId"],
  "retry_input_changes": {
    "parameters": {
      "customerId": "C001"
    }
  },
  "reselect_template": false
}
```

Runtime 将其规范化为：

```json
{
  "templateExecutionReview": {
    "schemaVersion": "template_execution_satisfaction.v1",
    "satisfied": false,
    "missingParameters": ["customerId"],
    "retryInputChanges": {
      "parameters": {
        "customerId": "C001"
      }
    },
    "templateReselectionRequired": false,
    "retryPolicy": "ONE_REPAIRED_PLAN_EXECUTION",
    "unchangedRetryForbidden": true
  },
  "templateExecutionRetryRequested": true,
  "templateExecutionRetryLimit": 1
}
```

`satisfied=true` 只表示该次执行结果足以继续当前任务，不代表最终答案已经完成，也不改变原始工具事实。

## 一次修复重做契约

模板执行不满足时，Runtime 最多允许一次修复后的计划执行，不受原计划更高重写次数影响。

允许的修复方式只有：

1. **参数修复**：补充 `missingParameters`，并应用有证据来源的 `retryInputChanges`。
2. **模板重选**：重新执行模板发现，让候选评定层选择另一个已授权模板。

重做必须遵守：

- 禁止以相同模板、相同参数和相同执行上下文进行无变化重试。
- `retryInputChanges` 中的值必须来自用户原文或已完成工具证据，不能由模型猜测。
- 无法获得必填参数时，应要求用户补充或重选不需要该参数的模板。
- `templateReselectionRequired=true` 时，不得重试原 `templateId`。
- 重选时应把原模板加入 `excludeTemplateIds`，保留失败证据，并实质性调整检索意图。
- 第二次执行仍不满足时，必须停止重做，输出执行阻断和缺失证据，不得进入循环。

## 查询子句上限恢复

当模板索引返回 `QUERY_CLAUSE_LIMIT_EXCEEDED` 时，MCP Server 返回：

```json
{
  "status": "MODEL_REVIEW_REQUIRED",
  "resultCode": "QUERY_CLAUSE_LIMIT_EXCEEDED",
  "retryable": true,
  "retrievalReview": {
    "nextAction": "REWRITE_TEMPLATE_SEARCH_KEYWORDS_AND_RETRY",
    "rewritePolicy": {
      "maxKeywords": 8,
      "maxAliases": 4,
      "preserveOriginalIntent": true,
      "removeTemplateRegistryMetadata": true
    }
  }
}
```

此时模型必须基于原始用户目标生成紧凑检索条件：

- 保留一个明确的 `intent`。
- 最多 8 个有区分度的 `keywords`。
- 最多 4 个 `intentAliases`。
- 删除模板注册表描述、重复的双语扩展和无关候选文本。
- 在检索返回可执行模板前，不得进入模板执行步骤。

该恢复流程处理检索表达式过大，不代表放宽授权或执行边界。

## 失败语义

| 状态 | Runtime 行为 |
| --- | --- |
| `NO_MATCHING_TEMPLATE` | 禁止依赖执行；可改写意图重新检索或披露模板缺口 |
| `QUERY_CLAUSE_LIMIT_EXCEEDED` | 要求模型精简关键词并重试发现 |
| 缺少必填参数 | 执行前阻断，或发布一次参数修复契约 |
| 数据源上下文不唯一 | 禁止 SQL 执行，必须从模板绑定或资产发现补齐唯一上下文 |
| 工具传输成功但业务失败 | 记录结构化失败，不能作为业务成功证据 |
| 执行成功但业务不满足 | 允许一次参数修复或模板重选 |
| 修复后仍不满足 | 终止闭环，输出执行阻断和证据限制 |

## 审计 Metadata

Runtime 至少应保留：

- `runtimeTemplateSelectionApplied`
- `runtimeTemplateCandidateCount`
- `runtimeTemplateSelectedCount`
- `runtimeSelectedTemplateIds`
- `runtimeTemplateCandidateEvaluations`
- `runtimeTemplateSelectionReason`
- `templateExecutionReview`
- `templateExecutionRetryRequested`
- `templateExecutionRetryLimit`
- `templateExecutionMissingParameters`
- `templateExecutionRetryInputChanges`
- `templateReselectionRequired`
- `templateExecutionRetryStrategy`

审计记录必须同时保留原始 MCP 候选、模型评定、投影后候选、实际执行模板、实际参数来源、
执行结果和修复结果。不得只保留最终模板 ID。

## 与其他契约的关系

| 契约 | 关系 |
| --- | --- |
| `template_parameter_protocol_v2` | 定义模板参数证据来源和 Runtime 编译规则 |
| `runtime_template_binding.v1` | 定义模板发现结果到真实执行器的绑定 |
| `agent_runtime_fact_grounding_v1` | 定义工具事实不可被模型修改的边界 |
| `evidence_augmentation_decision_v1` | 定义证据不足时是否继续检索或阶段性回答 |
| `agent_runtime_template_dsl.v1` | 定义多步骤模板的注册、执行和结构化结果 |

本契约不能放宽上述契约的安全、事实或授权边界。

## 最小测试要求

每次修改本契约或相关实现，至少覆盖：

1. Lucene 无命中时仍返回满足硬约束的授权候选。
2. Lucene 部分命中只改变排序，不过滤其他授权候选。
3. 模型高分候选能够覆盖 MCP 原始第一名。
4. 模型发明的模板 ID 不得进入候选集。
5. 后续 SQL/API 执行绑定使用投影后的模板。
6. 空模板结果阻止依赖执行。
7. SQL 数据源上下文唯一且可追溯。
8. API/HTTP 业务错误不能被 HTTP 200 掩盖。
9. 缺失参数被写入执行满意度契约。
10. 不满意执行最多重做一次。
11. 模板重选禁止再次使用被拒绝模板。
12. 查询子句超限触发紧凑关键词重检索。

## 版本演进

- 候选准入语义、评分阈值、执行满意度字段或重做上限发生不兼容变化时，必须升级对应协议版本。
- 协议版本变化必须同步修改 Runtime metadata、模型 Reviewer/rewriter 提示、MCP 返回字段、本文档和自动化测试。
- 历史执行记录保留原协议版本，不得静默重算模型评分或改写候选选择。
