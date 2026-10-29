# AI SQL Studio：DBeaver / CloudBeaver 源码对标

## 审查基线与实施边界

本报告先于本轮功能修改形成。工作区已有上一轮未提交的 UI 和输出协议修复，详见 [26 号报告](26-stability-and-output-protocol-20261029.md)；本轮不重复认领这些改动。当前产品基准提交为 `35550a6fc` 加上述工作区修改。

使用相同发布标签 `25.2.0`，不追随移动的 devel：

| 仓库 | 固定 Commit SHA |
| --- | --- |
| dbeaver/dbeaver | `bd2f6f8a3c350d4f9bb914879bba791c27039a45` |
| dbeaver/cloudbeaver | `5e30bbdc77b92c4b2276cf705d57ac42f0fa3684` |

两个仓库均已本地只读检出并阅读相关源码。Windows 的 CloudBeaver 长路径检出问题通过 Git `core.longpaths=true` 解决。21 个文件的不可变 URL、SHA256、符号行号及逐文件许可证依据在 [源码清单](27-dbeaver-evidence/upstream-sources.json) 和 [可点击文件索引](27-dbeaver-evidence/source-index.md)。复核脚本为 `scripts/verify-sql-upstream-sources.py`，读取固定 Git blob 而非不受控的工作区副本。

```powershell
git clone --depth 1 --branch 25.2.0 https://github.com/dbeaver/dbeaver.git target/upstream-dbeaver-25.2.0
git -c core.longpaths=true clone --depth 1 --branch 25.2.0 https://github.com/dbeaver/cloudbeaver.git target/upstream-cloudbeaver-25.2.0
python scripts/verify-sql-upstream-sources.py
```

## 1. 实际源码调用链

以下 `D/` 指 DBeaver 固定提交，`C/` 指 CloudBeaver 固定提交；链接均通过上方清单展开到对应完整路径。

### Database Navigator

`D/plugins/org.jkiss.dbeaver.ui.navigator/src/org/jkiss/dbeaver/ui/navigator/database/DatabaseNavigatorContentProvider.java`：`getChildren` 先调用 `hasChildren`，需要初始化的 `DBNLazyNode` 交给 `TreeLoadVisualizer.expandChildren / TreeLoadService` 后台加载；已初始化节点走 `DBNUtils.getNodeChildrenFiltered`。加载失败收起对应节点并展示错误，不通过全局重新挂载来刷新。

`D/plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/navigator/DBNDatabaseNode.java`：`getChildren` 在 `childNodes` 为空时加载并缓存；`needsInitialization` 区分未加载和空结果；`refreshNode`、`clearNode` 处理结构缓存失效及子节点更新。可借鉴的是明确的“未加载 / 已加载为空 / 失效”状态，而不是直接复用 JDBC 或 Eclipse 节点。

`C/webapp/packages/core-navigation-tree/src/NodesManager/NavTreeResource.ts`：继承 `CachedMapResource`，键为节点路径，值为子节点 ID。`loadNodeChildren` 经 GraphQL `navNodeChildren(parentPath,offset,limit,withDetails)` 分页；`insertSlice` 合并分页并去重。`refreshTree / refreshNode` 先通知后端 `navRefreshNode`，再分别标记子树或节点过期；用户变化清空树和节点信息，项目与会话过期使树失效。它没有把一个永久缓存当成权限判断。

### SQL 语义补全与作用域

`D/plugins/org.jkiss.dbeaver.model.sql/src/org/jkiss/dbeaver/model/sql/semantics/SQLQueryModelRecognizer.java`：语法树识别 SQL，收集查询源、符号和表达式，构建语义模型；源码仍有部分递归 CTE / DML 的 TODO，不能称为所有 SQL 方言全覆盖。

