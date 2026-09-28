# 外部 Skill 编译协议与固定数据流程

当前实现覆盖：外部声明转换、发布版本固定、能力匹配、确定性计算、步骤依赖、多 Skill 组合、请求内数据复用、数据库绑定发布、对照实验与人工复核。

## 职责与实际调用链

```text
Markdown / ZIP(SKILL.md) / JSON / YAML
  → 格式适配器保留原始声明
  → 模型整理领域知识 + 确定性协议转换器校验执行声明
  → runtime_skill_ir.v2 编译产物（草稿）
  → 管理员发布，固定 compilationId
  → 已授权能力发现 → 选择计划 → 重新授权并读取发布版本
  → requiredData → 本地发布绑定
  → PreauthorizedStructuredDataOperator
  → RegisteredToolAnalysisOperator → 现有 MCP 模板执行流程
  → 规范化数据 → 已注册计算算子 → 无工具的领域分析
  → 执行记录 → 人工复核
```

外部工具名称、脚本和权限声明不会安装工具或授予权限。取数仍使用现有的模板、资源授权、MCP 调用链。领域 Skill 声明“需要什么”，本地绑定决定“去哪里取、如何取”。当前固定取数实现使用已发布 SQL 模板；其他固定业务流程可以实现 SkillDataWorkflow 接口接入。

Runtime 记录数据是否取得、步骤是否执行、依赖是否完成；不判断证据充分性、结论真实性或正式采信。单项取数/计算失败不会终止其他独立步骤，分析收到失败状态。取消和线程中断继续传播。业务发布限制应由独立业务 Contract 执行，不由这些状态代替。

## 1. 外部格式与编译协议

Markdown 和 ZIP 中的 SKILL.md 支持以下 front matter；旧版顶层 requiredData/workflows 仍可转换。JSON/YAML 声明式导出要求 name（或 displayName）及 instructions（也支持 instruction/systemPrompt/system_prompt），runtime 对象与下例一致。

这是声明式导出的受支持子集；不运行外部 SDK 代码、任意 Agent 图或脚本。外部原文只有知识说明而没有数据契约时，保留知识用途；数据分析接口返回 NO_DATA_REQUIREMENTS。系统不会让模型猜测真实模板 ID 或业务参数映射。

```yaml
---
name: customer-sales-summary
description: 汇总指定客户的数据并说明观察范围
runtime:
  schemaVersion: skill_protocol.v1
  domain: SALES
  capabilities: [sales.summary]
  requiresCapabilities: []
  workflows: [customer-sales-analysis]
  requiredData:
    - id: sales
      contractId: customer.sales_rows.v1
      parameters: {customerId: customerId}
      requiredFor: [total, count]
      optional: false
  analysisSteps:
    - id: total
      operator: SUM
      datasetId: sales
      field: amount
      dependsOn: []
    - id: count
      operator: COUNT
      datasetId: sales
      dependsOn: [total]
---
解释已完成计算的结果，引用 evidenceId，说明数据范围及获取失败。
不得把已返回的记录称为客户全部历史；正式采信由人复核。
```

capabilities/requiresCapabilities 使用稳定标识符。模型生成的能力描述只能用于辅助发现；可执行依赖必须来自显式 requiresCapabilities。

编译检查：至多 8 项数据需求、24 个分析步骤，ID 唯一、契约带 .vN 版本、数据和步骤引用存在、依赖无环。无效声明使导入失败，不静默降级成可执行 Skill。当前计算支持 COUNT、SUM、AVG、MIN、MAX，使用十进制运算；AVG 使用 DECIMAL64。未注册算子返回 OPERATOR_NOT_REGISTERED，不交给模型临时生成代码。

编译产物保存在现有 source/compilation 表，领域 Skill 发布时保存 publishedCompilationId 和能力元数据。修改已编译说明后必须重新编译再发布；未固定编译版本的旧 Skill 仍可使用知识说明，但不直接执行原始导入文件中的数据需求。

现有导入接口和前端文件选择器现支持 .json/.yaml/.yml。重新编译已有 Skill：

- GET /api/v1/data-science/domain-skills/{id}/protocol：查看最新编译产物。
- POST /api/v1/data-science/domain-skills/{id}/compile：请求体为上述 runtime 对象；生成新编译草稿。
- 使用原有发布接口发布。编译不等于发布，也不等于授予资源访问权。

## 2. 发布固定数据绑定

沿用现有管理员身份规则（认证用户 username=admin）。绑定只允许当前租户，按 tenantId/domainSkillId/contractId 唯一。所有 ID 必须替换为已存在、已发布的本地资源；系统不会创建或猜测生产绑定。

PUT /api/v1/data-science/domain-skills/{skillId}/data-bindings：

```json
{
  "revision": null,
  "binding": {
    "enabled": true,
    "tenantId": "YOUR_TENANT",
    "domainSkillId": "YOUR_DOMAIN_SKILL",
    "contractId": "customer.sales_rows.v1",
    "workflowId": "customer-sales-analysis",
    "version": "1",
    "executionSkillId": "YOUR_EXECUTION_SKILL",
    "templateId": "YOUR_PUBLISHED_TEMPLATE",
    "assetName": "YOUR_LOGICAL_ASSET",
    "environment": "prod",
    "parameterBindings": {"customer_id": "customerId"},
    "fields": {"amount": "sales_amount"},
    "semantics": {"currency": "CNY", "scope": "REPLACE_WITH_APPROVED_SCOPE"}
  }
}
```

