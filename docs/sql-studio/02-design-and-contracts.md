# SQL Studio MVP 设计

## 边界

SQL Studio 属于数据科学 Workspace。三栏分别为工程文件树、Monaco 多标签编辑器、AI 助手。应用负责已授权文件快照、方言声明、结果预览及版本检查。分析 Mapping、选择参考、规划和 SQL 生成由既有 Agent Runtime 完成，不预置 Mapping→SQL 节点流程，不引入专属 Agent Loop。

## 生成闭环

1. 用户保存 Mapping 和参考 SQL，选择输入及方言。
2. 服务端授权工程及所有文件并冻结内容和版本，返回 GenerationContext 中的既有 AgentTaskSubmitRequest。
3. 前端调用原有 POST /agent/tasks，再关联 taskId；原身份、会话、RBAC、Skill/MCP 工具发现和事件协议沿用。
4. 页面通过已持久化 taskId 恢复任务并读取事件；切换 Workspace 只停止页面观察，不取消任务。
5. 服务端从终态任务的 answer 提取明确的 sql-studio.output.v1 JSON 文件包，验证路径、类型、重复项和大小。
6. 用户预览后应用文件；expectedVersion 使用提交前冻结的输出文件版本。若用户修改了已有文件，返回 409；未保存的编辑也在前端阻止覆盖。不以模型输出为写入授权。

SQL Studio 使用 `WorkspaceOriginService.createForWorkspace` 创建应用会话；这是通用的中央上下文创建扩展，仅接收注册的应用标识并用服务器身份校验。SQL 应用声明 data-science 来源，客户端 Header 无法改写该归属。原会话 API 及 create/prepare 行为保留。工程、文件、版本、生成上下文均是个人所有，不增加组织共享授权；同组织其他用户也不会自动获得访问权。拥有数据科学授权的同一用户可从其他已授权界面读取自己的 SQL 工程，workspaceId 不是隔离或授权令牌。

应用进行确认写入时若用户继续输入，前端保留新草稿及旧基准版本并标为冲突；之后保存需比较版本。新工程文件不存在时基准为 0；生成后再新建同路径文件也会触发 409。一次应用事务包含全部选择的输出，任何冲突整批回滚；一次 Generation 只能应用一次。网络中断保留 Prepared 请求，继续提交沿用原 task idempotencyKey。

## 接口

基础路径 /api/v1/data-science/sql。所有请求用现有身份/RBAC和 WorkspaceService 检查，所有对象用 ObjectAccessService。GET 列表按身份过滤，文件通过所属工程授权；不存在 404、越权 403、过大 413、非法路径/契约 400、版本冲突 409。

| 对象 | 接口 | 数据 |
| --- | --- | --- |
| Project | GET/POST /projects；GET /projects/{id} | id/name/dialect/sessionId/ownerId/tenantId；身份来自服务器 |
| File | GET /projects/{id}/files；GET /projects/{id}/files/{fileId}；PUT /projects/{id}/files | path/kind/content/expectedVersion；新文件 expectedVersion=0，已存在文件必须匹配 |
| Mapping | POST /projects/{id}/uploads?kind=MAPPING&expectedVersion=0 | multipart 文件，保留原始内容并以 Tika 提取可预览文本；JSON 为结构化字段映射或任意可解释文档 |
| FileVersion | GET /projects/{id}/files/{fileId}/versions | 不可变内容版本和来源 taskId；读取旧版本不能覆盖当前编辑 |
| Download | GET /projects/{id}/files/{fileId}/download | 原始 Mapping 或 SQL UTF-8 文件；路径是工程逻辑路径，不是宿主路径 |
| GenerationContext | POST /projects/{id}/generations | prompt/dialect/mappingIds/referenceIds/agentId/modelName；文件内容只能由服务端加载 |
| Agent Task | POST /agent/tasks；PUT /projects/{id}/generations/{generationId}/task | 原提交协议及 taskId；关联须验证任务身份、会话和冻结 query |
| Generation/Artifact | GET /projects/{id}/generations；GET /projects/{id}/generations/{generationId} | 原任务状态/事件、候选 SQL 文件、rawAnswer；原 Runtime Artifact 系统不变 |
| Apply | POST /projects/{id}/generations/{generationId}/apply | 选择候选 path；内容只取服务端终态结果，事务性版本检查 |
| Validation | GenerationView.validation | MVP 为 NOT_RUN。输出格式与路径检查不等同 SQL 语法、语义或 Spark 实际执行验证 |

