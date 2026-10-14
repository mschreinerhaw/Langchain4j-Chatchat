# SQL Studio P2 元数据发现基础实施记录

本批在[架构审查](12-online-metadata-architecture-review.md)和[P1 权限基础](17-metadata-permission-foundation.md)之后实施。它交付元数据发现领域工作流、应用端发现上下文与逻辑资产绑定；尚未完成 P2 全部能力，也不代表真实模型、真实数据库或生产部署验收通过。

## 实际调用链

```text
SQL Studio Metadata HTTP API
  -> WorkspaceService + SqlStudioService.project（工程归属）
  -> SqlStudioMetadataService（持久化发现身份与逻辑绑定）
  -> SqlStudioMetadataGateway
  -> 现有 McpRuntimeKernel + ApiKernelScopeResolver
  -> 已注册的 sql_schema_context_query
  -> 现有 McpAuthorizationService（每次取得当前授权）
  -> SqlMetadataDiscoveryWorkflow / SQL_METADATA_DISCOVERY
  -> SqlMetadataDiscoveryService
  -> 现有 MetadataIndexService + Lucene 授权集合内排序
```

也可以由现有模型工具循环直接选择 v2 操作。一次工作流调用只执行调用方已选定的一项操作；没有预设“列库、列所有表、查询字段”的探索顺序，没有业务 SQL 执行依赖。本批没有改动 Runtime 核心、创建第二套数据库连接框架或实现新的通用 Agent。

## 已落地契约

公开工具继续复用 `sql_schema_context_query`。不带 `contractVersion`/`operation` 的旧请求保留原五表契约；显式 v2 请求不经过旧版五表截断。

```json
{
  "contractVersion": "sql-studio.metadata.v2",
  "operation": "SEARCH",
  "assetId": "${authorizedAssetRef}",
  "query": "${userMetadataIntent}",
  "page": 1,
  "pageSize": 25
}
```

本批仅发布 `LIST_ASSETS`、`LIST_NAMESPACES`、`SEARCH`、`DESCRIBE` 四项操作。除 LIST_ASSETS 外必须指定逻辑 assetId；DESCRIBE 必须指定真实 tableName，同名多表必须补充 database/schema。不猜测物理标识符，不把名称相似性解释为外键。

结果使用 `items`、`page`、`pageSize`、`totalMatched`、`hasMore`、`snapshotId`、`provenance`、`doesNotExecuteSql`。页大小为 1–100；从第二页起必须提交第一页返回的 `expectedSnapshotId`，授权可见结构发生变化时返回快照冲突并要求重新翻页。分页针对完整授权集合，不先取前 500 张表再筛选。

快照来自已授权资产、可见表和可见字段的规范化内容散列，不使用配置指纹或口令。采集时间变化、口令轮换不会单独改变结构版本；字段增删改会改变版本。一次调用只读取一个索引修订，避免表和字段跨修订混用。采集时间标记为 collectedAt，数据库实际更新时间未知时 sourceUpdatedAt 为 null；来源方言与目标生成方言分开处理。

索引缺失或不可用返回 NEEDS_REFRESH，不自动打开连接、采集全部数据源、泄露底层错误或宣称已验证 Schema。刷新继续通过现有元数据管理能力完成。表关系标记 NOT_COLLECTED，不把普通列键当作已确认 JOIN 依据。

## 已落地应用接口

根路径：`/api/v1/data-science/sql/projects/{projectId}/metadata`。

| 方法与后缀 | 当前行为 |
| --- | --- |
| GET `/assets` | 经既有 MCP 目录返回授权资产分页；支持 serviceId、page、pageSize、expectedSnapshotId |
| POST `/contexts` | 校验资产访问，生成 discoveryId/bindingId，持久化 Query/ETL 目的、目标方言、源/目标逻辑绑定 |
| GET `/contexts/{discoveryId}` | 按工程、租户、所有者恢复，并重新检查当前资产权限 |
| POST `/contexts/{discoveryId}/operations` | 从服务端绑定选择 MCP 服务及资产，固定已绑定命名空间，再调用领域工作流 |

```json
{
  "purpose": "ETL",
  "targetDialect": "spark",
  "bindings": [
    {
      "serviceId": "${registeredMcpServiceId}",
      "assetRef": "${authorizedAssetRef}",
      "role": "SOURCE",
      "database": "${authorizedDatabase}"
    }
  ]
}
```

仅存在一个注册提供方时可省略 serviceId；多个提供方时拒绝猜测，要求明确选择。Gateway 从注册输入 Schema 检查 v2 支持，不连接用户提供的任意地址。身份通过现有 API 认证信封绑定；不接受模型自报身份。

```json
{
  "bindingId": "${serverBindingId}",
  "arguments": {
    "operation": "DESCRIBE",
    "tableName": "${observedPhysicalTable}"
  }
}
```

operations 不接受替换 assetId/serviceId、执行 SQL、刷新或角色参数，也不允许扩大绑定命名空间。每次 MCP 操作重新执行现有授权；历史 discoveryId、旧快照和管理员身份均不授予业务 SQL 执行权。

新增应用表 `ds_sql_metadata_context`，H2/MySQL/PostgreSQL 迁移均为 CREATE TABLE IF NOT EXISTS。仅保存逻辑绑定与工程归属，不保存连接凭据或元数据结果副本。上下文恢复不依赖进程内缓存；不另建证据仓库。当前 READY 表示上下文可继续使用，不表示数据库已连通或结构已验证。

## 验证与限制

工作流测试覆盖：503 表完整分页、快照固定、结构变化冲突、密码轮换不改变版本、敏感资产/表/字段过滤、撤权后拒绝旧页、同名表消歧、索引缺失、伪造身份/执行参数拒绝，以及无需前置步骤直接描述表。

应用测试覆盖：H2 真实持久化与服务实例重建恢复、绑定不能被请求替换、跨租户上下文隔离、恢复时重查权限、真实 HTTP 认证和工程所有权、重复迁移保留上下文。Gateway 测试校验使用注册工具、现有 Kernel 和认证身份；MCP 发布入口测试校验 v2 不再经过旧五表截断且不调用 SQL 执行器。

本批选择的 8 个测试套件共 107 项通过，失败、错误、跳过均为 0。这些是契约与应用回归测试，索引、模型及远端 MCP 结果使用受控夹具；不代替十项真实数据库/模型验收。日志为仓库 target/sql-metadata-p2-tests.log，机器可读汇总为 target/sql-metadata-p2-verification.json。来源方言补充后的工作流复验日志为 target/sql-metadata-p2-provenance-tests.log。

下一批仍需完成：发现上下文接入现有工具型 Agent 生成路径、Schema 浏览器、发现证据与 Generation/Run 关联、发现预算与会话关闭、关系采集/增量变化/受控刷新契约、Mapping 与案例续查、SQL Schema 校验，以及服务器实库和真实模型验证。不会把未采集的关系、未执行的校验或未完成的部署显示为成功。
