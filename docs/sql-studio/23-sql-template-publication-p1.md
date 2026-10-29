# SQL Studio → MCP SQL 模板发布 P1

实施日期：2026-10-28—29。依据：[P0 审查](22-sql-template-publication-audit.md)。本次使用现有 SQL 定义、查询网关、能力发布器和授权体系，没有增加 SQL 执行器、模板数据库或第二套发布入口。

## 实际链路

```text
SQL Studio 保存文件 / version
  → SqlStudioPublicationController：文件所有权、workspace、来源版本和 SQL digest
  → TemplateCapabilityController：现有发布权限、内部服务凭据、owner grant
  → SqlTemplateAuthoringService：mcp_sql_template 草稿及 SQL 修订
  → 显式测试：原 SqlTemplateService renderer
      → 原 SqlQueryExecutionWorkflow
      → 现有 TemplateExecutionTarget 固定已授权数据源
      → 原 SqlQueryExecuteService：只读检查、JDBC、超时/行数限制、脱敏和审计
  → TemplateCapabilityService：真实 SQL 证据门禁、首次写入完整不可变快照
  → 原 mcp_tool_release / TemplateCapabilityPublisher
  → 原 Agent 授权发现 / tpl_* / sql_query_execute
```

`ToolReleaseService` 的 CONTROLLED_TEMPLATE 检索发布流程保持原职责，SQL Studio 不调用它。原有 TEMPLATE_CONTRACT_VALIDATION 对旧模板保持兼容；Studio SQL 必须携带 SQL_DATABASE_EXECUTION 证据，不能通过契约预览替代数据库测试。

## 行为与安全边界

- 新增来源、作者、租户、修订、乐观版本和测试证据字段，继续保存在 `mcp_sql_template`。旧行 `publication_group_id IS NULL`，保持旧行为。
- 草稿和非活动历史修订 enabled=false，不进入正式目录。保存草稿不执行 SQL。已发布 SQL/参数/数据源不能通过原管理服务修改或删除，更新只能新建修订。
- 测试仅接受一条只读 SQL，10 秒、50 行；通过现有网关执行。禁止该次测试沿元数据自愈路径换一条 SQL。保存证据含 SQL/Schema/来源指纹、实参、数据源配置摘要、结果摘要、时间和耗时，不保存查询结果行。
- 数据源权限、方言和绑定在保存时检查。模板白名单保留：未批准可以保存 inert draft，界面显示待批准编码；测试、发布和调用均不能绕过白名单。
- 修改 SQL、参数 Schema、元信息或绑定会清除测试证据。发布再次核对文件版本、实参、指纹、数据源配置和权限。
- 每组修订使用既有数据库行锁，动作检查 expectedVersion；发布/回滚还检查 expectedActiveReleaseId，拒绝旧窗口覆盖新发布。create 使用稳定 operationId；同一次 testId 的同指纹重试不重复执行。
- 作者修订行锁阶段的超时、死锁/序列化竞争及创建时的唯一键竞争返回安全的 409，客户端重新加载后可用原操作标识对账；不会把这些底层 SQL 异常直接作为发布失败详情返回。
- 同一数据源内工具名称随修订保持稳定。更换数据源需要显式绑定新工具，不自动扩大 Agent 权限。
- SQL 正文、Schema、数据源及真实测试证据必须在发布记录**首次插入前**写全，遵守现场既有的已发布记录不可变数据库触发器。
- Studio SQL 工具首次发布时保存真实输出 Schema。MCP 工具说明明确原有 `ToolOutput → sql_result.v1` 封装和 `$.data.data.rows` 路径，不虚构顶层 SQL 列；其他模板家族的发布协议不改动。
- Studio SQL 的绑定摘要规范化 JSON 对象键顺序，重启不会因 Map 遍历顺序误判修改；旧 SQL 模板依赖投影排除新增作者字段，保留其原有摘要规则。
- 回滚对历史原 SQL/参数/数据源重新授权并真实测试，通过后才激活原修订并生成新发布记录。失败回执保留，线上版本保持不变。不会回写 SQL Studio 源文件。
- owner grant 和 Agent 工具绑定沿原管理入口执行；发布不自动授权所有 Agent。租户/用户来自已登录 API，不接受模型或浏览器传入的身份。
- Studio 作者修订只有一套活动发布关联。通用能力管理页复用该作者已完成且仍有效的发布，不另建记录、不重复执行 SQL；离线或依赖变化后须回到 Studio 重新测试/发布或恢复。作者修订的发布仍要求源文件作者身份；已授权 Agent 的执行权限规则不变。

## 页面