同模块 `semantics/completion/SQLQueryCompletionContext.java`：`prepareCompletionContext / prepareProposal` 使用光标词法位置、nearest/deepest context 和可见源；`preparePrefixedColumnCompletions` 区分别名、完整对象名与字段，`getAliasesInUse` 从当前可见源获取别名。`SQLQueryCompletionAnalyzer.run → prepareContextfulCompletion` 汇总候选，并计算 replacementPosition / replacementLength，不只在全文用正则寻找 FROM。

CloudBeaver 的实际调用：

```text
plugin-sql-editor-new/SQLEditor/useSqlDialectAutocompletion.ts
  completionSource → data.getHintProposals(cursor, simple)
plugin-sql-editor/SqlEditor/useSqlEditor.ts
  300ms 节流 → 当前 executionContext + 当前编辑内容
plugin-sql-editor/SqlEditorService.ts
  getAutocomplete → querySqlCompletionProposals
core-sdk/queries/sql-editor/querySqlCompletionProposals.gql
  projectId + connectionId + contextId + query + position
server/.../service/sql/impl/WebServiceSQL.java
  getCompletionProposals → 活动语句提取
  → SQLQueryCompletionAnalyzer 或旧 SQLCompletionAnalyzer（按设置）
WebSQLCompletionContextScriptParser.obtainCompletionContext
  parseScript → SQLQueryModelRecognizer → 光标对应 scriptItem
WebSQLCompletionContext
  既有连接执行上下文、语法和方言
```

前端将服务返回的 replacementOffset 用于现有编辑器事务；不会为补全再建一套 SQL 连接。当前系统应继续使用 Monaco + dt-sql-parser + 授权 MCP 元数据，不移植 CodeMirror、GraphQL SDK 或 Eclipse 依赖。

### AI 上下文、数据库范围与编辑器交互

`D/plugins/org.jkiss.dbeaver.model.ai/src/org/jkiss/dbeaver/model/ai/engine/AIDatabaseContext.java` 的 Builder 要求执行上下文和明确 scope；CUSTOM 还要求实体列表，`getScopeObject` 按当前 Schema / Catalog / Connection 取得范围。

`D/plugins/org.jkiss.dbeaver.ui.ai/src/org/jkiss/dbeaver/ui/ai/controls/ScopeSelectorControl.java` 将范围选择写入上述上下文。不是把所有连接元数据无条件交给模型。

`D/plugins/org.jkiss.dbeaver.model.ai/src/org/jkiss/dbeaver/model/ai/impl/AIEngineRequestFactory.java` 的 `build` 预留回复及协议开销，计算系统上下文预算，调用 `AIDatabaseSnapshotService.createDbSnapshot`，最后由 `ChatTruncator` 约束消息历史。这里预算系数是该版本实现细节，不直接照搬成当前模型默认值。

`AIDatabaseSnapshotService` 按 scope 遍历对象或自定义实体，DDL 生成器提供结构描述，跳过系统、隐藏及导航不可见对象，按 token 边界停止；超限后降低描述细节。当前系统有更严格的字段授权与不可变快照，应保留这些边界，不能用“导航不可见”替代服务端鉴权。

`D/.../engine/openai/OpenAICompletionEngine.java` 的公开 `requestCompletion / requestCompletionStream` 将角色消息交给现有 OpenAI 服务并投影内容。所审查版本的这条公开链路不能证明存在当前商业 AI Chat 的完整动态工具循环。

`D/plugins/org.jkiss.dbeaver.ui.ai/src/org/jkiss/dbeaver/ui/ai/legacy/AILegacyTranslator.java`：从活动 SQLEditor 取得连接，弹窗选择范围，`translateUserInputIntoSql → AIDatabaseContext → AIAssistant.translateTextToSql`；`insertSqlCompletion` 通过 Document 修改当前语句或选择位置，并更新光标。该旧入口还有可配置的生成后执行分支；**本产品不移植该自动执行行为**，继续显式插入、差异审查和独立执行授权。

### 公开与商业功能边界

