# Agent Runtime 事实落地契约

本文档定义 Agent Runtime 在工具执行、模型总结和最终回答之间必须遵守的事实一致性契约。

- 契约版本：`agent_runtime_fact_grounding_v1`
- 代码实现：`chatchat-agents/src/main/java/com/chatchat/agents/runtime/AgentRuntimeFactGroundingContract.java`
- 适用范围：planner、tool result reviewer、InterpretationPlan final synthesis、answer reviewer，以及所有消费结构化工具结果的后续 Runtime 组件
- 契约性质：Runtime 强制约束，不是提示词建议，也不是模型可以忽略的写作偏好

## 核心原则

> 工具结构化结果定义不可修改的事实边界；模型负责在事实边界内理解、关联和总结；Agent Runtime 负责保存事实、校验回答，并在模型篡改事实时触发重写。

该原则包含三项不可替换的职责：

1. 工具负责提供可追溯事实。
2. 模型负责解释事实，而不是创造工具事实。
3. Runtime 负责保证从 observation 到最终回答的事实一致性。

Runtime 不能用固定模板替代模型的分析能力，也不能只依赖提示词期待模型自觉遵守。正确实现必须同时保留模型总结和确定性事实校验。

## 角色边界

### 工具

工具结构化输出是本次执行的权威事实源。适用事实包括但不限于：

- 数据库、Schema、表名、字段名和数据库明确返回的分层字段；
- 行数、返回行数、统计指标和完整性状态；
- `complete`、`possiblyTruncated`、`catalogTruncated` 等截断或完备性标记；
- 命令退出码、stdout、stderr、步骤状态和失败原因；
- HTTP 状态码、响应体及结构化业务字段；
- 文档、知识库和网页检索返回的带来源证据。

### 模型

模型可以：

- 总结工具返回的事实；
- 解释表、字段、指标或执行结果与用户问题的关系；
- 在证据允许的范围内归纳业务含义；
- 明确提出推断、建议和下一步检索方案。

模型不得：

- 新增、改名、替换或否定工具返回的事实；
- 把未返回的数据库、Schema、表、字段、分层或指标写成检索结果；
- 根据命名惯例推断 ADS、DWS、DWD、ODS、DIM 等数据库分层；
- 将“可能表名示例”“常见表”“补充推荐”等模型知识混入实际检索结果；
- 删除会改变事实含义的限定条件，例如截断状态、失败状态、时间范围或数据不完整说明；
- 把 planner、reviewer 的判断或模型常识当成工具事实。

### Runtime

Runtime 必须：

- 保存工具原始结构化结果和标准化 observation；
- 在模型总结前构造权威事实块，并把它放入模型上下文；
- 在模型总结后校验标识符、数量、状态、完整性和领域事实；
- 将推断和工具事实保持为不同语义类别；
- 检测到事实篡改时，携带原始证据要求模型重写；
- 模型持续违反契约时返回安全的证据缺口说明，而不是放行错误事实；
- 在运行 metadata 中记录契约版本、校验结果、违规项和重写次数。

## Runtime 协议

Runtime metadata 必须包含：

```json
{
  "factGroundingContract": {
    "contractVersion": "agent_runtime_fact_grounding_v1",
    "factAuthority": "TOOL_STRUCTURED_OUTPUT",
    "modelRole": "INTERPRET_AND_SUMMARIZE_WITHIN_FACT_BOUNDARY",
    "runtimeRole": "PRESERVE_VALIDATE_AND_REWRITE_ON_FACT_MUTATION",
    "enforcementStages": [
      "planning",
      "tool_result_review",
      "final_synthesis",
      "answer_review"
    ],
    "onViolation": "REWRITE_FROM_ORIGINAL_TOOL_EVIDENCE_OR_RETURN_SAFE_LIMITATION"
  },
  "executionPolicy": {
    "factGroundingContractVersion": "agent_runtime_fact_grounding_v1"
  }
}
```

领域校验器可以增加自己的审计字段。例如 SQL 元数据回答使用：

```json
{
  "sqlMetadataGroundingValidated": true,
  "sqlMetadataGroundingRewriteCount": 1,
  "sqlMetadataGroundingViolations": []
}
```

## 四阶段执行约束

### 1. Planning

- planner 只能把 observation 中已经出现的内容写入 `key_facts`。
- 未执行工具时不能在 `final_answer` 中预设工具结果。
- 信息不足时必须规划最小检索步骤或记录 `missing_info`，不能补造事实。

