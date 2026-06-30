# MCP Governed Execution Platform

## 定位

ChatChat MCP 不再只是工具注册与调用系统，而是一个多租户、角色感知、范围受控、可审计的执行平台。

核心边界：

```text
API 负责授权决策
MCP 负责受控执行、受限检索、租户隔离和审计闭环
Agent Runtime 通过 Tool Router 选择正确 MCP 工具
```

## 设计原则

1. 权限中心化：用户、角色、授权规则由 API 控制面维护。
2. MCP 执行轻量化：MCP 不编辑权限，只同步、缓存、校验和审计。
3. 租户协议强约束：所有检索和执行请求必须携带 tenantId。
4. 工具按资产类型隔离：避免通用工具误路由到错误资产域。
5. 不按具体资源无限暴露工具：API 服务、数据库查询、资产实例不各自变成独立 MCP tool。
6. 模型不直接决定最终执行工具：通过 Tool Router 进行资产类型识别、权限预检和工具映射。

## 平面划分

### API Control Plane

职责：

- User / Role / Permission 管理
- Role -> Scope 授权
- 用户角色绑定
- MCP 授权规则管理
- 权限快照同步接口
- admin 作为 API 端独立超级管理员，不同步到 MCP

API 端回答的问题：

```text
谁可以用什么能力
```

### MCP Execution Plane

职责：

- 拉取或接收 API 权限快照
- 本地缓存非 admin 用户、角色和授权规则
- 强制校验 tenant context
- 按角色和 scope 校验 MCP 调用
- 执行受控检索和工具调用
- 写入审计日志
- admin 作为 MCP 端独立超级管理员，拥有完整权限

MCP 端回答的问题：

```text
这次请求现在能不能执行
```

## 请求上下文契约

所有 MCP 请求必须归一化为标准上下文。业务参数不能替代协议上下文。

```json
{
  "traceId": "trace-001",
  "identity": {
    "userId": "u001",
    "roles": ["BUSINESS_ADMIN"]
  },
  "tenant": {
    "tenantId": "tenant-a",
    "workspaceId": "default",
    "env": "prod"
  },
  "scope": {
    "assetType": "api_service",
    "domain": "order",
    "permissionLevel": "read"
  },
  "request": {
    "tool": "api_asset_query",
    "params": {}
  }
}
```

强制规则：

- tenant.tenantId 为空时直接拒绝。
- identity.userId 为空时直接拒绝，除非是本地 MCP admin 会话。
- scope.assetType 必须和目标工具声明的 assetType 一致。
- 检索、模板选择、执行、审计日志都必须写入 tenantId。

统一错误：

```json
{
  "success": false,
  "error": "TENANT_REQUIRED",
  "message": "missing tenantId in MCP request context"
}
```

## Scope DSL

授权规则不要只保存散字段，建议统一落成 scope 表达式，便于后续扩展 ABAC。

格式：

```text
mcp:{assetType}:{capability}:{action}@tenant={tenantId};domain={domain};level={level}
```

示例：

```text
mcp:api_service:asset:query@tenant=tenant-a;domain=order;level=read
mcp:api_service:template:query@tenant=tenant-a;domain=order;level=read
mcp:sql_datasource:asset:query@tenant=tenant-a;domain=finance;level=read
mcp:database_query:template:query@tenant=tenant-a;domain=finance;level=read
mcp:ssh_host:execute:command@tenant=tenant-a;domain=ops;level=write
```

最小字段：

```json
{
  "roleId": "BUSINESS_ADMIN",
  "effect": "allow",
  "scope": "mcp:api_service:asset:query@tenant=tenant-a;domain=order;level=read",
  "enabled": true
}
```

匹配顺序：

1. deny 优先于 allow
2. 精确 toolName 优先于 assetType
3. tenant 必须精确匹配
4. domain 缺省表示当前租户内全域
5. level 必须满足目标动作要求

## Tool Router

Tool Router 是 Agent Runtime 与 MCP 工具之间的稳定层，避免模型直接依赖大量 MCP tool 名。

执行链：

```text
LLM intent
  -> Tool Router
  -> Context normalizer
  -> Scope pre-check
  -> Typed MCP tool
  -> Audit
```

Router 输入：

```json
{
  "intent": "查询订单 API 资产",
  "tenantId": "tenant-a",
  "userId": "u001",
  "roles": ["BUSINESS_ADMIN"],
  "assetType": "api_service",
  "capability": "asset_discovery",
  "domain": "order"
}
```

Router 输出：