SQL 错误需要再区分编辑期诊断和执行期数据库错误。公开 `D/plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/semantics/SQLEditorSemanticMarkersManager.java` 监听语句引入、失效，合并调度 UI Job；`updateMarkers` 使用问题的 interval + 语句初始位置生成注解，每行只突出最严重问题，失效时删除对应标记。这是语义诊断投影，不是执行 SQL。当前 Monaco 本地语法标记与 Runtime SQLGlot 字段诊断同样应分开。

公开 `D/.../model/ai/commands/SQLCommandAI.java` 的 `handleCommand` 则取得既有执行上下文、范围和用户元数据传递确认，构造 `AICommandRequest` 后交给助手；返回 SQL 再经过解析与执行确认规则，转换为原脚本执行链。它不证明存在一个公开的通用“数据库错误→AI 自动重试”实现。本轮自然语言 SQL 解释仍走原无工具模型入口；不将数据库 EXPLAIN 执行计划功能与自然语言解释混为一谈。

CloudBeaver 固定版本的上述公开目录没有完整 AI Chat 前端与工具编排实现；不能把 DBeaver 公共 AI 模型接口等同于 CloudBeaver PRO Chat 源码。商业能力只引用官方公开文档：[AI Chat](https://dbeaver.com/docs/dbeaver/AI-chat/)、[AI tools & MCP](https://dbeaver.com/docs/dbeaver/AI-Tools-and-MCP/)。后者明确注明版本适用性及支持工具调用的供应商。文档访问时间为 2026-10-29，可能晚于所选源码版本；它们只证明公开行为，不证明 25.2.0 的内部实现。本轮不接触或复制商业代码。

## 2. 与现有系统的差异矩阵

| 能力 | 当前真实实现 | 分类与处理 |
| --- | --- | --- |
| 延迟目录、分页、缓存、刷新 | SqlSchemaExplorer + SqlMetadataSession；snapshot 检测、每次授权探测、64条投影缓存、过期请求 epoch | 已具备；保持，不另建缓存。空目录与未加载的状态需补充精确区分，避免每次重开空目录都重新查。 |
| 数据源级 AI 入口 | 助手上下文已支持数据源 / 全部授权库 / Schema，explore 不要求预选表 | 已具备后端；导航增加明确入口，沿用单一上下文，不自动提交任务。 |
| 动态元数据工具 | SqlStudioMetadataAssistant 的 SQL_METADATA_DISCOVERY；Runtime 调用 LIST_ASSETS / LIST_NAMESPACES / SEARCH / DESCRIBE，发现状态跨轮保存 | 已具备；保留 12次模型 / 24次工具预算，不让 Cline 直接 MCP。 |
| 表字段、别名、作用域补全 | sqlSchemaCompletion 使用 dt-sql-parser 的 caret entity + 授权 SEARCH/DESCRIBE | 已具备基础；WITH 语句目前为防止 CTE 冒充物理表，过度排除了全部未限定物理表。先复现，再做保守作用域修正；复杂派生列不假装已验证。 |
| 当前 SQL / 选中 SQL | 显式 opt-in，文件身份和版本检查，Monaco 既有插入事务 | 已具备；保持。未选择的所有文件不批量注入。 |
| 元数据探索 → 工程生成 | 两条链路各自已有快照；目前只有导航手动选表能给 Coding，候选证据卡不能直接复用 | 可用组件衔接；候选表明确点击后转入原 codingSelection，重新授权并核对快照。不能把模型文字直接变成授权。 |
| 关联依据 | MCP 提供 relationsStatus、JDBC_IMPORTED_KEYS；证据卡可逐表查看 | 已部分具备；加强身份核对和已确认外键展示，禁止把同名字段或 NOT_COLLECTED 当关系。 |
| Cline 证据追踪 | captureCoding → sqlCodingMetadata → CodingWorkspaceService inputSnapshotRef → SQLGlot schema | 已具备链路；补充 discovery/binding/tableRef 的来源标识，保持冻结快照及原 evidence 存储。 |
| SQLGlot 修复 | workspace_propose 返回真实 parse / field diagnostics，失败可继续提出修订；预算8次模型、16次工具、执行次数0 | 已具备；增加真实解析错误→修复成功→持久化证据回归，不新增修复 Agent。 |
| 数据库执行错误 | 当前工程生成禁止执行业务 SQL，模板测试走原执行网关及白名单 | 不绕过限制；实际数据库错误自动修复不作为本轮已实现功能。 |
| DBeaver 直接数据库/DDL遍历 | JDBC、Eclipse、同步执行上下文 | 不适合移植；保留 Runtime/MCP 授权供给。 |
| 商业 Chat / 工具循环 | 非公开完整源码 | 仅行为参考；不推断、不复制，不称为已移植。 |

## 3. 本轮最小实施方案（审查后执行）

1. SqlSchemaExplorer 增加源级探索入口，分离未加载与空目录，重开空目录不重新抓取；刷新、切源、权限变化恢复未加载状态。
2. 基于实际 parser 输出复现 WITH / 子查询 / 多语句 / 别名遮蔽，修正可证实的物理表补全缺口；不增加第二个 SQL parser。
3. SqlMetadataEvidence 的“引用到工程 SQL”仅交付精确候选标识；SchemaExplorer 复用既有选表和授权机制核对后加入 codingSelection。组件只传标识，Java prepare 再取结构并冻结。
4. captureCoding 的表级证据保留 discoveryId、bindingId、tableRef 和 snapshotId；原 inputSnapshotRef 的哈希继续覆盖完整交付，不新增数据库结构。
5. 增加快照失效、跨上下文证据拒绝、真实 SQLGlot 修复回执回归；在真实页面验证源级入口、元数据补全、证据转交和既有 SQL 生成。线上模型无法稳定触发某次错误时，用真实解析器的受控失败来证明修复回路，明确不冒充真实模型的随机失败。

所有更改保持 Java Runtime 授权、数据供给和治理，模型选择元数据，Cline 仅离线工程生成，SQLGlot 仅解析且不执行 SQL。生产不随研究自动发布。实现与结果追加到本报告后续章节。

## 4. 许可证结论

21个审查文件中20个文件具有 Apache-2.0 文件头；GraphQL 查询无独立文件头，依据该仓库根 LICENSE 记录。两个根许可证均已逐一读取并哈希，见清单。研究只引用类、方法、调用关系、文件链接和哈希；没有将第三方源码、第三方注释或商业实现复制进产品，因此没有新增源文件的第三方归属声明。若后续决定逐行移植，应重新核对对应文件及 NOTICE 并保留声明，不能把本次研究结论当作未来复制的豁免。

## 5. 实际实施结果

### 导航和编辑器

源级“AI 探索”入口打开现有助手并选择 explore 模式，只在输入为空时提供建议，不提交任务，不重建编辑器，也不要求选择表。原工程、数据库上下文和用户输入保持原状态。

目录增加 `tablesLoaded`，明确区分“未加载”和“已加载为空”；重开空目录不重复 SEARCH，显式刷新、切源、切命名空间、权限／快照失效仍清除该标志。没有新缓存。

补全继续使用已有 dt-sql-parser。读取现有语法树的 CTE 声明，使用其词法分号确定语句范围。实际回归发现 MySQL / Hive 的 collector 部分 rootStmt 从 WITH 之后开始，旧的 WITH 范围保护并不一致；修复后同名 CTE 不再借用物理表字段，其他真实表也不再被整个 WITH 一概排除。嵌套声明按保守方式处理：允许少给候选，不允许把派生列冒充平台注册字段。五种方言覆盖 CTE 同名遮蔽、其他语句隔离、最近作用域的同名别名、未限定表歧义和自关联。

### 探索证据交付工程 SQL

```text
用户点击候选表“引用到工程 SQL”
  SqlMetadataEvidence → SqlAssistantPanel → SqlStudioView.referenceMetadata
  → SqlSchemaExplorer.referenceEvidence
    → 现有 metadata context GET：校验项目所有权和当前授权
    → 核对当前 service / asset / namespace 与候选的真实绑定
    → 现有 metadata operation DESCRIBE：精确 expectedSnapshotId
    → 核对 tableRef、物理身份、可见字段
    → 加入原 selectedTables / codingSelection（16表、8源上限）
用户输入工程需求并发送
  SqlStudioService.prepare → SqlStudioMetadataService.captureCoding
  → 原 MCP 网关再次授权并抓取选中结构
  → sqlCodingMetadata → 原 CodingWorkspaceService 冻结 inputSnapshotRef
  → Cline 仅 workspace_read / workspace_propose，无 MCP / 数据库网络授权
  → Java SQLGlot 校验 schema → 失败回执可修复 → 原差异审查
```

不把模型给出的表名当成权限；未引用的表不会因为出现在模型文字或历史回答里自动进入工程输入。切源时尚未完成的引用通过现有 serial 丢弃，错误快照、错误物理身份和跨源引用被拒绝。普通侧栏可看到已引用数量并查看／移除。

候选结构展开也补充物理身份核对。关联列表只展示 `source=JDBC_IMPORTED_KEYS` 的真实回执，并包含 referencedSchema；同名字段、模型建议和 NOT_COLLECTED 均不提升为外键事实。本次现场表没有可展示的已采集外键，页面准确展示这一局限，没有为截图虚构关联线。

### 兼容扩展

只有捕获的表快照增加来源标识 `discoveryId / bindingId / tableRef`，既有接口路径和选表请求结构不变。运行诊断面板展示当前 generation 实际冻结的标识、snapshotId 与可见字段数量，恢复页面不再请求模型。

源码审查同时发现直接增加来源字段会使旧任务的冻结快照与重新抓取结果不相等。`sameCodingSnapshot` 仅对旧快照未记录的三个新增来源字段做投影兼容；原字段、列列表、快照及新任务已保存的来源标识仍完整比较。每次比较之前仍通过原 MCP 重新授权，不能将兼容逻辑用于跳过授权或接受已变化的列。没有迁移、改写历史上下文或升级旧任务的冻结输入。

SQLGlot 和 Cline 修复算法没有重写。新增回归使用真实 SQLGlot Python 子进程及 H2 中的原持久化 journal / evidence：`missing_field` 被拒绝，旧失败操作不能完成任务，修正为快照内字段后完成；原失败回执仍可读取，inputSnapshotRef 不变，代码执行次数为0。该测试不使用 live 模型，证据清楚标明这一点。

### 本轮文件清单

| 文件（相对项目根目录） | 本轮职责 |
| --- | --- |
| `chatchat-api/web-app/src/js/editor/sqlSchemaCompletion.js`、`.test.js` | CTE 声明、语句范围和作用域安全回归。 |
| `chatchat-api/web-app/src/components/sql-studio/SqlSchemaExplorer.vue`、`.test.js`、`src/js/components/sql-studio/SqlSchemaExplorer.js` | 源级入口、空目录状态、证据引用与原选表状态衔接。 |
| `chatchat-api/web-app/src/components/sql-studio/SqlMetadataEvidence.vue`、`.test.js`、`src/js/components/sql-studio/SqlMetadataEvidence.js` | 明确引用动作、身份校验、真实关联依据。 |
| `chatchat-api/web-app/src/components/sql-studio/SqlAssistantPanel.vue`、`src/js/components/sql-studio/SqlAssistantPanel.js` | 转发引用事件，保持三个布局区域。 |
| `chatchat-api/web-app/src/views/SqlStudioView.vue`、`src/js/views/SqlStudioView.js`、`.test.js` | 沿用当前上下文打开探索、引用成功后切工程模式，不自动生成或执行。 |
| `chatchat-api/web-app/src/components/sql-studio/SqlBottomPanel.vue`、`.test.js` | 冻结元数据版本的技术诊断展示。 |
| `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioMetadataService.java`、对应测试 | 来源标识捕获和旧冻结投影兼容。 |
| `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioService.java` | 恢复／读取时使用兼容比较，保持先授权后比较。 |
| `chatchat-api/src/test/java/com/chatchat/api/runtime/coding/workspace/CodingWorkspaceServiceTest.java` | 真实 SQLGlot 字段失败、修复及持久化回执。 |
| `scripts/verify-sql-assistant-workbench-browser.cjs` | 本轮真实补全、证据转交、Cline、只读恢复验收分支。 |
| `scripts/verify-sql-upstream-sources.py` | 固定源码、符号与逐文件许可证验证。 |
| `scripts/collect-sql-benchmark-evidence.py`、本报告及 `27-dbeaver-evidence/` | 聚合通过的回归、业务证据与研究索引。 |

未修改 Coding Agent Node Runner、MCP 数据库连接、模板发布链路、SQL 执行网关及数据库 Schema；上一轮未提交的文件清单需结合26号报告区分，不将整个工作区差异都视作本轮新增。

## 6. 测试与真实案例

结果索引：[verification-summary.json](27-dbeaver-evidence/verification-summary.json)。

| 验证 | 结果 |
| --- | --- |
| 固定源码和许可证 | 2个固定提交、21个源码文件，哈希和符号验证通过。 |
| 前端单元／组件 | 21个文件，157项通过；包含原126项回归。 |
| Java API 与模型适配器 | 聚合最终 JUnit 回执111项通过；包含原109项回归。新增测试校正了无关的集合排序和 JSON 数字类型断言后，针对失败测试重新执行通过，没有修改运行逻辑迁就断言。 |
| MCP 元数据权限 | SqlMetadataDiscoveryWorkflowTest 12项通过，含字段／表屏蔽、外键可见性、快照与禁止执行边界。 |
| Vue 结构与正式构建 | 构建成功，已有大 chunk 警告仍存在。 |
| 真实编辑器补全 | MySQL 授权源 `db_query_mysql_221_dev / brho`，WITH + 别名返回操作日志表7个真实可见字段。 |
| 探索 → 工程生成 | `5a4bbaf4-e618-4d26-8245-236c182a9f62`，真实配置模型和 Cline，SUCCESS；一份 `publication.sql` 提案，`PARSED; FIELDS_CHECKED; SQL_NOT_EXECUTED`。 |
| 恢复来源证据 | 同一 generation 页面重开后显示相同 discovery / binding / snapshot / tableRef，新模型请求0次。 |
| 真实 SQLGlot 修复回归 | 缺失字段失败 → 同一冻结 schema 下修复成功 → COMPLETED，原失败回执保留，未执行 SQL。 |
| 交互稳定性 | 100% / 125% / 150% 等效视口与DPR；折叠轨道、极限拖拽、Monaco实例、草稿、光标和滚动锚点检查通过。 |

此次业务场景是“基于已确认操作日志结构，生成 ID、modl 的只读预览 SQL”，只验证开发与证据链路，不冒充已执行数据库查询或已验证业务统计口径。探索证据来自已有真实任务 `90e7d4cb-c887-44ef-82d3-cf47daa1a099`，恢复后通过现有接口重新核查权限与结构。

证据：

- [真实作用域补全截图](27-dbeaver-evidence/real-scope-completion.png)。
- [真实字段及关系局限](27-dbeaver-evidence/real-fields-and-relations.png)、[读取回执](27-dbeaver-evidence/metadata-detail-proof.json)。
- [真实 Cline 工程提案](27-dbeaver-evidence/real-evidence-bound-coding.png)、[浏览器请求与验证记录](27-dbeaver-evidence/business-browser-proof.json)。
- [恢复后的工程元数据诊断](27-dbeaver-evidence/restored-coding-evidence.png)、[恢复记录](27-dbeaver-evidence/restore-proof.json)。
- [真实解析器失败与修复回执](27-dbeaver-evidence/real-sqlglot-repair.json)、[稳定性记录](27-dbeaver-evidence/stability-browser-proof.json)。

本轮验收脚本第一次把会话中另一个历史回答的首个候选当作当前卡片，导致测试需求与所引用表不一致；那次不作为正向验收。修正脚本按实际可见表匹配后重新通过，正式证据仅收录通过的正向场景。没有调整权限、放宽 SQLGlot 或使用模拟接口通过验收。

复现：先运行26号报告中的前端和 Java 命令，本轮 Java 测试额外包含 SqlStudioMetadataServiceTest 与 CodingWorkspaceServiceTest；设置 `-Dsql.benchmarkEvidenceDirectory=<本目录绝对路径>` 可导出受控真实解析回执。额外 MCP 回归：

```powershell
mvn.cmd -o -Pbackend-only -pl chatchat-mcp-server -am test `
  '-Dcontainer.image=true' '-Dfrontend.skip=true' '-Dfrontend.install.skip=true' `
  '-Dtest=SqlMetadataDiscoveryWorkflowTest' '-Dsurefire.failIfNoSpecifiedTests=false'
python scripts/verify-sql-upstream-sources.py
python scripts/collect-sql-benchmark-evidence.py
```

真实页面脚本使用受保护的正常登录 session 和授权受控工程，沿用 `SQL_UI_AUTH_FILE / SQL_UI_STATE_FILE / SQL_UI_URL / SQL_UI_EVIDENCE`。`SQL_UI_BENCHMARK=1` 会生成真实提案并消耗模型预算；`SQL_UI_BENCHMARK_RESTORE=1` 配合 `SQL_UI_GENERATION` 只读恢复指定验收任务，`SQL_UI_METADATA_EVIDENCE=1` 只读检查原操作日志证据。禁止对任意客户工程盲目运行。完整性能 Trace 保留在 target，报告记录其哈希，避免将大体积内部报文纳入仓库。

## 7. 未实现、风险与回退

1. 没有实现完整递归 CTE、派生表达式列的语义补全；复杂或未完成 WITH 继续保守少给候选。Oracle / SQL Server 仍受原 GenericSQL 语法范围约束，不宣称与 DBeaver 方言覆盖相同。
2. 真实联机仅验证现场 MySQL 元数据与已配置 OpenAI 兼容模型。其他四种语法属于 parser 回归，其他供应商属于原客户端回归，不是四个数据库的联机验收。
3. 现场操作日志表没有可展示的已采集外键。JDBC 外键展示与屏蔽有组件及 MCP 自动化覆盖，不能据此称为现场多表业务关联分析已验收；统计关系和业务口径仍需真实证据。
4. SQLGlot 错误→修复的正式证据为受控真实解析器与原持久化服务回归，不将它冒充 live 模型必然重现的自动修复场景。生成结果保持用户差异审查；不自动落盘、自动执行业务 SQL或默默更换统计口径。
5. 没有复制商业 Chat、自动执行或权限模型，也没有新建 Agent、MCP直连、数据源连接池和元数据缓存。数据库执行错误自动诊断、执行计划和模板测试白名单问题仍属于原治理链路，未借本轮放宽。
6. 三种缩放为等效视口／DPR仿真，原生浏览器工具栏缩放与特定GPU的现场复核仍待完成。冷启动 Monaco 的长任务和构建体积警告仍存在。
7. 仅更新现场隔离预览 API / Node 与本地真实构建页面，生产未替换、未提交 Git。隔离镜像及上一版本记录见 [preview-deployment.json](27-dbeaver-evidence/preview-deployment.json)。回退时恢复该记录的 previousImage 与对应隔离 Node，并使用上一版静态资源；新增字段是读取兼容的快照元信息，没有 DDL、业务数据及索引回退操作。正式发布仍应完整打包并核对源码与静态资源版本。
