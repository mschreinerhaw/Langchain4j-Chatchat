# Studio 页面、元数据校验与会话隔离整改

## 基线与范围

修复分支：`fix/studio-isolation-metadata-20261029`。先同步 `origin/main`，再快进纳入当前环境的最新分支 `origin/fix/runtime-analysis-boundaries-20261028`，代码基线为 `d29a2c538`。原工作区干净，没有覆盖未提交修改。

## 根因与修改

| 问题 | 根因 | 修改 |
| --- | --- | --- |
| Python 顶部提示 | `DataScienceView.vue` 在开发工作区外额外挂载 `PythonReusePanel` | 删除挂载、导入和注册。保留复用组件源码及 `PythonCodeReuseService`、运行服务和既有编辑器功能 |
| SQL 中央残留阴影 | Explorer 和 grip 原本已受同一布局状态控制，但隐藏 Monaco 主／差异编辑器只设置 `visibility:hidden`，其后代的绘制状态可覆盖继承值 | 隐藏编辑器使用 `display:none`，释放绘制区域。保留现有 ResizeObserver、动画帧布局及视口恢复机制，无遮罩 |
| 快照外表误判 | `validate.py` 把未收入上下文的表直接判为校验失败；最终输出没有先补齐授权元数据 | 先使用 SQLGlot 提取真实物理表引用，经现有绑定及 MCP 服务按需 DESCRIBE，补充快照后再执行同一字段校验器 |
| Studio 混入最近对话 | Studio 创建及历史投影沿用默认 `PLATFORM_UI`，而最近对话已在 Repository 按该字段过滤 | 复用 `source_channel` 写入 `SQL_STUDIO`／`PYTHON_STUDIO`，保持普通会话 `PLATFORM_UI` 和原 `AGENT_API`；迁移历史分类 |

元数据调用链：`SqlStudioMetadataAssistant.Turn.finish` → `SqlStudioReferenceResolver` → `SqlStudioMetadataService.operateScoped` → `SqlStudioMetadataGateway.invokeScoped` → 既有 MCP `sql_schema_context_query` → `SqlMetadataDiscoveryService`。工程代码提案通过 `CodingWorkspaceService.propose` 复用同一解析和发现逻辑；选择数据库绑定后无需先选择具体表。

解析器按方言规范化未加引号的标识符，保留方言中的引号语义。CTE 和子查询别名通过 SQLGlot 查询作用域排除。本地显式声明的临时表单独建模，不作为数据库资产发现；未声明的临时表拒绝。仍校验真实字段，仍不执行生成的 SQL。

元数据仅加载 SQL 实际引用的表，限制最多 64 个引用。不把全库结构注入模型。已缓存的引用对象也重新经过 MCP 授权校验，拒绝已撤销权限；不存在可见对象、越界命名空间及同名歧义分别返回明确错误。业务字段证据检查继续执行。隐藏对象不会通过错误消息泄露其存在性。

会话调用链：SQL 工程和生成入口 → `WorkspaceOriginService.createForStudio` → `ConversationService.classifyStudioConversation`。Python／SQL Coding 历史通过 `StudioTaskHistory` 使用相同分类服务。来源由服务端入口固定，HTTP 请求不能任意指定或切换其他 Studio 类型。查询仍使用服务器解析的租户、用户及已校验的旧用户名别名。

普通列表、摘要、搜索、分页及总数继续在 `ChatSessionRepository` 的现有统一条件中筛选 `PLATFORM_UI`。新增既有会话接口的筛选入口：

`GET /api/v1/conversations/user/{userId}/studio-history?channel=SQL_STUDIO|PYTHON_STUDIO&keyword=&page=1&pageSize=30`

该接口按租户、授权用户别名、来源和关键词执行数据库分页。会话详情、消息恢复和删除继续复用原服务；SQL Studio 的工程历史仍按工程所有权查询。

## 数据兼容

不新增表或字段，不删除消息。`StudioSessionMigration` 在既有 SQL 工程迁移之后执行，依据工程／生成作用域的持久化关联，以及 `StudioTaskHistory` 写入的固定 agent 标签，幂等回填历史来源。不通过用户可编辑标题猜测类型，不改变历史更新时间，保留 `AGENT_API` 分类。

发布前备份完整 API 数据库、原会话来源、容器配置和部署配置。回退恢复旧容器及原来源分类，不恢复整库覆盖其他数据。

## 修改文件

