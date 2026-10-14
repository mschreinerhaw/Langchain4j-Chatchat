# SQL Studio 元数据权限基础实施记录

本次继续实施 P1 的服务端边界加固。公开 Schema 检索接入逐资产授权和表字段投影，SQL Studio 生成会话增加只拒绝、不授予权限的工具策略。本轮没有启用新的联机 UI、查询执行入口或生产发布；P2 的发现会话与工作流、P3 的校验和独立执行仍按原计划推进。

## 公开 Schema 检索

`SqlMcpToolPublisher` 的公开 Schema 调用改为 `SqlMetadataSearchService.searchAuthorized()`。该路径要求 `McpAuthorizationService.authorizedAssetDomains()` 提供本次新鲜权限快照的资产域判断；缺少可信调用身份、授权服务或有效权限时不回退到内部无身份搜索。

资产域来自已注册资产 ID，身份来自 `McpInvocationContext`。检索请求的 tenantId、roles、scopeExpression 等参数不能覆盖资产域判断。每次工具调用重新取权限快照，权限撤销不会被发现缓存延续。

候选进入排名前应用已有数据源的 allowedTables、sensitiveTables 和 sensitiveFields 配置。公开路径复用现有 `rankScopedMetadata()`，仅用可见表和字段建立本次排名输入；不能先从含隐藏字段的全局索引取命中，再在输出时隐藏字段。目录、计数、摘要和详细字段都来自同一可见候选集合。

表白名单保留物理名大小写，不把 Orders 的授权扩大到另一个 orders 对象。敏感标识采用保守的大小写折叠拒绝，限制配置格式错误时拒绝。旧内部 `search()` 仍供已有内部服务使用，增加表字段限制投影；它不是面向模型的无授权替代入口。

公开结果中的后续操作说明也取消了固定“调用某个模板再执行”的指引，改为按需要继续探索或生成，并声明执行属于独立授权操作。

## 生成会话的拒绝边界

新增应用表 `ds_sql_generation_scope`，按 session_id 主键存储 project_id、tenant_id、owner_id，不保存连接、代码、工具授权或凭据。生成请求准备时与 Generation 一起登记；H2、PostgreSQL 和 MySQL 的既有增量脚本创建该表，迁移从现有已保存任务上下文回填旧会话并校验归属一致性。

工具调用只需按 session ID 查询应用来源，不扫描历史 context_json，也不信任模型传入的 profile 标志。范围记录独立于工程文件清理保留，使删除工程不会把旧生成会话变成不受该边界约束的会话。

`SqlStudioGenerationToolPolicy` 是额外的 deny-only 策略：

- 非生成会话不改变原有处理；生成会话的 tenant/user 不匹配直接拒绝。
- 仅对注册发布者明确声明 `sqlStudioGenerationBoundary.operation=METADATA_READ` 且 `executesBusinessSql=false` 的读取能力不追加拒绝。
- 不追加拒绝不代表授权成功，原 RBAC、License、Scope、确认及其他策略继续生效。
- DATA_FETCH、ACTION_EXECUTION、模板查询、未知能力以及伪造请求参数中的“安全声明”不能放宽生成范围。
- 声明来自 ToolMetadata 及其 MCP 原始元数据，不从工具名称猜测用途，不固定模型的调用顺序。

Schema 工具与企业标准元数据工具发布上述能力声明。查询网关没有获得声明，SQL Studio 生成任务不能因此调用它。独立执行操作的完整确认摘要绑定属于 P3，不通过本次策略假装已经完成。

现有策略合并允许后面的 allowed 值覆盖较早值。本轮仅在 API 层为既有 EnterpriseToolRuntimePolicyProvider 标明 Order(0)，让新的 SQL deny-only 适配排在其后，避免管理员的较早放行覆盖生成边界。没有修改通用策略合并、Kernel、AgentPlanner 或 Temporal。增加了实际 Spring 策略列表顺序的集成断言。

## 资产绑定的复用结论

仍使用现有 MCP 逻辑资产 ID、执行目标路由和权限域，不建立 data_source 到 mcp_sql_datasource 的自动复制、同名推断或新连接框架。已有 ExecutionTargetRouter 的绑定/歧义/具体连接拒绝测试纳入回归。SQL Studio 面向用户的绑定与发现上下文 API 在 P2 接入；本次的应用来源索引不等同于已经完成该 API。

## 测试范围与证据

新增测试覆盖：授权资产之外不加载索引、敏感表字段不进入排名与计数、空搜索范围、伪造资产与身份参数、非法限制配置、大小写不同对象、缺少可信身份、按资产域拒绝和下一次调用观察到权限撤销。

生成边界测试使用实际 H2 表和原 ToolRuntimeService：持久化来源、重复回填、其他会话不受影响、伪造能力标志无效、较早管理员放行后仍拒绝执行且不调用工具；SQL Studio 集成测试校验持久化范围和真实 Spring 注入顺序。

扩大回归覆盖原 EnterpriseToolRuntimePolicyProvider、SqlStudioIntegrationTest、SqlMetadataSearchService、SqlMcpToolPublisher、McpAuthorizationService、ExecutionTargetRouter、MCP 传输身份适配器及 ToolRuntimeService。测试命令和最终统计见本次执行日志 `target/sql-metadata-p1-tests.log`。

最终定向回归 11 组、137 项通过，零失败、零异常、零跳过，Maven exit=0。统计保存在 `target/sql-metadata-p1-verification.json`。其中 ToolRuntimeService 只选择身份传播、可信模板绑定和恢复授权的 3 项；不能将其写成整个 Runtime 测试类通过。

扩大执行完整 ToolRuntimeServiceTest 时，70 项中出现 13 项失败、3 项异常，集中于原批量工具调用的 BATCH_TOOL_NOT_ALLOWED 和空结果。另建无本轮改动的独立 HEAD 检出 `01657935a95e93a63594bfe71df7199fd4a7b68e` 复跑，复现完全相同的 16 项问题；已比对失败用例集合。证据为 `target/sql-metadata-runtime-baseline-verification.json` 与保存的 baseline report。本次不将存量失败计入通过，也没有据此修改 Runtime 核心。临时检出已清理，报告保留。

发布前必须对这些批量执行问题做独立审查与处置，特别是 P2 后续可能使用的批量探索路径；本轮未做生产部署。

这些是本地单元与集成回归，不代替十项真实模型/真实数据库联机验收。新增的资产域授权声明可能要求原先仅具有执行域授权的用户另行配置元数据读取权限；本轮不会自动修改角色授权。

## 后续阶段仍需闭合

P2 接入 `SQL_METADATA_DISCOVERY`、服务器发现上下文、逻辑资产绑定、分页、内容版本、新鲜度和主外键证据；同时处理缓存未初始化与刷新授权的独立语义。新公开路径的授权后排名需结合大 Schema 做载荷和性能验证。

P3 实现并验证联机字段解析、不同方言能力状态、EXPLAIN/查询独立任务，以及 SQL/参数/绑定摘要变化后的确认失效。P4 才接入 Schema 浏览器、Mapping/案例搜索和元数据探索 UI，P5 完成真实角色、真实模型及测试库验收后灰度发布。
