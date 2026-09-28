# 领域 Skill 与固定 MCP 数据流程：分阶段接入

## 第一阶段：已实现的执行闭环

当前实现支持单个已授权领域 Skill，按版本化数据契约执行已发布 SQL 模板取数，
将规范化数据与采集状态交给现有 LangChain4j / OpenAI-compatible 分析适配器。
复用 `DefaultSkillRuntime`、数据库 Skill 授权、`PreauthorizedStructuredDataOperator`
和 `RegisteredToolAnalysisOperator`，没有增加另一套 MCP 客户端或允许模型自由选择工具。

执行顺序：元数据路由 → 内容加载与授权 → 工作流授权 → 数据需求参数绑定 →
固定模板流程 → 规范化数据包 → 无工具分析 → 结果与采集记录。

领域 Skill 的发布内容声明业务需求；运维配置绑定具体数据流程；数据库授权仍独立生效。
导入 Skill、配置绑定、发送请求中的任何一个动作都不会自动授予资源权限。

## 1. 导入并发布带数据需求的 Skill

下面是导入 SKILL.md 时的 front matter 示例。`contractId` 必须带 `.vN` 后缀。
`parameters` 的值是请求 `inputs` 中的键名，不支持表达式、SQL 或工具调用。

```yaml
---
name: customer-return-description
description: 解释客户已经计算好的日收益序列，标注观察范围与数据来源
workflows:
  - customer-return-analysis
requiredData:
  - id: returns
    contractId: customer.return_series.v1
    requiredFor: [describe_returns]
    optional: false
    parameters:
      customerId: customerId
      startDate: startDate
      endDate: endDate
---
基于提供的日收益数据描述变化，引用 evidenceId 和数据契约。
不得将实际返回日期范围称为完整历史，不得自行生成缺失数据。
未取得 returns 时，报告对应状态，不执行 describe_returns。
本 Skill 不计算累计收益、收益归因或投资能力评分。
```

这是接口示例，不预设生产数据的收益口径。领域负责人应先确认模板字段、单位、口径
与 Skill 方法一致，再发布实际绑定。外部 Skill 未声明 `requiredData` 时，原有执行入口
保持兼容；新分析入口返回 `NO_DATA_REQUIREMENTS`，不会回退为自由工具调用。

## 2. 发布服务端数据流程绑定

在部署配置中配置 `chatchat.skill-data.bindings`。下面的 ID 均需替换为本地已发布资源，
示例默认关闭。第一阶段使用服务端配置发布绑定；暂未增加数据库管理页面。

```yaml
chatchat:
  skill-data:
    bindings:
      - enabled: false
        tenant-id: YOUR_TENANT
        domain-skill-id: YOUR_IMPORTED_DOMAIN_SKILL_ID
        contract-id: customer.return_series.v1
        workflow-id: customer-return-analysis
        version: "1"
        execution-skill-id: YOUR_LOCAL_EXECUTION_SKILL_ID
        template-id: YOUR_PUBLISHED_TEMPLATE_ID
        asset-name: YOUR_LOGICAL_ASSET
        environment: prod
        parameter-bindings:
          customer_id: customerId
          start_date: startDate
          end_date: endDate
        fields:
          date: trading_date
          dailyReturn: daily_return
        semantics:
          granularity: daily
          returnUnit: ratio
          returnDefinition: REPLACE_WITH_APPROVED_DEFINITION
```

需要独立配置并授予：

- 用户/角色对领域 Skill 的访问权，以及该 Skill 的 WORKFLOW 绑定和工作流访问权。
- 用户/角色对本地执行 Skill 的访问权。
- 本地执行 Skill 对现有模板执行工具的绑定，以及已有工具、模板、数据资产访问权限。

绑定严格匹配租户、领域 Skill、数据契约；多条匹配返回 `AMBIGUOUS_BINDING`，不自动任选。
模板 ID、执行 Skill、环境、资产和字段映射始终来自服务端配置，不读取请求中的同名参数。
数据契约参数经 `parameter-bindings` 映射为模板参数；未绑定参数不会发给 MCP。

## 3. 调用分析接口

```http
POST /api/v1/data-science/domain-skills/YOUR_IMPORTED_DOMAIN_SKILL_ID/analyze
Content-Type: application/json
Authorization: Bearer YOUR_TOKEN

{
  "query": "描述这个客户在所选区间的日收益变化",
  "workflowId": "customer-return-analysis",
  "modelName": "YOUR_PUBLISHED_MODEL",
  "inputs": {
    "customerId": "CUSTOMER_ID",
    "startDate": "2026-03-01",
    "endDate": "2026-08-31"
  }
}
```

租户、用户、角色取自服务端认证结果，不接受请求提供的身份。此入口固定使用现有
LangChain4j 适配器；没有暴露任意外部 Agent 转发能力。

## 数据与失败语义

`diagnostics.skillDataResults` 保留每个需求的结果：`AVAILABLE`、`EMPTY`、`NO_BINDING`、
`AMBIGUOUS_BINDING`、`DENIED`、`MISSING_INPUT`、`INVALID_DATA` 或 `FAILED`。
`rows` 只含发布绑定列出的规范字段；`provenance` 包括实际执行流程版本、模板、资产、
环境、参数、证据 ID、请求 ID 和已声明的数据口径。AVAILABLE 表示取得符合当前结构
检查的数据，不表示证据充分、结论正确或允许发布。

单项失败保留其他数据并继续分析；取消和线程中断仍传播。`requiredFor` 是领域步骤
依赖声明，第一阶段交给分析上下文解释，尚不是独立的确定性分析步骤执行器。
整个请求仍可能因鉴权失败、分析模型失败或执行基础设施故障无法完成。

分析阶段不暴露 MCP 工具和文档检索入口，工具预算为 0。客户端伪造的
`skillDataResults` 会被 Runtime 丢弃。本阶段数据路径仅支持 LangChain4j 及其 OpenAI-compatible
适配器，其余引擎返回 `DATA_ANALYSIS_ENGINE_UNSUPPORTED`。

本阶段最多 8 项数据需求，每项复用现有最多 100 行的模板执行限制；不做静默截断。
合并后的模型数据上下文超过 100,000 字符时返回 `DATA_CONTEXT_LIMIT`。
字段检查目前验证存在、非空、标量；不自动进行币种换算、频率重采样或收益口径转换。

## 后续阶段

1. 将能力需求与已发布 Skill 的能力元数据匹配，输出可审阅的 Skill 选择计划。
2. 引入确定性分析步骤与已注册计算算子，严格执行 `requiredFor` 依赖及跳过原因。
3. 组合多个 Skill，在身份、权限、主体、日期、契约和版本完全匹配时复用数据。
4. 增加绑定的数据库发布管理和效果对比：取数轨迹、可复算指标、引用、领域人工复核。

上述后续能力不计入第一阶段已完成范围。
