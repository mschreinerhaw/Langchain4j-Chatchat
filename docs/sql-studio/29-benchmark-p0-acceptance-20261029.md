# AI SQL Studio 对标复核与 P0 整改

结论：此前任务为**部分完成**，不是尚未开始。已有源码研究、Vue 工作台、元数据证据与 Cline 自主探索实现；真实客户的指标口径、业务粒度及完整血缘尚未完成业务验收。本轮在核对实际 Git Diff、上游源码及测试后继续修改代码，没有部署生产环境。

## Git 与上游源码核对

- 起点分支 `fix/studio-isolation-metadata-20261029`，HEAD `2dad077c0`，起点工作区干净。
- 已执行 `git fetch --all --prune`。远端 `origin/main` 为 `221efb954`，已包含在起点 HEAD；保留本地其后的五个提交，未用远端覆盖。
- 工作分支 `fix/sql-studio-benchmark-p0-20261029`。相关分支 `origin/feature/sql-studio-mvp` 为 `375a15713`，`origin/fix/sql-studio-composer-layout` 为 `0400bacf4`；均检查实际提交和文件差异。
- 本次源码与测试提交：`08c4f8c11`（26 个文件，544 行新增、49 行删除）。逐文件路径及 Git blob SHA-256 均保存在本轮 `verification.json` 的 `sourceFiles` 中；报告、复核清单、测试摘要和截图另行提交，避免把报告存在本身当作实现依据。
- 主要既有实现提交：`c9e70d232`（统一工作台）、`06ef716d4`（源码对标及证据）、`221efb954`（自主探索）、`a68191cc5` / `8d822a8a8` / `4815886b7`（预算、上下文、检索与权限修正）。`2dad077c0` 主要保存验收记录，不能单独用它证明功能实现。
- 重新读取 DBeaver `bd2f6f8a3c350d4f9bb914879bba791c27039a45` 和 CloudBeaver `5e30bbdc77b92c4b2276cf705d57ac42f0fa3684` 的固定 Git blob。原 13 + 8 个源码文件的 SHA-256、关键符号和逐文件许可证与既有清单完全一致；另外补查 `DBSEntity`、`DBSEntityAssociation`、`DBSEntityConstraint`，本轮共核验 24 个文件。其属性、约束、关联分离的接口进一步支持本轮“完整结构证据与业务推断分开”的实现。脚本同时修复了 Windows 长对象路径导致 `git show revision:path` 失败的问题，改为先解析对象 ID，再读取 blob。
- 原始证据：[源码及许可证清单](27-dbeaver-evidence/upstream-sources.json)、[逐文件符号索引](27-dbeaver-evidence/source-index.md)。本轮复核摘要和测试证据见 [本轮证据](29-p0-evidence/verification.json)。

## 原任务状态矩阵

下表为本轮开始时的状态；测试依据包含本轮重跑结果，不因文档存在而判定实现完成。