### 2. Tool Result Review

- reviewer 判断结果是否足以支持当前步骤，但不能改写工具结果。
- 部分结果仍是部分事实，不能因为“不完整”而被 reviewer 丢弃。
- reviewer 的满意度、完整性判断和建议不是新的业务事实。

### 3. Final Synthesis

- 模型第一次总结前必须先获得权威事实块。
- 最终总结必须保留精确标识符、数量、状态、完整性和失败限定。
- 模型可以解释事实，但不能把解释写成工具返回值。
- 未返回字段必须表述为“工具未返回”，不能给出示例字段冒充事实。

### 4. Answer Review

- reviewer 必须拒绝改变工具事实的候选回答。
- revised answer 同样受本契约约束，不能在修订时引入新事实。
- 最终对用户输出前仍应执行领域确定性校验。

## 事实、推断与建议的表达

最终答案必须区分：

- **检索事实**：直接来自结构化工具结果，可以作为确定性陈述。
- **基于事实的解释**：模型根据已返回事实做出的关系说明，必须能指出依据。
- **显式推断**：证据不能直接证明但存在合理可能性的判断，必须标记为推断，且不能生成新的物理标识符。
- **建议**：下一步工具、参数或验证方法，不能写成已经完成的结果。

推断和建议不得出现在“实际检索结果”“工具返回表”“字段清单”等事实区域中。

## SQL 元数据示例

假设工具只返回：

```text
database=finance
schema=public
tableName=customer_return_fact
columns=[customer_id, return_rate]
```

允许的总结：

```text
实际检索到 public.customer_return_fact，返回字段包含 customer_id 和 return_rate。
基于字段语义，该表可以作为分析客户收益率的候选事实表；是否覆盖年化口径仍需核对计算周期或继续检索字段说明。
```

禁止的总结：

```text
建议使用 DWS 层的 dws_cust_return_monthly，并补充 ADS 层的 ads_customer_return_summary。
```

禁止原因：工具没有返回 DWS/ADS 分层，也没有返回这两个物理表名。即使名称符合常见数仓规范，也只是模型知识，不是本次检索事实。

## 完整性与截断

- 输出预览变短不等于工具结果被截断。
- 只有结构化结果明确返回截断标记时，模型才能声明结果被截断。
- `detailTruncated=true` 不能被改写成“表名未返回”；如果 `catalogTruncated=false`，已返回物理表目录应按完整目录处理。
- SQL 行数、返回行数和 `complete` 状态必须同时保留。
- Linux stdout/stderr 使用头尾保留策略时，必须保留退出码、失败步骤和尾部错误，模型不能只根据 stdout 头部宣布成功。

## 违规处理

Runtime 检测到以下任一情况时必须阻止直接输出：

- 未出现在事实集中的物理标识符；
- 未经工具明确返回的数据库分层；
- 与工具结果冲突的数量、状态或完整性；
- 把失败工具描述成成功证据；
- 把推断、常见命名或示例包装成实际检索结果。

处理顺序：

1. 记录违规项。
2. 使用原始工具事实块、用户问题和违规项要求模型重写。
3. 再次执行确定性校验。
4. 在配置允许的次数内重复重写。
5. 持续违规时返回安全说明，明确事实边界和缺失证据。

## 验收标准

每次修改该契约或相关实现，至少验证：

- 模型可以继续输出基于事实的业务总结；
- 实际返回的表名和字段不会被误判；
- 虚构表名会被拒绝；
- 根据前缀推断的 ADS/DWS/DWD/ODS/DIM 分层会被拒绝；
- 零结果不会生成“可能表名示例”或“常见表”；
- 缺失字段会显示为缺失，不会由模型补齐；
- Runtime metadata 包含契约版本和校验审计信息。

当前自动化测试：

- `AgentRuntimeFactGroundingContractTest`
- `SqlMetadataGroundingGuardTest`
- `SqlMetadataAnswerRendererTest`

## 版本演进

- 契约版本变化必须同步更新代码常量、Runtime metadata、提示词引用和测试。
- 领域校验器可以扩展，但不能降低本契约定义的事实保护级别。
- 新工具接入时必须声明其结构化事实字段和完整性语义，不得绕过 Runtime 事实边界直接让模型解释原始字符串。

