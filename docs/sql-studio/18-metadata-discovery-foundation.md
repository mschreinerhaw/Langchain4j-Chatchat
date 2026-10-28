# SQL Studio 元数据探索实施与验收记录

## 2026-10-28 改造前审查

审查基线为 `d9f39979d4963c48e00c52af5709e3ba7b5c006c`。在修改代码之前，已核对源码和现场接口；没有以文档或 Mock 结果推断部署能力。

| 能力 | 改造前实际状态 |
| --- | --- |
| SQL 生成、修复、优化 | 已实现：SqlStudioService → SQL_WORKSPACE_V2 → CodingWorkspaceService/Cline → 原差异审查及保存 |
| 普通模型问答 | LlmChatModeHandler 仍统一走 CodingAnswerPort/ClineContextAnswerService，简单元数据问答未独立接通 |
| SQL_METADATA_DISCOVERY | 已实现领域工作流和发布入口；不等于已经接入 SQL Studio 自主模型探索 |
| 四项元数据操作 | 真实 MCP 接口均可用；现场有 1 个提供方、8 个授权资产。旧版五表请求与 v2 分页契约分开 |
| discoveryId | 已持久化工程归属、逻辑绑定；原先没有跨轮模型对话及可复用执行证据 |
| 完整自主探索闭环 | 部分实现／仅有规划：领域工作流已有代码，专用模型循环、跨轮执行证据及业务进度聚合尚未形成已验收链路 |
| 授权、分页、快照 | 已实现，已有 Schema 浏览器复用；单次索引结果并不证明实时源库连接成功 |
| 主键、外键 | 已返回真实列键；外键关系原先统一 NOT_COLLECTED，不能据列名推断 JOIN |
| 代码规划 | StudioCodePlanService 是确定性的任务准入检查，modelCalls=0，并非一次额外模型规划 |

现场首次只读审查的 SEARCH 返回 353 个匹配、DESCRIBE 返回 QRTZ_TRIGGERS 的 16 个字段；这是旧索引调用证据。随后在隔离实例通过现有管理入口刷新 MySQL，才验证了实际源库连接。API/MCP 的配置库是 PostgreSQL，业务资产是 MySQL，两者没有替换或混用。

## 本次最小改动及实际链路

```text
Vue SQL Studio action=explore
  → SqlStudioService：保存原 Generation、工程归属、逻辑发现绑定
  → 现有 AgentTaskService / LlmChatModeHandler
  → SqlMetadataAnswerPort / SqlStudioMetadataAssistant（仅 SQL 元数据领域）
  → AgentChatModelResolver + ToolExecutionModels：当前模型策略、任务截止、取消
  ↔ 模型原生工具选择 sql_schema_context_query
  → SqlStudioMetadataGateway → 现有 McpRuntimeKernel
  → MCP 当前授权 → SQL_METADATA_DISCOVERY → 既有 MetadataIndexService/Lucene
  → 原 RuntimeExecutionCheckpointPort：保存有界执行证据、跨轮历史及已完成结果
  → 最终结构化结论；SQL 提案使用既有 SQLGlot 校验表字段
  → 原 GenerationView / Monaco / CodeChangeReview / 文件版本检查及保存
```

工程代码生成、解释、修复、优化仍保留原 Cline 路径。新增问答不初始化 Coding Workspace，不发布业务 SQL 执行器，不新增连接池、元数据索引、通用 Agent 或编排服务。

服务端从已持久化的 Generation 重新检查工程所有者、会话、原问题及选中模型，不能靠伪造 toolInput 进入其他工程。每次工具调用和最终发布均重新检查当前授权；复用执行证据前检查可见结构内容散列。切换绑定创建新的 discoveryId；快照变化不继续向模型提供旧证据或旧历史。已完成轮次恢复仍须授权及快照有效。