SQL Studio 工具栏新增“发布为 MCP 模板”，保留 Monaco、文件版本、工程文件和 SQL diff 流程。面板包含基本信息、JSON Schema、真实测试、人工确认、SQL/发布历史。未保存文件、未同步草稿、测试失败、实参变化都不能直接发布。服务端回执决定成功/失败，无模拟进度。

创建下一修订时，若 SQL 占位符变化，按新占位符初始化可编辑 Schema，并保留已有本地定义；占位符未变则保留原默认值、可选性与 Schema。新参数的初始类型为 string，须由作者核对后测试。兼容现有 HTTP 内网部署，不依赖仅安全上下文可用的 crypto.randomUUID。

## 数据库升级与回退

先备份 **MCP 配置数据库**，在首次启动新应用前根据其真实类型选择一个脚本；不要在查询的业务数据库执行这些迁移。

- PostgreSQL：`deploy/docker/application/migrations/20261029-sql-template-authoring.postgresql.sql`，增量、可重复执行，含组内修订唯一索引。
- MySQL 8：同目录 `.mysql.sql`，按现场迁移台账执行一次；DDL 会独立提交，不得当作 PostgreSQL 使用。
- 必须执行迁移，尤其唯一索引；不依赖 Hibernate 自动更新补齐完整迁移。
- 停止发布/测试请求后，`.rollback.sql` 将本功能的活动发布和 SQL 修订下线，再恢复旧应用镜像并刷新 MCP 目录。保留新列、正文、测试证据和历史；不 DROP 表、不覆盖业务数据。不应把整个备份直接恢复到有后续写入的数据库。

## 验证记录

结果汇总：[脱敏现场记录](23-sql-template-publication-p1-evidence/acceptance.json)、[自动化结果](23-sql-template-publication-p1-evidence/automated-tests.json)、[真实模型记录](23-sql-template-publication-p1-evidence/real-model-agent.json)、[浏览器恢复记录](23-sql-template-publication-p1-evidence/browser-verification.json)。

| 验证 | 结果及实际边界 |
| --- | --- |
| 后端回归 | 84 项通过、0 失败/错误/跳过；包含原有 63 项。新增 14 项作者生命周期/H2 JDBC、4 项 API 来源检查、1 项首次插入完整快照检查、1 项摘要顺序稳定性检查、1 项旧字段投影兼容检查。授权/Repository 的单元替身不作为现场证明。 |
| 前端回归 | SQL Studio 16 个文件、95 项通过；Vue 结构检查与 Vite 构建通过。 |
| 现场数据库 | 使用现有 MySQL 实例执行受控 SELECT：成功/语法失败/非法写入拒绝、50 行真实上限、约 10 秒真实超时。没有创建空业务数据库或业务表。 |
| 发布生命周期 | 原测试失效、来源版本冲突、不可变发布快照、新版本/历史 Schema、integer → number → integer 恢复，均已联调。 |
| 数据源回滚 | A → B 后重新测试回滚，恢复 A 的 SQL、参数、绑定；B 工具不会自动授予 Agent，授权后可执行，回滚后下线。 |
| 并发 | 并发发布只有一个不可变发布；锁竞争为成功/安全 409；两份草稿获得不同修订号；同版本并发更新一方被拒绝。 |
| 统一入口 | 通用能力页复用 Studio 的既有发布，历史数量和测试回执不变；新修订及回滚仍通过同一链创建新发布，真实 Agent 调用成功。 |
| 权限 | 真实登录的同租户其他用户、跨租户用户不能读取或发布他人源文件；未授权数据源为空；有文件所有权和编辑权但无发布权的用户被拒绝。 |
| MCP 协议 | 真实 tools/list / tools/call：授权 Agent 可发现及调用，未绑定/跨租户不可发现，跨租户调用和数据源参数覆盖被拒绝；最终 UI 发布工具也真实返回 8。 |
| 真实模型 Agent | qwen3.8-flash 受控任务 `7a176aa6-73fb-3f0a-bcca-70326b9f054c` 为 SUCCESS / SUCCEEDED，48 条真实事件包含 TOOL_CALL、TOOL_RESULT、ANSWER；`SELECT {{value}} + 1`、value=7，返回 acceptance_value=8。不是 Mock。 |
| 真实页面 | 浏览器实际创建草稿、保存绑定/参数配置、执行 SQL 测试并确认发布通过；重新打开及刷新恢复通过，无 pageerror。 |
| 升级/数据保留 | PostgreSQL 增量脚本重复执行通过、唯一索引已安装；与升级前备份比较，旧 SQL、Schema 语义、数据源及 enabled 保持一致。无损回退脚本在事务内执行验证后 ROLLBACK。原业务数据源配置及生产 API/MCP 容器 ID 均保持原值。 |
| 冷启动 | MCP 重启前后同一活动发布的绑定摘要完全一致，无须重新发布即可继续通过真实 Agent 上下文执行。 |

