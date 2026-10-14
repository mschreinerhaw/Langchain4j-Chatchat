# SQL Studio 联机元数据 MCP 契约草案

状态：拟实施契约。现有能力与拟扩展字段在本文分开描述；工具可调用性仍以现有注册目录、权限和契约快照为准。此草案不发布工具，不授予权限。

## 复用与扩展原则

公开元数据入口保留现有 `sql_schema_context_query`，扩展操作协议，底层复用已有元数据索引与采集服务。内部 `sql_metadata_search` 不重新作为另一个重复公开工具发布。

不带 operation 的旧调用继续按旧 SEARCH 语义返回原结构，catalogLimit/detailLimit 上限仍为 5。显式带 `contractVersion=sql-studio.metadata.v1` 的新调用使用下述操作与结果包；未知版本拒绝，不静默降级。旧客户端不会因加入新字段而获得刷新、执行或更广资产权限。

`sql_query_execute` 保留现有查询执行契约，不合并进元数据工具。企业标准检索复用当前 `enterprise_metadata_search`，物理 Schema 与标准元数据的来源标识必须区分。

## 模型输入与可信上下文

工具业务参数只能包含逻辑引用和检索需求。用户、租户、角色、权限修订号、绑定内容、凭据及 Gateway 地址由服务端认证上下文注入；以现有 `McpInvocationContext` 和 MCP 调用 envelope 为身份来源。模型提交相同名称的身份字段也不能覆盖它。

绑定 ID 和发现会话 ID 不是 bearer token。每次访问均重新校验归属与当前权限，不允许仅凭猜中 ID 读取结果。资源过滤先于排名、计数、分页和字段摘要。

### 新协议输入示例

下例是参数形状，`${...}` 为运行时返回的引用，不是内置资产或表名。

```json
{
  "contractVersion": "sql-studio.metadata.v1",
  "operation": "DESCRIBE",
  "discoveryId": "${discoveryId}",
  "bindingId": "${bindingId}",
  "objectRefs": ["${objectId}"],
  "snapshotId": "${snapshotId}",
  "include": ["columns", "primaryKeys", "foreignKeys"],
  "freshness": {"policy": "REQUIRE_FRESH", "maxAgeSeconds": 300},
  "pageSize": 50,
  "cursor": null,
  "knownEvidenceRefs": []
}
```

300 秒和 pageSize=50 是示例请求，不是强制探索策略；实际新鲜度与单次载荷上限取管理员配置和工作流预算的交集。

| 字段 | 约束 |
| --- | --- |
| contractVersion | 新协议固定为 `sql-studio.metadata.v1` |
| operation | LIST_ASSETS、LIST_NAMESPACES、SEARCH、DESCRIBE、RELATIONS、CHANGES、REFRESH 中的一种 |
| discoveryId | 必填，服务端创建的发现会话引用 |
| bindingId | 资产级操作必填；LIST_ASSETS 可省略，但必须按当前身份返回可绑定逻辑资产 |
| query | SEARCH 的自然语言、字段或物理名检索文本；长度受预算限制 |
| objectRefs | DESCRIBE/RELATIONS 的对象身份，必须解析到绑定授权范围；数量有载荷上限 |
| namespaceRef | LIST_NAMESPACES/SEARCH 可用的 catalog/schema 引用，不接收连接串 |
| snapshotId | 指定快照时检查归属与版本；不得跨资产混用 |
| include | 请求字段、主键、外键或已授权关系；请求未知能力返回 UNSUPPORTED |
| freshness | ALLOW_STALE、PREFER_FRESH 或 REQUIRE_FRESH；策略不能代替刷新权限 |
| pageSize/cursor | 稳定快照的分页参数；游标由服务端签名或存储验证，不能修改以越权跳页 |
| sinceSnapshotId | CHANGES 使用的历史快照；超过保留边界时返回 SNAPSHOT_EXPIRED |
| knownEvidenceRefs | 可选证据引用，用于增量返回；服务端验证存在性、租户和当前可见性 |

不接收任意 `sql`、`script`、JDBC URL、datasourceId、host、用户名、密码或自定义系统目录 SQL。系统目录访问通过已有 Collector 的参数化、受限实现完成。物理名精确定位可以作为 SEARCH 的条件，但不能把模型提供的任意字符串直接拼成系统 SQL。