| 检查项 | 状态 | 实际代码与提交 | 核查及剩余问题 |
| --- | --- | --- | --- |
| DBeaver 导航、对象模型与 SQL 编辑器研究 | 部分完成 | 上游 `DBNDatabaseNode`、`DatabaseNavigatorContentProvider`、`SQLQueryModelRecognizer`、`SQLQueryCompletionContext/Analyzer`；`06ef716d4` | 原导航/语义研究的固定 blob、符号及哈希重核通过；实体约束/关联模型补充研究在本轮新增 3 个固定源码文件中完成。当前实现仍非完整桌面 IDE 等价物。 |
| CloudBeaver Web 工作台和导航研究 | 已完成且验证通过 | 上游 `NavTreeResource.ts`、`useSqlEditor.ts`、`useSqlDialectAutocompletion.ts`、`WebServiceSQL.java`；`c9e70d232` / `06ef716d4` | 确认按需加载、目录状态及编辑器服务边界；Vue 测试验证折叠、空目录缓存、分页和焦点保留。 |
| AI 上下文、结构理解及生成机制分析 | 部分完成 | 上游 `AIDatabaseContext`、`AIEngineRequestFactory`、`AIDatabaseSnapshotService`；本地 `SqlWorkspaceMetadataTools.java`、`coding-agent-node/src/runner.js`；`06ef716d4` / `221efb954` | 当前代码已超出旧报告的静态选表方案，支持模型自主发现；业务粒度、分区和 JOIN 风险证据还不完整。 |
| 源码级功能差异对比 | 已完成且验证通过 | [原对标报告](27-dbeaver-source-benchmark-20261029.md)与对应提交 Diff；本地 `sqlSchemaCompletion.js`、`SqlSchemaExplorer.js`、`SqlStudioView.js` | 对照当前代码重新确认：默认折叠与懒加载已实现，不能重复算本轮新增；既有浏览器脚本仍使用旧页签，需要更新并重跑。 |
| 复用方案与许可证边界 | 已完成且验证通过 | 固定的 21 个 Apache-2.0 源文件；`scripts/verify-sql-upstream-sources.py`；`06ef716d4` | 仅研究公开实现及交互原则，保留 Vue 3；未复制 DBeaver PRO 专有代码，未引入 CloudBeaver React 工作台。公开源码不能证明 PRO 内部实现。 |
| 现有 SQL Studio 对应修改 | 部分完成 | `SqlSchemaExplorer.vue/.js`、`sqlSchemaCompletion.js`、`SqlStudioMetadataService.java`、`SqlWorkspaceMetadataTools.java`、`CodingWorkspaceService.java`；上述实现提交 | 已实现自主发现、冻结证据、Diff、权限与修复。LINEAGE 缓存操作标识、结构证据投影和静态结构风险尚有缺口，本轮补齐可验证部分。 |
| 自动化测试及真实业务验收 | 部分完成 | `SqlStudioIntegrationTest`、`CodingWorkspaceServiceTest`、`SqlMetadataDiscoveryWorkflowTest`、前端 SQL 套件；[此前真实客户验收](../cline-sql-metadata-20261029-evidence/customer-acceptance.json) | 自动化与此前真实 Cline 调用有证据；完整指标公式、币种/期间口径、实时性和关联业务语义未确认，不能判定全面业务通过。本轮不把历史记录当成当前源码实时实库验收。 |

## 本次实际代码修改

| P0 | 修改结果 | 文件 |
| --- | --- | --- |
| 01 自主元数据探索 | 保留模型决定查询顺序、无需预选表的现有链路；修复 DESCRIBE / LINEAGE 共用证据时错误返回缓存操作名和血缘状态。复用仍重新检查绑定、权限和快照。 | `SqlWorkspaceMetadataTools.java`及测试 |
| 02 表关系与业务结构 | 投影完整可见的声明主键、分区键和明确的未知状态。隐藏任一复合键成员即隐藏整组，不泄露字段名。列采集达到旧 50000 上限时不认证主键完整性；Inceptor 的 unique_constraint 标记不冒充主键。只在有可信来源时认证，不根据 id/date 名称猜测。 | MCP `MetadataColumn.java`、`MetadataIndexService.java`、`SqlMetadataDiscoveryService.java`及测试 |
| 03 Runtime → Cline | 冻结选表路径也交付新增结构证据；自主发现路径原有结构化回执直接保留。Node 模型视图保留主键、外键、分区、注释、类型、绑定和快照，仅去除重复的传输契约与相同证据标签；后端完整审计不变。 | `SqlStudioMetadataService.java`、`coding-agent-node/src/runner.js`及测试 |
| 04 校验和修复 | 真实 SQLGlot 新增完整复合外键匹配、等值条件仅属推断、聚合 JOIN 放大风险、生产者声明分区键过滤检查。结构风险作为 `structuralDiagnostics` 返回 Cline；语法/字段有效与业务正确性分开，`businessValidation=NOT_EVALUATED`。原有不存在字段失败→修复→持久化回执链路重跑。 | `sql-toolchain/validate.py`、`SqlGlotToolchainTest`、`CodingWorkspaceServiceTest`、Python 结构测试 |
| 05 工作台 | Validation 面板用业务提示展示关联、粒度和重复计数风险，明确数据库未执行；进度按服务器序号处理事件回放，避免旧事件把新阶段覆盖；修复高级设置原生弹窗遮挡知识库文档选择的实际缺陷，打开选择层前关闭原弹窗；更新已落后于统一工作台的浏览器脚本。既有懒加载、编辑器保活及 Diff 保留。 | `SqlBottomPanel.vue/.js`、`SqlAssistantPanel.vue`、`SqlStudioView.vue`、`sqlTaskPresentation.js`及测试、`web-app/scripts/test-sql-studio.mjs` |
| 06 性能 | 压缩模型重复上下文；审计摘要增加真实复用次数及请求操作。分区数组按表解析复用，缺失列只探测一次，不在每个字段上反复抛异常。增加可复跑的离线体积比较脚本。 | `runner.js`、`CodingWorkspaceService.java`、`MetadataIndexService.java`、`benchmark-sql-metadata-context.mjs` |