```json
{
  "tool": "api_asset_query",
  "scope": "mcp:api_service:asset:query@tenant=tenant-a;domain=order;level=read",
  "params": {
    "filters": {
      "intentZh": "查询订单 API 资产"
    }
  }
}
```

Router 职责：

- 识别 assetType
- 注入 tenantId / userId / roles
- 映射 typed MCP tool
- 权限预检查
- 阻止跨资产类型路由
- 阻止模型发明 toolName
- 生成审计 traceId

## 工具体系

### 资产检索工具

按资产类型拆分，避免通用资产检索工具误路由。`asset_query` 不再作为 MCP Server 发布工具名使用。

```text
api_asset_query
ssh_asset_query
sql_datasource_asset_query
http_endpoint_asset_query
```

API 资产不按每个 API 单独暴露 MCP tool。统一通过 api_asset_query 检索 API 资产元数据，再通过 api_template_query 选择模板。

SQL 表级分析不应在每次对话中扫描业务库系统元数据。数据库资产维护页负责声明元数据索引范围并刷新到本地：

```text
Asset Registry
  -> Metadata Refresh
  -> RocksDB schema cache
  -> Lucene search index
  -> sql_metadata_search
```

`sql_metadata_search` 是 MCP 对外暴露的元数据检索入口，用于按资产、库/schema、表名、表注释、字段注释召回结构化表位置和列信息。

### 模板检索工具

当前实现按资产类型拆分，保证强隔离：

```text
api_template_query
ssh_template_query
sql_datasource_template_query
http_endpoint_template_query
database_query_template_query
```

后续如果工具数量继续膨胀，可以在 Router 层保留统一逻辑能力名：

```text
template_discovery(assetType)
```

但 MCP 实际发布仍可以是 typed tools。这样对 Agent 是稳定接口，对 MCP 是强隔离实现。

### 本地检索工具

本地检索工具不直接访问外部系统，主要消费已经刷新到本地的索引或缓存：

```text
sql_metadata_search
document_search
```

这类工具仍然必须携带 tenant/user/trace 上下文，并受本次 `availableTools` 约束。

### 执行工具

执行维度必须严格隔离：

```text
database_query
sql_query_execute
linux_command_execute
http_request_execute
notification_send
```

写操作、危险命令、外部 HTTP 调用必须走更高 level 的 scope，并根据风险级别触发确认。

## API 资产策略

API 服务属于资产，不属于独立 MCP tool。

允许暴露：

- toolName
- title
- description
- method
- enabled
- templateId
- routing hints

禁止暴露：

- urlTemplate
- headersJson
- bodyTemplate
- credential
- raw request payload

推荐执行链：

```text
api_asset_query
  -> api_template_query
  -> Router 生成受控执行计划
  -> HTTP/API 执行层
```

## 授权管理页面

权限同步位于 MCP 管理后台的系统设置页签。

页面结构：

```text
系统设置
  - 连接信息
  - 权限同步
      - 用户角色
      - 角色信息
      - 授权规则
```

角色信息页签提供授权管理按钮。点击后进入角色授权弹窗，按分类检索授权对象：

- API 资产检索
- API 模板检索
- 数据库查询模板
- SSH 资产/模板
- SQL 数据源资产/模板
- HTTP 端点资产/模板

授权结果写回 API 控制面，再由 MCP 同步快照。

## 审计模型

每次 MCP 检索和执行都必须记录：

- traceId
- tenantId
- userId
- roles
- roleId
- scope
- toolName
- assetType
- targetKind
- effect
- decisionReason
- requestHash
- resultStatus
- latencyMs

审计日志不保存敏感参数原文。对于 API、数据库、SSH 等高风险工具，只保存脱敏摘要。

## 拒绝策略

常见错误码：

```text
TENANT_REQUIRED
IDENTITY_REQUIRED
SCOPE_REQUIRED
SCOPE_MISMATCH
PERMISSION_DENIED
TOOL_ROUTING_DENIED
RAW_TARGET_FIELD_FORBIDDEN
RISK_CONFIRMATION_REQUIRED
```

示例：

```json
{
  "success": false,
  "error": "PERMISSION_DENIED",
  "message": "role BUSINESS_ADMIN has no permission for api_asset_query in tenant tenant-a"
}
```

## 演进优先级

1. 补齐 Tool Router 层，统一 Agent 到 MCP 的工具映射。
2. 将授权规则内部表达升级为 Scope DSL。
3. 在 MCP 请求入口强制校验 tenant / identity / scope。
4. 将所有资产和模板检索接入 tenant filter。
5. 将审计日志绑定 scope 和 traceId。
6. 将高风险执行统一接入确认策略。