证据复用使用既有执行检查点，每个发现最多保存 24 份、600,000 字符工具结果及最近 4 轮问题/结论；这是运行证据而非第二套数据库元数据缓存。单份超大响应只返回收窄检索提示，不把未交付字段作为模型证据。SEARCH 默认轻量目录，模型可按需直接 DESCRIBE 或显式检索字段，没有固定探索顺序。每轮最多 24 次工具操作、12 次模型请求，接近预算时撤去工具选择并要求按现有证据整理结果；结构修复最多 2 次，不增加任务总超时。

外键通过现有只读 JDBC 连接及 DatabaseMetaData.getImportedKeys 采集，同原索引持久化。旧索引兼容缺失字段，保持 NOT_COLLECTED；成功采集后的 COLLECTED_VISIBLE 只表示当前授权可展示的关系，空数组不能证明没有隐藏关系。涉及隐藏表或字段的整个复合约束均过滤，约束内部名称不公开。物理外键不自动证明业务含义。

页面显示真实 Runtime 观察事件，按操作和执行尝试聚合，支持展开详情、耗时、Markdown、表格及代码块；点击候选表会重新执行授权 DESCRIBE。全局状态以 canonicalState 为准，单次工具或模型失败不能终止仍在重试的任务。成功恢复后保持成功状态，历史失败留在展开详情。元数据探索结果仍须经原差异审查后才能保存；本链路不执行业务 SQL。

现场还确认：原 TaskService.retry 保留 executionId，但创建新 taskId。原 Generation 若只读取旧绑定，会一直显示旧失败。现在按租户、所有者、会话、问题和 executionId 读取最新真实尝试，保留旧任务终态；显示最近 32 个尝试、最多 2,000 条事件。失败或取消后的页面进行低频静默检查，发现真实后继才恢复加载；取消按钮指向后继任务。有运行中的后继时禁止删除工程。累计耗时取真实事件的最早时间，本次尝试的 SDK/工具耗时另列。

SQLGlot 的字段校验只证明标识符和语法，不证明业务口径。新增探索路径对聚合、比率、CASE、JOIN 和时间量纲转换要求 businessDefinitions：引用真实字段注释或包含确切字段名的用户定义，并核对业务字面值；不能用版本号的数字子串证明失败码。缺少依据时不发布统计 SQL，原始模型输出保留在既有受限执行检查点。没有可信领域执行证据的通用任务不能冒充探索结果。早期探索 SQL 若尚无本检查记录，历史查看和 apply 会拒绝发布并要求重新生成；原 Cline 工程路径保持原行为。

这道检查不是行级数据验证或完整业务正确性证明。explore 使用元数据和服务端问题历史；不会为读取附件而初始化 Coding Workspace。若口径来自文档，请把确切字段定义写入问题或现有字段注释；工程附件和工程代码处理仍使用原 Cline 模式。

## 本次现场验收结果

脱敏记录和完整变更文件清单见 [验收证据](evidence/sql-studio-ai-acceptance-20261028.json)。真实业务源是已配置 MySQL，模型为已配置 qwen3.8-flash；不是 Mock 数据库或 Mock 模型。

| 场景 | 结果 |
| --- | --- |
| 实库采集 | 3 个命名空间、353 张表、2,659 个字段、34 条 JDBC 外键字段映射，持久化成功 |
| A 元数据问答 | 成功返回 8 张真实候选表及字段依据，包括 QRTZ_TRIGGERS、QRTZ_JOB_DETAILS、QRTZ_FIRED_TRIGGERS |
| B 跨轮探索 | 复用同一 discoveryId 和 15 份已复核证据；发现并修复重试新 taskId 跟踪问题，后继尝试成功 |
| C 七天失败率 SQL | 最终版本真实模型明确不给出统计 SQL：缺少失败码、记录粒度、重试去重及时间口径；不宣称失败率业务验收成功 |
| 确认字段后的 SQL | 仅查询 QRTZ_TRIGGERS 的 JOB_NAME、JOB_GROUP、TRIGGER_STATE，LIMIT 20；真实 SQLGlot 字段校验通过，模型复用 24 份证据，无额外元数据工具选择 |
| 差异审查与保存 | 浏览器真实 Monaco 差异编辑器通过；确认后保存版本 1，下载内容哈希一致，刷新后保留已应用状态；未执行 SQL |
| 授权边界 | 非管理员先获准、撤权后同一 discoveryId 返回 403；无权既有资产、他人工程、越界绑定及 SQL 参数均拒绝 |
| 快照与敏感字段 | 固定页成功、冲突页 409；真实敏感字段/表及相关 FK 隐藏，策略变化使旧快照失效，测试后恢复原策略 |
| 进度与取消 | 真实浏览器事件聚合、字段展开、历史恢复通过；取消后不转圈，后台重试新 taskId 无需刷新被跟踪，再次取消发往新任务 |
| 模型超时 | 隔离模型临时改为 1 秒且关闭 SDK 重试，实际请求约 1 秒终止，事件原因 MODEL_REQUEST_TIMEOUT；随后恢复 300 秒及原重试配置 |

