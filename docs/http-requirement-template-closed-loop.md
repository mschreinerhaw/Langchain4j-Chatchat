# HTTP 需求分析与模板执行闭环规范

## 1. 职责边界

HTTP 网关资产遵循“模型负责需求拆解和候选语义选择，Runtime/MCP 负责检索、参数编译、路由和执行”。模型不得提交 URL、HTTP 方法、Header、Body、主机或 IP，也不得创造模板 ID。

## 2. 执行流程

1. 模型把用户目标拆成带 `id`、`description`、`requiredOutputs`、`dependsOn` 的需求步骤。
2. `http_requirement_analyze` 对每一步检索已启用的 HTTP 端点模板，返回候选和缺口。
3. 候选必须根据 `capabilitySpec`、`outputSchema`、`dependencySpec` 和参数 Schema 进行接受、细化或拒绝评审。
4. 被拒绝模板通过 `excludeTemplateIds` 从有界重检中排除；重检次数受 InterpretationPlan 的 `maxRewriteTimes` 限制。
5. Runtime 只接受 `http_endpoint_template_query.templates[].templateId`，并按模板参数 Schema 编译模型给出的参数协议。
6. `http_request_execute` 根据模板 ID 解析真实端点、生成逻辑路由上下文并执行。真实网络配置始终保留在 MCP 端。
7. 结构化 HTTP 结果作为事实交给后续 DAG 步骤和最终回答。

## 3. 工具协议

需求分析：

```json
{
  "goal": "查询客户订单及支付状态",
  "requirements": [
    {
      "id": "order_status",
      "description": "查询订单状态",
      "requiredOutputs": ["orderId", "status"],
      "dependsOn": []
    }
  ],
  "excludeTemplateIds": []
}
```

执行：

```json
{
  "template": "http_endpoint_template_query 返回的 templateId",
  "parameters": {
    "orderId": "用户问题或前序步骤提供的值"
  },
  "reason": "本次调用目的"
}
```

`executionContext` 可以由计划显式提供；未提供时，MCP 会用已验证的模板 ID 构造逻辑资产选择条件。模板 ID 只是逻辑标识，不会向模型暴露 URL。

## 4. HTTP 模板元数据

`mcp_ops_http_endpoint` 增加三个可选 JSON 字段：

- `capability_spec_json`：业务能力、适用场景和意图别名。
- `output_schema_json`：响应字段及结果结构。
- `dependency_spec_json`：前置能力、参数来源和调用依赖。

服务启动时由 Hibernate `ddl-auto=update` 同步列。旧数据保持兼容；元数据为空时仍可按名称、描述、标签和参数检索，但不能形成完整的需求覆盖证明。

## 5. 失败规则

- 没有候选：返回 `NO_CANDIDATE`，报告能力缺口或进入有界改写。
- 多候选：必须进行语义评审，不能仅因结果非空直接执行。
- 必填参数缺失：Runtime 阻止执行，不允许模型猜值。
- 模板不存在、被禁用或未由模板查询返回：阻止执行。
- 候选被拒绝：保留拒绝 ID 和细化意图，排除原候选后重新检索。