SQLGlot 不会执行 SQL。`DECLARED_FOREIGN_KEY` 表示与当前可见登记结构匹配，不表示实时读取过数据库 DDL，也不证明业务粒度正确。静态警告允许模型进一步发现、修改，或明确交付待确认事项；不能因缺少业务定义就自行补造财务公式。

## 工具与证据调用链

`SQL Studio 当前工程/用户/数据源绑定 → Runtime 冻结输入与预算 → Cline workspace_read → 模型自主 workspace_metadata / workspace_validate → Runtime 身份及绑定校验 → 既有 MCP 元数据目录 → 结构化证据缓存及回执 → Cline 修改 SQL → workspace_propose 再校验 → 结果及父证据 → 用户审查 Diff → 版本校验后保存`。

Java 不决定候选表或查询顺序。Cline 没有 MCP、业务库、Shell 或 SQL 执行连接。未发现对象返回 `METADATA_REQUIRED / UNDETERMINED`；无权限、快照变化和服务不可用分别反馈。保存继续使用既有工程权限和文件版本校验，本轮没有覆盖用户 SQL 文件。

## 验证与客户案例

测试明细、固定源码哈希、代码提交标识见 [verification.json](29-p0-evidence/verification.json)。

- Java：SQL 相关广泛回归 245 项，其中 243 通过、2 个需额外 Node 集成环境的测试按原条件跳过，0 失败。最后修改另外重跑结构投影、工具、真实解析器和 Runtime 证据链，结果见证据摘要。
- 后续显式启用 `coding.nodeE2e=true`，跨进程 Java → 实际 Node/Cline SDK → 签名 HTTP 回调 → H2/SQLGlot 的 SQL/Python、普通/快速路径共 4 项通过；真实 Provider 对比模板 1 项因未提供真实模型配置而跳过。最终元数据投影与索引服务 22 项通过，结构/工具/解析器/Runtime 链路另有 44 项通过；这些与广泛回归存在重叠，不累计成独立测试总数。
- 前端：22 个 SQL 测试文件、165 项通过。高并发运行时补全测试出现冷加载超时，未改断言；最终在其它重构建结束后以 2 个 worker 整体复核。
- Node：128 项通过，包含现有 SDK 生命周期及模型视图语义保留检查。
- Python：8 项原引用测试与 10 项新结构测试；使用真正 SQLGlot 30.23.0，业务元数据为明确模拟夹具。
- 浏览器使用本地真实 Vue / Monaco / Chromium，HTTP 为模拟接口，不冒充实库或真实模型；最终结果及截图见证据摘要。
- 正式前端构建结果见证据摘要；本轮未发布构建包。

复跑入口：Maven 选择 `Sql*Test,CodingWorkspace*Test`，启用 `coding.sqlglotPython` 并使用 `frontend.skip=true` / `frontend.install.skip=true`；跨进程测试另加 `coding.nodeE2e=true`。前端运行 `npm run test:sql-studio -- --maxWorkers=2`、`npm run test:sql-studio:browser` 和 `npm run build`；Node 运行 `npm test`；Python 使用 unittest discover 选择 `scripts/tests/test_studio_sql_*.py`。实际 SQLGlot 解释器须安装锁定版本，嵌入式 Python 还须把测试目录加入 sys.path。不要把并行冷启动造成的补全测试超时隐藏成通过。

