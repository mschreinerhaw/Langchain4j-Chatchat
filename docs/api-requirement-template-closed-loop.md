# API 需求分析与模板执行闭环规范

## 1. 目标

API 能力链遵循“模型负责语义决策，Runtime/MCP 负责检索、校验、参数编译和执行”。模型不得拼接 URL、HTTP 方法、Header、Body，也不得创造模板 ID。

闭环覆盖：

1. 将用户目标拆成可验证的 API 能力需求。
2. 对每项需求检索已登记且已启用的 API 模板。
3. 对候选进行接受、细化或拒绝决策。
4. 对未覆盖需求重新检索，且排除已拒绝模板。
5. 根据依赖关系生成执行 DAG。
6. 从用户问题或前序结果中提取参数，由 Runtime 按模板 Schema 编译。
7. 通过统一执行入口调用模板，并把结构化结果交给后续步骤。

## 2. 工具职责

### `api_requirement_analyze`

只读能力分析工具。输入模型生成的需求步骤，代码逐项调用模板索引，返回：

- `coverage[].requirement`
- `coverage[].candidateStatus`
- `coverage[].templates`
- `missingRequirementIds`
- `allRequirementsHaveCandidates`

`CANDIDATES_FOUND` 只表示存在候选，不表示候选已满足业务需求。

### `api_template_query`

检索单项 API 能力。候选模板返回：

- `templateId`
- `capabilitySpec`
- `parameterSchema` / `requiredParameters`
- `outputSchema`
- `dependencySpec`
- `relevanceScore`
- `routing.callTool=api_template_execute`

细化查询可携带 `excludeTemplateIds`。同一轮不得用相同条件重复查询。

### `api_template_execute`

唯一 API 模板执行入口：

```json
{
  "templateId": "api_template_query 返回的模板 ID",
  "parameters": {
    "业务参数": "值"
  },
  "purpose": "本次调用目的"
}
```

MCP 根据 `templateId` 读取真实 API 配置，检查模板是否启用，并使用模板参数 Schema 校验 `parameters`。模型输出中的 URL、Header、Body 不进入执行协议。

## 3. 模型协议

需求拆解建议使用：

```json
{
  "schemaVersion": "api_requirement_protocol.v1",
  "goal": "完成客户授信分析",
  "requirements": [
    {
      "id": "customer_profile",
      "description": "查询客户基本信息",
      "requiredOutputs": ["customerId", "customerType"],
      "dependsOn": []
    }
  ]
}
```

候选选择使用 `template_selection_protocol.v1`：

- `accept`：模板 ID 必须存在于当前工具结果。
- `refine`：提供缺失能力、细化意图和已拒绝模板 ID。
- `reject`：停止该候选的执行，不得用原条件重复调用。

参数化模板继续使用 `template_parameter_protocol_v1`。Runtime 会校验步骤 ID、模板 ID、字段声明、必填参数和类型，并编译成执行请求。

## 4. API 模板元数据

`mcp_api_service_config` 增加以下可选 JSON 字段，Hibernate `ddl-auto=update` 会在服务启动时同步列：

- `capability_spec_json`：业务能力、适用场景、意图别名。
- `output_schema_json`：响应字段和结果结构。
- `dependency_spec_json`：前置能力、输入来源和调用依赖。

旧模板字段为空时保持兼容，但只能参与基于标题、描述和输入参数的基础检索，无法提供完整覆盖证明。

## 5. 失败与重检规则

- 无候选：返回 `NO_CANDIDATE`，进入计划改写或向用户报告能力缺口。
- 多候选：必须经过模型语义评审，不再由 Runtime 因“数量大于零”直接跳过评审。
- 参数不足：不执行 API；模型从当前用户问题或已完成步骤中补充参数。
- 模板被禁用或不存在：`api_template_execute` 拒绝执行。
- 重检：携带 `excludeTemplateIds` 和 `refinedIntent`，受 InterpretationPlan 的 `maxRewriteTimes` 限制。

## 6. 安全边界

- 模型不能改变 Runtime 已绑定的模板 ID。
- 模型不能传递原始网络执行字段。
- 候选发现不是执行证据；只有 `api_template_execute` 的结果可作为实时 API 数据证据。
- 输出 Schema 是需求覆盖和结果解释元数据，不得作为输入参数发送。