所有 SQL API 都复用 `capability:data-science` 的现有接口权限与 MCP Workspace License。任务提交仍经过原 Agent API 对 agentId/Skills/资源的独立裁决；选择器不授予工具权限。API 的 TypeScript 数据投影见 `chatchat-api/web-app/src/services/sqlStudioContracts.d.ts`。不增加 SQL 专用执行状态枚举或 Artifact 引擎：候选文件来自原 Runtime 持久化最终回答，应用后由 `File.sourceTaskId` 记录来源。原 UI Artifact、执行轨迹和文件系统 Blob 接口保持原样。

示例输出契约：

```json
{"schemaVersion":"sql-studio.output.v1","files":[{"path":"sql/customer.sql","content":"INSERT OVERWRITE TABLE dim_customer SELECT customer_id FROM stg_customer;"}],"notes":"Review source and target types."}
```

结果必须为单个 JSON 对象或单个 json 代码块；不猜测 SQL 文件边界、不把说明文字直接写入文件。输出不符合契约时保留原始回答供审查，并禁止应用。输出格式要求只约束交付物，Mapping 分析、文件数量、计划和授权工具使用继续由模型决定。

## Mapping 数据设计

Mapping 保留原始文档，不强迫用户用固定模板。JSON 可采用 `schemaVersion=mapping.v1`、`sourceTables`、`targetTable`、`fields[{source,target,expression,type,nullable,description}]`、`joins`、`filters`、`writeMode`、`notes`。这些是业务资料，不是 Core 规则；不把缺失表达式自动猜测成固定 ETL 流程。模型可要求澄清不完整 Mapping。

支持 JSON/CSV/Markdown/TXT/XLS/XLSX/DOC/DOCX/PDF，预览为提取文本而非 Office/PDF 排版。JSON 接收 UTF-8。参考案例仅接收 .sql，列表展示当前身份自己的工程 SQL/上传案例，最多最近 100 项。每次最多选择 32 个 Mapping 和 32 个参考；冻结内容与版本之后，输入被编辑也不改变已提交任务。

`MAPPING` 与 `REFERENCE` 是工程文件用途字段；JSON 的 mapping.v1 是建议模板，服务端不会执行其中的 expression/join/filter。缺失字段、业务语义和 SQL 正确性仍需模型或人工判断。二进制文档不可直接在 Monaco 编辑。文本编辑会产生新版本并使最新下载返回新的文本，原上传字节仍保留在旧版本记录中。

## 数据库与兼容

SQL 源码存数据库文本，原始二进制 Mapping 以 Base64 保存在 FileVersion 中；MVP 每文件原始大小≤2MiB、提取文本≤200Ki 字符、生成上下文≤250Ki 字符、单输出≤200Ki 字符、输出≤32 文件。逻辑路径只允许安全相对路径，各段不能为 . 或 ..；不接受绝对路径、反斜线、URL 编码、控制字符或 OS 设备名。没有浏览器访问 Docker/内部文件系统的接口。

迁移只新增表，独立的 PostgreSQL/MySQL/H2 脚本随交付附上。现有 startup migration 之后执行幂等 SQL 应用 schema/config migration。可回退前端与应用版本，新增 SQL 表保留，不删除用户工程。自定义 Workspace config 需管理员显式注册页面，不覆盖用户配置。

新增 `ds_sql_project`、`ds_sql_file`、`ds_sql_file_version`、`ds_sql_generation`。PostgreSQL/H2 脚本有关系约束；MySQL 版本通过应用事务维护关联并有路径/版本唯一约束，未声明数据库外键。写入和读取都不能绕过中央对象授权。暂不做团队共享、删除/清理、二进制历史版本下载或版本恢复；不可把新表删除当作回滚。

SQL 页面地址为 `#/w/data-science/sqlStudio`，新增 `#sqlStudio` 兼容入口；旧 Python/智策地址不变。Manifest 标准 V2 精确匹配时升级到 V3，新增 sqlStudio 页面及菜单。定制或不同序列化格式的配置不会自动覆盖，管理员须合并页面声明。未知数据库产品会拒绝运行未经审查的 SQL Studio 迁移。本轮只验证 H2，不执行生产迁移。
