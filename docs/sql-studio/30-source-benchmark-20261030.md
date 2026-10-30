# SQL Studio P0：固定源码对标复核

## 审查边界与证据

2026-10-30 对照公开仓库真实源码复核，并补查连接、执行和错误传播。本次不复制第三方源码，不以商业产品介绍替代源码依据。

| 仓库 | 固定版本 | Commit |
| --- | --- | --- |
| [DBeaver](https://github.com/dbeaver/dbeaver) | 25.2.0 | `bd2f6f8a3c350d4f9bb914879bba791c27039a45` |
| [CloudBeaver](https://github.com/dbeaver/cloudbeaver) | 25.2.0 | `5e30bbdc77b92c4b2276cf705d57ac42f0fa3684` |

重新读取并核对 29 个固定 Git blob、关键方法、文件 SHA-256 和许可证。完整文件路径、不可变 GitHub 链接、符号行号见 [源码索引](30-p0-evidence/source-index.md) 与 [机器可读清单](30-p0-evidence/upstream-sources.json)。复核命令：

```powershell
python scripts/verify-sql-upstream-sources.py --source-root <已检出的上游仓库父目录> --output docs/sql-studio/30-p0-evidence/upstream-sources.json
```

`D/`、`C/` 下文分别代表两个固定提交。更早的导航、补全、证据卡研究见 [27 号报告](27-dbeaver-source-benchmark-20261029.md)；本轮没有把旧报告的存在当作新功能验收。

## 源码调用与可复用设计

| 关注点 | 实际源码与调用关系 | 本工程差距和处理 |
| --- | --- | --- |
| 连接与执行上下文 | `D/plugins/org.jkiss.dbeaver.model.jdbc/src/org/jkiss/dbeaver/model/impl/jdbc/JDBCDataSource.java` 的 `openConnection`；`C/server/bundles/io.cloudbeaver.server/src/io/cloudbeaver/service/sql/WebSQLProcessor.java` 的 `createContext` 创建含 Catalog/Schema 的 `WebSQLContextInfo` | 当前绑定持久化于 `ds_sql_metadata_context`，服务/资产/用户均由后端固定。Oracle 源方言与 Hive 目标方言不是同一上下文，UI 应明确显示两者，不能依据源 Oracle 把目标 SQL 当成 Oracle SQL 校验或执行。 |
| 分层发现与缓存 | `DatabaseNavigatorContentProvider.getChildren` → 后台加载 → `DBNDatabaseNode.getChildren/refreshNode`；`C/webapp/packages/core-navigation-tree/src/NodesManager/NavTreeResource.ts` 的 `loadNodeChildren/insertSlice/markTreeOutdated` 维护分页与失效 | 保留既有 MCP 元数据索引和任务级快照，补齐 Catalog、Schema、表、字段搜索和显式刷新入口；刷新仍受现有资产授权约束，不把旧缓存当作永久权限。 |
| 编辑器绑定 | `C/.../plugin-sql-editor/SqlEditor/useSqlEditor.ts` → `SqlEditorService.getAutocomplete` → `querySqlCompletionProposals.gql` → `WebServiceSQL.getCompletionProposals` → `WebSQLCompletionContextScriptParser` | SQL Studio 的 Monaco、当前文件、源绑定及目标方言已有组件；本轮修复前置校验与解析器使用不同名称规范的问题，不另建编辑器或执行上下文。 |
| 解析与语义 | `D/.../model/sql/semantics/SQLQueryModelRecognizer.java` 使用 ANTLR4 Token/Interval 和语义作用域；`SQLQueryCompletionAnalyzer/Context` 处理可见源、别名和替换位置 | 本工程复用锁定 SQLGlot 30.23.0 的方言和作用域；修复 `MappingSchema.find` 之前缺失名称规范化。保留字段检查和引号语义，不能靠忽略元数据错误放行。 |
| JSQLParser 的实际用途 | `D/plugins/org.jkiss.dbeaver.ext.oracle/src/org/jkiss/dbeaver/ext/oracle/data/QueryTransformerRowNum.java` 导入 `net.sf.jsqlparser`，`transformQueryString` 用于 Oracle 查询变换 | JSQLParser 和 ANTLR4 在同一仓库中存在，并不代表所有 SQL 处理都串行通过两者。无需为对标而把两个解析器再引入本工程。 |
| 执行与数据库错误 | `D/.../ui/editors/sql/execute/SQLQueryJob.java` 的 `executeStatement` 调用 `DBCStatement.executeStatement`，失败经 `SQLQueryResult.setError` 传播；`C/.../WebSQLProcessor.java` 处理执行上下文和异常，`WebSQLQueryResults` 分离结果集和更新计数 | 静态检查、数据库执行、结果集和业务结论必须分开。本用户已明确 Oracle 只提供元数据、目标为 Hive；本任务执行阶段应记录 SKIPPED 和原因，不能向 Oracle 执行 Hive SQL，也不能用未执行伪装执行成功。 |
| AI 数据库上下文 | `D/.../model/ai/engine/AIDatabaseContext.java` 绑定执行上下文及 scope；`AIEngineRequestFactory.build` → `AIDatabaseSnapshotService.createDbSnapshot`，按可见范围和上下文预算提供结构 | 本工程采用可查询的授权元数据工具和版本化快照，不扩大一次性 Prompt。真实失败记录证明工具已被调用；缺陷在后续查找，不是仅仅“未向模型提供更多表”。 |
| 约束和关联 | `DBSEntity`、`DBSEntityConstraint`、`DBSEntityAssociation` 将字段、键和关联分开 | 本轮交付保留 JDBC 外键来源、完整结构、版本和字段引用。SQL 等值连接推断仍不冒充真实外键；缺失主键、分区或业务粒度继续明确为未知。 |

## 社区、公开源码和商业功能

本次审查的导航、连接、SQL 编辑、执行和 AI 基础接口来自上述公开固定提交，逐文件许可证依据在清单中。公开 AI 接口的存在不等于每个社区安装包都包含所有商业 AI 功能。

[DBeaver 官方 AI Chat 文档](https://dbeaver.com/docs/dbeaver/AI-chat/) 仅作为产品行为参考；它是移动文档，访问日期为 2026-10-30，不用来证明固定 25.2.0 的内部实现。CloudBeaver 复用 DBeaver 后端数据库能力可以从 `WebServiceSQL` 等实际导入和调用确认；本次没有读取商业 CloudBeaver/PRO 私有 AI 编排源码，不能声称复刻了商业产品的自动修复循环。

## 本轮复用与限制

复用现有 MCP 目录、授权、元数据索引、SQLGlot、Cline SDK、任务日志和证据事务。普通数据分析任务的 Cline 工具集合未扩大；SQL Studio 的探索能力仍要求专属协商标记及持久化的项目/用户/资产绑定。

新增 `GET_TABLE_DDL` 是从当前用户可见字段生成的结构 DDL，响应明确标注 `GENERATED_FROM_AUTHORIZED_METADATA` 和 `VISIBLE_COLUMNS_ONLY`。它不冒充数据库原始建表文本，不包含未采集的约束、存储或隐藏字段；对需要原始全量 DDL 的用例仍有差距。

缺失字段不得以“方言差异”放行；无授权不得以“缓存命中”放行；缺少目标 Hive 连接不得以“Oracle 能连接”代替目标试执行。