现场检查合计 82 项通过；另有浏览器历史恢复检查 3 项通过。候选服务为 API、MCP `sql-publication-20261029-r10`，部署清单见 [build-manifest.json](23-sql-template-publication-p1-evidence/build-manifest.json)。只替换隔离服务，生产未发布。

首次真实模型回合确实暴露过 `EDGE_CONTRACT_FAILED`：把 SQL 列误当顶层字段。保留该次取消记录，在发布工具补充真实输出 Schema/说明后复验成功，没有关闭 Runtime 契约校验，也没有延长任务总超时。此次没有重构 Runtime OS 核心流程。

截图：[发布面板](23-sql-template-publication-p1-evidence/publication-overview.png)、[真实 SQL 测试](23-sql-template-publication-p1-evidence/browser-real-sql-test.png)、[页面发布成功](23-sql-template-publication-p1-evidence/browser-published.png)、[不可变 SQL 历史](23-sql-template-publication-p1-evidence/published-sql-history.png)、[刷新恢复](23-sql-template-publication-p1-evidence/publication-reloaded.png)。

本地验证命令：

```powershell
mvn.cmd -o -Pbackend-only -pl chatchat-api -am test `
  '-Dcontainer.image=true' '-Dfrontend.skip=true' '-Dfrontend.install.skip=true' `
  '-Dtest=TemplateCapabilityServiceTest,ControlledTemplateReleaseExecutorTest,ToolReleaseLifecycleTest,TemplateAssetCatalogServiceTest,TemplateQueryMcpToolPublisherTest,TemplateQueryAdminControllerTest,SqlTemplateServiceTest,SqlQueryExecutionWorkflowTest,SqlTemplateAuthoringServiceTest,SqlStudioPublicationControllerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
npm.cmd --prefix chatchat-api/web-app run test:sql-studio
```

浏览器只读恢复验证脚本：`scripts/verify-sql-template-publication-browser.mjs`，通过 `SQL_PUBLICATION_BASE_URL`、`SQL_PUBLICATION_SESSION_FILE`、`SQL_PUBLICATION_PROJECT_ID`、`SQL_PUBLICATION_REVISION_ID`、`SQL_PUBLICATION_OUTPUT_DIR` 指定现场，`BROWSER_EXECUTABLE` 可指定浏览器。会话文件必须放忽略/受保护目录，不能提交仓库。真实页面发布的现场回执留在保护目录，公开目录仅保留脱敏结果和截图。

## 尚未完成的现场条件

- 本次配置数据库是 PostgreSQL；MySQL 8 配置库迁移脚本尚未在 MySQL 配置库联机执行。现有 MySQL **查询**实测不能替代该项迁移验证。
- 尚未部署到生产。生产升级前须完成备份、选择正确配置库迁移脚本、核对数据源白名单及原发布权限，再执行同样的业务验收。
- 原 `data_query_query` 业务模板目录没有新增一份 SQL 副本，也未把它改造成作者发布入口；其现有边界保留并经原回归测试。新模板的现场验收入口是原 TEMPLATE_CAPABILITY 的授权 `tpl_*` 发现/调用链。

## 变更范围

- API：`controller/datascience/SqlStudioPublicationController`、`controller/mcp/TemplateCapabilityController`。
- MCP：`SqlTemplateConfig`、`SqlTemplateConfigRepository`、`SqlTemplateService`、`SqlTemplateAuthoringService`、`SqlTemplateAuthoringController`、`TemplateAssetCatalogService`、`TemplateCapabilityService`、`TemplateCapabilityPublisher`。
- Vue：`SqlTemplatePublicationPanel`（Vue/独立 JS/CSS）、`SqlStudioView`、`sqlStudioApi`、SQL Studio 测试入口。
- 测试：`SqlTemplateAuthoringServiceTest`、`SqlStudioPublicationControllerTest`、`TemplateCapabilityServiceTest`、`SqlTemplatePublicationPanel.test.js`。
- 运维：上述三个迁移、`scripts/verify-sql-template-publication-browser.mjs`、本交付记录及脱敏证据目录。

完整路径和 SHA-256 见 [changed-files.json](23-sql-template-publication-p1-evidence/changed-files.json)。与本任务无关的分析执行链路、Cline 诊断页面等既有工作区修改未撤销，也不列为本任务新增功能；原 64 + 9 个文件与各自交付散列一致。