### 操作语义

| 操作 | 行为 | 约束与新鲜度 |
| --- | --- | --- |
| LIST_ASSETS | 返回当前调用者可绑定的既有逻辑数据源资产 | 只给展示信息及能力，不返回管理配置或被隐藏资产总数 |
| LIST_NAMESPACES | 分页列出绑定内可见 catalog/schema | 不要求先进行表搜索 |
| SEARCH | 搜索授权表、视图、字段、注释 | 同时支持语义检索与精确物理名，返回 nextCursor 和截断说明 |
| DESCRIBE | 按对象引用读取结构和键 | 不要求重新经过 SEARCH；直接读取当前仍可见的已知对象 |
| RELATIONS | 查询物理外键和有来源的业务关联 | 两端对象和字段均要可见；推测关联另列假设 |
| CHANGES | 比较两个允许访问的快照 | 返回 ADDED/CHANGED/REMOVED，不能把被撤权对象的详情当删除记录泄露 |
| REFRESH | 对绑定内指定对象或范围请求元数据刷新 | 单独匹配现有权限；不可调用无界管理员全源刷新或业务 SQL |

REQUIRE_FRESH 而没有刷新权限时返回 METADATA_STALE 或 PERMISSION_DENIED，不静默调用管理接口。驱动不支持局部采集时返回支持范围，按既有管理员策略执行受限刷新或明确拒绝；不伪称只刷新了一个对象。

## 新协议结果

结果通过现有 MCP 结果工厂输出 structuredContent，文本仅为授权结果的摘要。以下是拟定领域 payload；现有通用状态、错误与 trace envelope 继续由 Runtime/MCP 适配器生成。

```json
{
  "schemaVersion": "sql-studio.metadata-result.v1",
  "operation": "DESCRIBE",
  "discoveryId": "${discoveryId}",
  "invocationId": "${invocationId}",
  "bindingId": "${bindingId}",
  "bindingRevision": "${bindingRevision}",
  "snapshotId": "${snapshotId}",
  "visibilityRevision": "${visibilityRevision}",
  "sourceKind": "DATABASE_CATALOG",
  "databaseType": "${databaseType}",
  "dialect": {"name": "${dialect}", "version": null, "source": "REGISTERED_DATASOURCE"},
  "collectedAt": "${ISO8601}",
  "sourceUpdatedAt": null,
  "freshness": "FRESH",
  "objects": [],
  "relations": [],
  "changes": [],
  "evidenceRefs": ["${evidenceId}"],
  "page": {"returned": 0, "nextCursor": null, "truncated": false},
  "limitations": [],
  "doesNotExecuteSql": true
}
```

FRESH 表示达到本次声明的新鲜度策略，不保证采集后数据库永远不变。不得把 `sourceUpdatedAt=null` 填成采集时间。版本与可见性修订号是服务端返回值，不由模型生成。

### 对象与关系类型

```text
MetadataObject
  objectId, assetRef, catalog?, schema?, name, kind, qualifiedPath[]
  objectVersion, snapshotId, collectedAt, sourceUpdatedAt?
  columns[]:
    fieldId, name, ordinalPosition, nativeType, nullable, defaultExpression?
    comment?, generated?, evidenceRefs[]
  primaryKeys[]:
    constraintId, name?, columns[{fieldId, ordinal}], evidenceRefs[]
  foreignKeys[]:
    constraintId, sourceObjectId, targetObjectId
    columnPairs[{sourceFieldId, targetFieldId, ordinal}]
    updateRule?, deleteRule?, evidenceRefs[]

RelationEvidence
  relationId, sourceObjectId, targetObjectId, columnPairs[]
  kind: FOREIGN_KEY | APPROVED_MAPPING | DOCUMENT_DECLARATION
  status: VERIFIED_PHYSICAL | DECLARED_BUSINESS
  sourceReference, sourceVersion, evidenceRefs[]
```

无权限字段不能出现在主外键、注释、定义、关系摘要或总数中。关系的任一端不可见时，不返回对端身份；不能用“已隐藏关联到某表”泄露隐藏表名。模型提出的关联采用生成输出中的 `assumptions`，不注册成 VERIFIED_PHYSICAL。

### 证据登记