新增时 revision=null；更新必须提交 GET 返回的 revision。

- GET /{skillId}/data-bindings：查看草稿、发布快照和修订号。
- POST /{skillId}/data-bindings/{contractId}/publish，体为 {"revision":当前修订号}。
- POST /{skillId}/data-bindings/{contractId}/retire，同样提交修订号。

发布后的快照独立于草稿。改变绑定内容需要新 version；并发编辑使用数据库乐观锁。数据库已发布绑定优先于同键配置文件绑定；只有草稿时配置仍生效；停用保留记录，防止旧配置意外恢复。也仍支持 chatchat.skill-data.bindings 部署配置。

需要分别配置领域 Skill、WORKFLOW、本地执行 Skill、工具、模板、数据资产的现有授权。发布绑定不会自动授予任何权限。字段/口径语义由领域负责人确认；系统仅做结构检查，不自动认证币种或业务含义。

## 3. 能力规划与组合执行

统一前缀 /api/v1/data-science/domain-skills。

POST /analysis-plan 查看计划，POST /analyze-capabilities 执行并保存 runId；请求体相同：

```json
{
  "query": "汇总客户在当前数据范围内的销售额",
  "capabilities": ["sales.summary"],
  "skillIds": [],
  "workflowIds": {"YOUR_DOMAIN_SKILL": "customer-sales-analysis"},
  "inputs": {"customerId": "CUSTOMER_ID"},
  "modelName": "YOUR_PUBLISHED_MODEL",
  "maxSkills": 4
}
```

capabilities 明确指定任务所需能力。未指定时，以已授权检索首选 Skill 的已发布能力为起点，并在计划标记 METADATA_RETRIEVAL；这不保证已覆盖问题的全部业务需求。skillIds 可限制候选集合，也会限制可用的依赖提供者。

选择先覆盖请求能力，再寻找声明的依赖提供者，输出确定性顺序、缺失能力与被阻断的依赖。最多组合 8 个 Skill；循环或缺失依赖不可执行。执行使用固定编译版本，并重新校验权限；计划生成不授予权限。一个 Skill 失败只跳过依赖它的 Skill，独立 Skill 继续。

数据复用仅发生在同一组合请求/实验内：租户、用户、角色、执行授权范围、契约、参数、工作流版本、执行 Skill、模板、资产、环境、字段与口径必须完全一致。每次使用重新检查领域工作流和执行 Skill 权限；缓存只保留成功数据或空结果。无跨请求缓存。底层模板与资产鉴权仍在实际 MCP 取数时执行。

单 Skill 原接口 POST /{skillId}/analyze 保留，体为 query/workflowId/modelName/inputs。新组合入口固定使用现有 LangChain4j 适配器，不开放客户端引擎选择或任意工具调用。

## 4. 结果与效果评估

诊断字段：

- skillDataResults：AVAILABLE、EMPTY、NO_BINDING、AMBIGUOUS_BINDING、DENIED、MISSING_INPUT、INVALID_DATA、FAILED，以及规范化行和取数 provenance。
- skillStepResults：COMPLETED、SKIPPED、FAILED，计算结果、源证据与计算证据 ID、未执行原因。
- 组合计划：Skill 与编译版本、选定工作流、能力依赖、未覆盖能力。
- metrics：执行数、取数成功数、计算完成数、耗时。这些是执行指标，不是答案质量评分。

POST /experiments 接收同一组合请求，分别运行领域 Skill 和通用说明基线；使用同一版本计划和同一个请求内数据会话。基线关闭领域计算步骤，不评估“谁更正确”。sameAcquiredEvidence 仅在两次实际存在且证据 ID 相同时为 true；失败、权限或版本变化可能使对比不完整，应检查两个结果及该字段。

GET /analysis-runs/{id} 返回请求、结果、revision、人工复核。仅当前租户的记录创建者可读取和复核。POST /analysis-runs/{id}/review：

```json
{"status":"NEEDS_REVIEW","notes":"需要核对数据范围与业务口径","revision":0}
```

状态可选 APPROVED / NEEDS_REVIEW / REJECTED，由人明确提交并记录服务端身份和时间；不会据此更改 Runtime 执行状态或自动发布业务结论。

## 5. 部署与验证边界

新增表 ds_skill_data_binding、ds_skill_analysis_run；ds_domain_skill 新增 published_compilation_id、runtime_metadata_json。当前项目的 Hibernate ddl-auto=update 创建新表，现有 Skill schema migrator 补齐领域表列。禁用自动建表的环境应在部署前按实体映射执行数据库变更。

后端接口已实现；前端本次更新导入格式选择，绑定、组合实验与复核使用上述 API，未新增完整管理页面。现有生产模板、业务 Contract 和资源授权保持由本地配置管理。本次没有发布生产绑定或调用真实 MCP/模型服务，实际领域分析效果仍需用本地数据跑实验并人工复核。

每项取数最多 100 行；模型数据上下文最多 100,000 字符，超限明确返回 DATA_CONTEXT_LIMIT，不静默截断。业务需要更多记录时，应在固定模板流程内做经过确认的聚合或分页契约。系统不会自动重采样、换汇或改变收益计算口径。