- 前端：`chatchat-api/web-app/src/views/DataScienceView.vue`、`src/js/views/DataScienceView.js`、`src/styles/pages/sql-studio-ide.css`。
- 元数据：`chatchat-api/src/main/resources/sql-toolchain/validate.py`、`datascience/sql/SqlStudioReferenceResolver.java`、`SqlStudioMetadataAssistant.java`、`SqlStudioService.java`、`runtime/coding/workspace/CodingWorkspaceService.java`。
- 会话：`chatchat-chat/.../conversation/service/ConversationService.java`、`persistence/ChatSessionRepository.java`；API 的 `workspace/WorkspaceOriginService.java`、`datascience/StudioTaskHistory.java`、`StudioSessionMigration.java`、`controller/chat/ConversationController.java`。
- 测试：`SqlStudioReferenceResolverTest`、`StudioSessionMigrationTest`、`StudioConversationChannelTest`、`SqlStudioIntegrationTest`、`ConversationHistoryOwnerAliasTest`，以及两个 SQL 测试的创建入口桩更新；`scripts/tests/test_studio_sql_references.py`。
- 运维：`scripts/deploy-studio-remediation.py`、`scripts/verify-studio-remediation-browser.mjs`。

## 验证与限制

编辑器观察器还修改了 `chatchat-api/web-app/src/js/utils/sqlEditorLayout.js`：隐藏时清除旧尺寸，恢复显示时即便尺寸未变也重新布局；对应测试覆盖这一恢复场景。

前端定向回归 32 项通过，生产前端构建通过。独立真实 SQLGlot 回归 8 项通过，覆盖物理表提取、CTE／子查询／JOIN、Catalog／Schema、大小写、各方言、字段拒绝、视图与临时表。最终 Maven 定向回归 253 项通过，失败、错误、跳过均为 0；完整应用包构建通过。

修复前真实浏览器校验已复现隐藏编辑器仍有绘制布局盒的问题。发布后的浏览器和服务验证结果另附证据。

线上现有验收账号可以进入 Studio，但查询授权元数据资源返回 HTTP 403。保留该拒绝，未修改数据源权限。`TZRT_HYXXLS` 在真实业务库的存在性、所属 Schema 和当前账号权限仍需有该数据源权限的身份验证；授权表补齐机制及拒绝越权行为通过自动化测试验证，不将受限账号的结果描述为实表验收通过。

## 发布与线上验收

已发布到 `192.168.195.221:18100`，应用镜像 `runtime-os-api:studio-remediation-e91b2b117-20261029`，应用代码提交 `e91b2b117`。完整 JAR 的 SHA-256 为 `ba96cfef6487fb9a2fccbdc6daf61a69b09d2c9b16bc4c636552087b60d22238`。使用已有传输工具在服务器重建完整 JAR，最终字节哈希与本地构建一致。

发布目录 `/opt/chatchat-releases/studio-remediation-20261029`；`backup` 保存数据库、原会话来源及容器／配置备份。旧 API 和共享网络的 proxy 容器保留，名称记录在 `evidence/deployment.json`。其他运行容器 ID 均未改变。新版本已重建 Coding 回调防火墙并刷新网关。

迁移结果为 `SQL_STUDIO=22`、`PYTHON_STUDIO=4`、`PLATFORM_UI=827`、`AGENT_API=165`。除来源字段外的全部会话行指纹一致，消息索引数量一致。

真实接口验证已通过：新 SQL 工程会话自动分类；普通列表和分页总数不包含该 Studio 会话；SQL／Python 历史分别筛选；跨用户请求返回 403；详情可恢复；普通会话仍出现在普通列表。只创建、清理验收脚本自己的临时对象，没有删除历史业务数据。

真实浏览器验证已通过：1600、1280、1066 像素三个窗口宽度，各进行三轮展开／折叠；折叠释放 257 像素，隐藏编辑器没有布局盒，截图无中央残留阴影。Python 提示条不存在，页面错误为 0。源码与运行态截图、接口回执见 [证据目录](studio-remediation-20261029-evidence/)。

验收身份为现有部署服务账号，不具有业务元数据授权，也没有 READY Python 环境，故没有宣称真实业务 SQL 或 Python 脚本已执行通过。额外调用原 Python AI 说明接口时，该服务账号返回 `user not found`；相关页面、Python 执行服务及该说明调用链未在本次改动中替换，仍需用业务账号验证完整交互。普通登录 token 原本只保存在内存，重启后需要重新登录；验收已通过正常登录续建会话。