复用 `EvidenceStorePort.register(EvidenceRecord,EvidenceLineage)` 与 `createSnapshot()`。元数据证据 payload 为已授权结构投影，contentSha256 为该投影的规范化内容摘要。lineage 关联 source asset、collector/tool invocation、discovery、task/run 和 generation。

配置指纹、连接串和口令不进入 payload、游标或模型上下文。历史证据读取也需要当前权限；引用合法但权限被撤回时返回拒绝，不用旧缓存继续回答隐藏结构。生成输出只能引用 Runtime 实际登记且属于该发现会话的 evidenceId。

## Mapping 与历史 SQL 检索契约

先复用注册目录里现有、已授权的文档检索能力和企业术语工具。项目私有文件若缺少可复用的 MCP 搜索入口，拟增加薄应用适配工具 `sql_studio_asset_query`，经现有 MCP 注册与 Gateway 发布，不新建文档索引或数据库访问框架。

该名称是拟新增名称，不是当前已发布工具。它由 SQL Studio 只读应用端口提供工程/文档权限过滤，部署时要验证其调用身份传递和目录绑定，不能在 MCP 模块中直接依赖 API 模块实体。

```json
{
  "contractVersion": "sql-studio.asset.v1",
  "operation": "SEARCH",
  "discoveryId": "${discoveryId}",
  "projectId": "${projectId}",
  "kind": "MAPPING",
  "query": "${userOrModelQuery}",
  "cursor": null,
  "pageSize": 20
}
```

操作为 SEARCH 或 READ；kind 为 MAPPING、SQL_REFERENCE。READ 使用先前返回的 assetRef 和 sourceVersion；projectId 必须与发现会话归属一致。输出包含 assetRef、来源类型、文档/文件版本、命中片段、sheet/行号或代码范围、evidenceRefs 与分页信息。片段截断必须显式标识。项目文件复用已有 owner 检查，文档复用 `SearchPermissionContext`，禁止绕过端口遍历其他用户文件或拉取未授权原件。

## 发布声明和工具集合

```json
{
  "workflowContract": {
    "schemaVersion": "tool_workflow_contract.v1",
    "workflowRole": "DIRECT",
    "protocolFamily": "sql_metadata",
    "inputEnvelope": "arguments",
    "resultEntityKind": "sql_metadata_evidence"
  },
  "sqlStudioCapabilities": {
    "contractVersion": "sql-studio.metadata.v1",
    "workflowId": "SQL_METADATA_DISCOVERY",
    "sideEffect": "NONE",
    "supportsPagination": true,
    "supportsSnapshotReferences": true
  }
}
```

workflowContract 为现有协议形状；sqlStudioCapabilities 是拟新增能力描述，不是权限授权。readOnly 标记也不构成安全证明。ToolRuntimePolicyProvider、MCP 服务端授权和资产可见性过滤仍须执行。

生成 profile 的工具集合只包含经目录和权限验证的元数据、Mapping 与案例只读能力。不能因工具名称带 metadata 就放行；模板、复合工具、路由别名同样检查其实际能力与作用域。未知副作用的工具默认不进入生成集合。

## 错误语义

| 拟定领域错误码 | 处理与恢复 |
| --- | --- |
| PERMISSION_DENIED | 返回已拒绝，不返回隐藏对象是否存在；不可改用别的工具绕过 |
| BINDING_REQUIRED / BINDING_CHANGED | 要求重新选择或确认绑定，不自动换数据源 |
| METADATA_STALE | 当前快照不满足新鲜度；可由有权用户请求刷新 |
| METADATA_UNAVAILABLE | 采集或存储不可用；不能等价成零匹配 |
| SNAPSHOT_EXPIRED / CURSOR_INVALID | 在仍授权的绑定范围重取快照/分页，不混合旧新页面 |
| OBJECT_NOT_FOUND | 仅在允许披露且完成适当范围检索后返回；不用于探测越权对象 |
| AMBIGUOUS_OBJECT | 返回可见候选及限定名，请模型或用户选择 |
| UNSUPPORTED_OPERATION / UNSUPPORTED_DIALECT | 标注当前能力边界，不伪装成功 |
| BUDGET_EXHAUSTED / CANCELLED | 保留已获授权证据，返回部分覆盖与可继续引用 |

这些领域码映射到现有 MCP 规范结果与任务错误，不另创一套传输错误协议。空页、查询失败、权限拒绝、未刷新和真实对象不存在必须可区分。