自动化验收：后端主要链路 97 项、额外 MCP 回归 65 项、前端 86 项，共 248 项，失败、错误、跳过均为 0。后端包含真实 HTTP/H2 所有权、重试追踪、伪造结果拒绝、JDBC 外键和独立 SQLGlot 进程；模型/MCP 夹具只用于自动化协议测试，现场证据另列，二者没有混称。

现场模型耗时仍明显受供应商及输出长度影响：首轮约 194 秒、第二轮成功尝试约 249 秒、最终不足口径答复约 201 秒、已确认三列提案约 46 秒。调用次数是 SDK chat 轮次，单轮耗时包含 SDK 内部重试，不冒充供应商侧 HTTP 请求条数。尚未覆盖所有模型供应商和所有数据库驱动的实库联调；不支持原生工具调用的模型不能直接用于此探索模式。本次未重新做完整 Cline 代码工程的实模回归，原工程路径的 HTTP/H2 和工作区夹具回归通过。

联调期间外部清理删除了验收目录和旧发布归档；用户确认暂停清理后，保留 PostgreSQL 验收库，并从现有本地数据恢复隔离挂载，逐文件核对源与副本哈希。该中断未用于掩盖失败场景或替换空业务库。生产 API/MCP 容器身份保持不变。

## 可重复验收与回退

自动测试日志在 `target/sql-studio-ai-audit/`，现场原始记录保留在受保护的验收目录和本地忽略目录，不提交认证会话、口令、模型密钥或连接凭据。公开摘要仅包含验收任务标识、脱敏结果及摘要哈希。

真实 A/B/C 验收脚本为 [verify-sql-studio-metadata.py](../../scripts/verify-sql-studio-metadata.py)。显式指定隔离应用地址、现有认证响应文件、工程和绑定上下文；脚本提交真实任务、保留后端事件、检查跨轮身份/证据复用和 SQL 字段校验。C 场景允许模型因缺少已确认统计语义而说明不足；不能把拒绝虚构 SQL 算作失败，也不能将其称为失败率 SQL 已验证。

```bash
python3 scripts/verify-sql-studio-metadata.py \
  --base-url http://127.0.0.1:28180 \
  --token-file /protected/api-auth.json \
  --project-id "$SQL_PROJECT_ID" --binding-context-id "$SQL_BINDING_CONTEXT_ID" \
  --model "$SQL_MODEL_NAME" --dialect mysql \
  --output /protected/sql-metadata-acceptance
```

回退应用镜像即可恢复原 SQL Studio/Cline 流程，本次没有新增数据库迁移或破坏性数据变更。不要删除工程、Generation、执行证据、业务数据库或金融数据目录。MCP 降级前需保留原元数据索引备份：旧版本不认识新增 foreignKeys JSON 字段时，应恢复升级前索引，或通过原管理入口重新采集该数据源；禁止清空整个 RocksDB 以掩盖兼容问题。现场验证使用备份数据库克隆和独立 API/MCP 容器，生产容器与原始配置保持原位，尚未切换生产到本功能。

## 以下为 P2 基础阶段历史记录

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