固定需求仍是“查询客户 070200046604 的交易、资产和盈亏情况，生成 SQL 并解释表与关联”。本轮从此前真实任务 `sql-coding-7298b64c-569a-4877-b228-fc921f3b32b8` 的十张表元数据回执离线重放原 SQL，当前解析器语法和字段校验通过，未执行数据库 SQL；详见 [离线回放结果](29-p0-evidence/customer-replay.json)。该回放没有实时重查授权或快照，没有调用新模型，不能替代当前代码的实时业务验收。

| 验收要求 | 本轮依据与边界 |
| --- | --- |
| 无须人工选表、自主查找相关字段 | 当前 `CodingWorkspaceServiceTest.clineCanDiscoverThenRepairCustomerSqlWithoutAnyPreselectedTable` 使用模拟元数据、真实 H2 证据库和 SQLGlot；此前真实客户记录无预选表。 |
| 关联路径、业务粒度 | 新测试验证完整外键与推断区分、复合键、跨绑定隔离、金额重复计数和缺分区过滤。客户实据未采集外键，仍明确未确认，不虚构 JOIN。 |
| 完整上下文交给 Cline | Runtime 回执、冻结结构投影与 Node 模型视图测试验证字段、关系、方言及结构证据保留；既有 workspace_read 交付请求和已有文件。 |
| SQL 文件、真实静态校验与错误修复 | Runtime/H2/真实 SQLGlot 集成测试覆盖缺元数据→发现→不存在字段失败→同任务修复→结果回执；不是模拟 parser 成功。 |
| SQL、解释、证据、Diff、未执行标记 | 前端组件与浏览器检查，具体范围见最终证据。SQL 执行入口及权限没有放宽。 |
| 工程、权限、历史 | 现有 SQL 集成、用户文档、模板、引用版本、恢复和拒绝测试重跑；不等同完整系统所有模块验收。 |

## 性能依据

没有找到可唯一对应“157 秒”的完整原始 trace；现有可核验的接近样本是此前盈亏定义任务 **152.95 秒**。其 10 次模型调用累计 109596 ms，17 次元数据调用累计 19113 ms，剩余约 24241 ms 为未进一步归因的授权/回调/排队/持久化等开销，不能全算作 MCP 慢或模型纯推理。模型耗时包含服务端等待及生成，不具备足够证据再拆成 TTFT 与推理时间。另一完整客户任务为 348.64 秒，模型累计 266353 ms，元数据累计 30148 ms。

在相同 27 次真实物理元数据回执上，完整 Runtime 回执 221615 bytes；修改前模型视图 166792 bytes，当前 142170 bytes，**增量减少 14.76%**，相对完整回执共减少 35.85%。后端原回执不变，权限和快照检查没有减少。详见 [体积比较](29-p0-evidence/context-performance.json)。这是相同输入的离线比较，没有新调用模型，不能声称 157 秒已降低到某个数值。

## 剩余问题及下一步

1. 当前代码没有重新进行真实模型 + 实时元数据服务的端到端验收；此前临时业务验收身份已停用，本轮没有重新创建生产身份或部署。需要在隔离验收环境以真实业务身份重跑固定需求，再测耗时与修复质量。
2. 客户数据源尚缺已确认业务关联、财务公式、币种/期间定义及完整血缘。应通过现有元数据管理能力补录；静态解析器不能替业务方确认这些口径。
3. 主键认证目前仅使用已有 MySQL PRI 证据；Oracle 等没有明确来源时保持 NOT_COLLECTED。分区证据复用现有可返回 partition_keys 的采集接口；没有采集到不代表表未分区。派生表、CTE 预聚合的唯一性传播尚未实现，相关关联保守标为未知。
4. 结构投影增加字段会正常改变快照版本。旧冻结任务应按既有冲突流程刷新，不绕过版本比较，也不伪造兼容的快照 ID。
5. 性能下一步应在同模型、同授权和相同元数据版本下做多次端到端对比，补全授权、回调与等待的时间段，再决定是否需要调整探索预算；不以减少授权检查换速度。生产发布应重新执行前端构建并打包 API/worker/MCP，本轮提交源码和本地构建验证，不提交本机生成的无关静态资源变动，也不部署。
