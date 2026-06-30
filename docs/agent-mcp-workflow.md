# Agent MCP Workflow JSON

Agent 配置可以在 `workflowConfig.mcpWorkflow` 中声明 MCP 工具编排契约。Planner 把它作为执行计划的工具图，Runtime 按同一契约硬拦截越序、缺依赖、缺租户上下文、缺授权和缺确认的工具调用。

新的约定是：Agent 面向稳定的 Router step，不直接依赖已经拆分掉的泛化 `asset_query` / `template_query`，也不让模型发明具体工具名。Tool Router 根据 `assetType`、`routerCapability`、`tenantId` 和角色授权映射到类型化 MCP 工具。

请求级 `availableTools` 是硬边界：planner、rewriter、workflow guard 和 runtime validator 都必须只规划本次可见工具。工具即使已在全局 registry 注册，只要不在本次 `availableTools` 中，也不能被补造进 InterpretationPlan。

```json
{
  "workflowConfig": {
    "mcpWorkflow": [
      {
        "step": "asset_discovery",
        "routerCapability": "asset_discovery",
        "assetTypes": ["api_service", "ssh_host", "sql_datasource", "http_endpoint"],
        "resolvedTools": [
          "api_asset_query",
          "ssh_asset_query",
          "sql_datasource_asset_query",
          "http_endpoint_asset_query"
        ],
        "required": true,
        "dependsOn": [],
        "parallelSteps": [],
        "condition": "当用户问题涉及 API、数据库、服务器、HTTP 端点或其他业务资产时必须先执行",
        "confirmation": "none",
        "executionStrategy": "router_resolve_then_query"
      },
      {
        "step": "sql_metadata_resolution",
        "routerCapability": "metadata_search",
        "assetTypes": ["sql_datasource"],
        "resolvedTools": ["sql_metadata_search"],
        "required": false,
        "dependsOn": ["asset_discovery"],
        "optionalDependsOn": [],
        "parallelSteps": [],
        "condition": "当用户问题包含表名、库名、schema、表注释、字段注释或需要分析 SQL 表结构时执行",
        "confirmation": "none",
        "executionStrategy": "local_index_search_then_bind_sql_context"
      },
      {
        "step": "template_retrieval",
        "routerCapability": "template_discovery",
        "assetTypes": ["api_service", "ssh_host", "sql_datasource", "http_endpoint", "database_query"],
        "resolvedTools": [
          "api_template_query",
          "ssh_template_query",
          "sql_datasource_template_query",
          "http_endpoint_template_query",
          "database_query_template_query"
        ],
        "required": true,
        "dependsOn": ["asset_discovery"],
        "optionalDependsOn": ["sql_metadata_resolution"],
        "parallelSteps": [],
        "condition": "当任务需要使用受控模板、数据库查询模板、API 服务模板或运维命令模板，并且本次 availableTools 中存在对应 typed template discovery 工具时执行",
        "confirmation": "none",
        "executionStrategy": "router_resolve_retrieve_rank_then_plan"
      },
      {
        "step": "read_only_execution",
        "routerCapability": "execute",
        "assetTypes": ["database_query", "api_service", "http_endpoint"],
        "resolvedTools": ["database_query", "http_request_execute"],
        "required": false,
        "dependsOn": ["asset_discovery", "template_retrieval"],
        "parallelSteps": [],
        "condition": "当任务需要读取数据库、调用只读 API 或访问只读 HTTP 端点时执行",
        "confirmation": "none",
        "executionStrategy": "scope_checked_read_only_execute"
      },
      {
        "step": "host_diagnosis",
        "routerCapability": "execute",
        "assetTypes": ["ssh_host"],
        "resolvedTools": ["linux_command_execute"],
        "required": false,
        "dependsOn": ["asset_discovery", "template_retrieval"],
        "parallelSteps": ["read_only_execution"],
        "condition": "当问题可能与主机 CPU、内存、磁盘、网络、进程或日志有关时执行",
        "confirmation": "required_for_risky_command",
        "executionStrategy": "safe_command_only"
      },
      {
        "step": "write_execution",
        "routerCapability": "execute",
        "assetTypes": ["database_query", "ssh_host", "api_service"],
        "resolvedTools": ["database_query", "linux_command_execute", "http_request_execute"],
        "required": false,
        "dependsOn": ["asset_discovery", "template_retrieval"],
        "parallelSteps": [],
        "condition": "仅当用户明确要求执行变更类操作，并且角色授权允许写级别 scope 时执行",
        "confirmation": "required_for_write",
        "executionStrategy": "confirm_then_execute"
      }
    ]
  }
}
```

说明：

- `required=true` 表示当该 workflow step 的 condition 命中且 resolved tool 存在于本次 `availableTools` 时必须执行；它不允许 planner 补造一个本次不可见的工具。若配置要求模板发现但本次没有 typed template discovery 工具，runtime 应停止并报告缺少模板发现能力，而不是跳过或改用 raw SQL/raw command/raw HTTP。
- `dependsOn` 表示必选依赖，planner 必须把它转换为目标 step 的 `depends_on`，并在 `plan.dependency_contracts` 中标记 `required=true`。
- `optionalDependsOn` 表示可选依赖，planner 需要结合用户问题和 condition 判断是否生成该前置 step；未命中时不要把它放进 `depends_on`。如果选择执行，应在 `plan.dependency_contracts` 中标记 `required=false` 并写明 `condition/reason`。

## Router Contract

Runtime 调用 MCP 前必须生成 Router 决策。

```json
{
  "traceId": "trace-001",
  "identity": {
    "userId": "u001",
    "roles": ["BUSINESS_ADMIN"]
  },
  "tenant": {
    "tenantId": "tenant-a"
  },
  "scope": {
    "assetType": "api_service",
    "domain": "order",
    "permissionLevel": "read"
  },
  "router": {
    "capability": "asset_discovery",
    "resolvedTool": "api_asset_query",
    "scopeExpression": "mcp:api_service:asset:query@tenant=tenant-a;domain=order;level=read"
  }
}
```

## Confirmation Levels

- `none`
- `required_for_risky_command`
- `required_for_write`
- `required_always`

## Hard Rules

- `tenant.tenantId` 为空时禁止调用 MCP。
- `scope.assetType` 必须和 `resolvedTool` 的资产类型一致。
- `resolvedTool` 必须来自 Router 映射表，不能由模型自由生成。
- `resolvedTool` 必须同时存在于本次 `availableTools`；全局 registry 中存在但本次不可见的工具不得执行。
- `deny` 授权优先于 `allow` 授权。
- API 服务不按单个 API 暴露成 MCP tool，统一走 `api_asset_query` 和 `api_template_query`。
- SQL 表分析优先走 `sql_metadata_search` 查询本地 RocksDB/Lucene 元数据索引，不在对话中频繁访问 `information_schema`。
