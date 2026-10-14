# SQL Studio 联机元数据实施与验收计划

本计划从已经交付的[代码审查](12-online-metadata-architecture-review.md)进入分阶段实施。当前十项能力均为计划验收项，不能用现有离线生成成功、模拟模型结果或管理账号访问代替联机验收。

## 分阶段实施

| 阶段 | 实施范围 | 完成条件 | 前置依赖 |
| --- | --- | --- | --- |
| P0 架构审查与契约 | 复用、缺口、最小改动方案、MCP/工作流/应用接口及验收矩阵 | 五份文档完成，现有与拟新增契约可区分 | 当前交付 |
| P1 权限与复用验证 | 证明 API→Runtime→Gateway 身份传递；确定企业登记到 MCP 逻辑资产绑定；逐候选表字段过滤；服务端 metadata-only 工具范围；确认内容绑定 | 普通用户、拒绝用户和管理员均通过负向用例；不能通过无资产检索、参数篡改或模板执行扩大生成范围 | P0 |
| P2 元数据工作流与证据 | 现有工具兼容扩展；`SQL_METADATA_DISCOVERY`；分页、精确对象、键关系、内容版本、新鲜度、增量；发现会话和证据引用 | 模型可以以不同顺序探索并增量补充；旧 Top 5 调用仍兼容；刷新失败不被误报为零对象 | P1 |
| P3 Query 生成与校验 | 现有 Agent 入口联机工具上下文；单表/JOIN、补全修复、Schema 诊断、方言处理、证据输出；独立 EXPLAIN/只读查询任务 | 查询引用真实授权结构；不存在字段可诊断；生成零业务查询执行；执行确认与文件摘要一致 | P2 |
| P4 ETL 与 Studio 集成 | Mapping/SQL 案例权限检索；源目标绑定；多文件、增量/全量/分区假设；Schema 浏览器、Monaco 引用、探索面板与审查应用 | 模型自主选策略；未确认规则可见；多文件应用原子且保留版本；解释仍无写入入口 | P2，P3 的结果和校验契约 |
| P5 真实回归与灰度 | 十项矩阵、跨方言、授权撤销、Schema 更新、恢复与日志脱敏；应用开关、回滚和部署核验 | 真实模型/真实可控数据库的证据齐全；权限和执行隔离零违规；灰度回滚恢复离线入口 | P3、P4 |

P1 若证明现有端口缺少窄能力，先补充具体缺口与接口设计，再扩展现有授权或上下文适配。不得因为 Agent 工具筛选不合适而重写全局 planner、Kernel、权限仓库或 Temporal 核心。

## 实施文件分组

| 分组 | 原有文件 | 拟新增文件或窄扩展 |
| --- | --- | --- |
| SQL 应用 | `SqlStudioController.java`、`SqlStudioService.java`、`SqlOutputBundle.java`、`SqlStudioMigration.java` | `SqlStudioMetadataContextService`、`SqlStudioValidationService`、`SqlStudioExecutionService` 与应用 DTO；只存绑定和证据引用 |
| 元数据工具 | `SqlMcpToolPublisher.java`、`SqlMetadataSearchService.java`、`DatasourceMetadataSearchService.java` | `SqlMetadataDiscoveryWorkflow`、新协议输入/输出适配、授权结果投影 |
| 原采集缓存 | `MetadataIndexService.java`、`MetadataIndex.java`、`MetadataObject.java`、`MetadataColumn.java`、Collector 与系统目录查询提供者 | 复合键/外键、局部刷新支持、新鲜度、内容版本、增量和游标 |
| 资产上下文 | 现有文档检索、`DocumentLibraryReadPort`、工程文件服务、MCP 动态注册 | 按权限分页端口；确无可复用公开工具时增加薄 `sql_studio_asset_query` 适配 |
| 授权接入 | `EnterpriseToolRuntimePolicyProvider`、`McpAuthorizationService`、`McpInvocationContext`、现有资源权限接口 | 优先调用；必要时加 metadata-only 操作限制的窄入口，保证不是 first-allow 覆盖其他拒绝 |
| 编辑器 UI | `SqlStudioView`、`SqlProjectTree`、`SqlAssistantPanel`、`SqlEditorPane`、`sqlStudioApi`、`sqlDiagnostics` | Schema 树、证据/探索视图；复用原 Monaco 与 CodeChangeReview |
| 证据与事件 | 原 AgentTask 事件和 `EvidenceStorePort`/`JdbcRuntimeEvidenceStore` | SQL 应用投影与 lineage，不另建证据仓库和调度器 |

具体文件清单在每阶段完成时按实际实现更新。模板示例、采集器的系统目录 SQL 和方言适配允许按数据库规范实现；业务表名、字段映射、生成 SQL 模板与固定探索调用顺序不得硬编码到工作流。

## 十项质量验收矩阵

| 编号 | 用例 | 验收方法 | 必须留存的证据 |
| --- | --- | --- | --- |
| Q1 | 真实单表查询生成 | 在已有登记的可控数据库创建测试 Schema，经模型工具调用生成；表字段应来自真实快照 | 用户意图、metadata invocation、快照与内容摘要、生成文件、Schema 校验；另行授权查询可核对结果 |
| Q2 | 多表 JOIN | 使用真实外键或已确认业务 Mapping，包含同名字段与有序复合键；模型自主选择探索顺序 | 两端结构、关系来源、字段配对、JOIN SQL、基数或业务关联假设 |
| Q3 | 不存在字段检测与纠错 | 注入不存在字段，授权新鲜 Schema 校验明确失败；模型接收诊断后修复 | UNKNOWN_COLUMN 诊断、原/新文件摘要、额外元数据调用及修复对比；不能把缓存缺失算字段不存在 |
| Q4 | 跨 SQL 方言 | 至少两种已注册、可真实验证的引擎，另覆盖 Spark/Hive 生成的语法边界；验证 quoting、函数、分页及 ETL 目标方言 | 源/目标方言证据、适配后 SQL、支持或 UNSUPPORTED 状态；无对应实库时不得称联机通过 |
| Q5 | Mapping ETL 多文件 | 真实授权源/目标结构，上传有版本的 Mapping 和历史参考；包含转换、全量/增量、分区及未确认规则 | Mapping 文档片段版本、案例引用、多文件建议、待确认假设、审查与原子保存记录；不执行 ETL 写入 |
| Q6 | 元数据缺失补充探索 | 初始仅提供部分字段或超过一页表；模型发现不足后继续调用，不重启会话 | 同一 discoveryId 下多次不同操作、分页覆盖、预算、缺失到补足的证据变化；不断言固定调用次序 |
| Q7 | 权限不足正确拒绝 | 不同 tenant/角色，隐藏表/字段，越权 assetId、无绑定检索、伪造身份和游标；运行中撤权 | 真实服务端拒绝、未泄露对象、无 JDBC 越权调用、当前授权修订；不能只用 admin 证明 |
| Q8 | Schema 变化更新 | 采集后 ADD/ALTER/DROP 字段或表，再验证；刷新失败、旧游标和超期增量也覆盖 | 前/后快照、objectVersion 变化、ADDED/CHANGED/REMOVED、STALE/过期诊断及重新生成 |
| Q9 | 生成执行权限隔离 | 仅元数据权限的用户可生成但不能查询；尝试 sql_query_execute、模板/复合间接执行、ETL 写入、EXPLAIN ANALYZE；确认后改 SQL/参数/资产 | 生成期间业务 SQL 执行计数为零、全部越界拒绝；独立确认和执行记录、摘要匹配、过期确认拒绝 |
| Q10 | 全链路审计 | 串联 UI 操作、AgentTask、Run、MCP invocation、discovery、Evidence、Generation、Validation 和独立执行 | 可关联标识、契约版本、授权决定、脱敏来源/版本/时间、真实状态与失败原因 |

Q6 还须在复用已有有效快照时允许模型不调用某个检索步骤。安全边界测试可以固定断言，业务探索顺序不能用测试锁死。

## 分层测试与环境

契约测试覆盖 JSON 版本、兼容旧工具调用、空值、分页、脱敏、对象身份和内容版本。权限集成测试走真实认证、现有角色/Scope 与元数据接口，覆盖每次授权重新获取、工具集合交集及撤权。模型编排测试先用可控工具结果验证循环和事件，再做真实模型与真实测试库回归；两者结果分开记录。

浏览器测试使用现有 Vue/Monaco 环境，覆盖树分页和刷新、表字段引用、解释不写文件、生成差异、选区插入/替换基线、未保存内容保护、状态与事件续传、Schema 缺失状态和独立执行确认。语法解析异常或未支持方言不得显示为 Schema PASSED。

真实测试库通过现有数据源注册维护，由测试管理员准备隔离 Schema 和只读运行账号。Q8 的 DDL 仅由测试管理员在该隔离 Schema 修改；模型和生成工作流没有 DDL 权限。测试库或引擎不可用时记为 BLOCKED/NOT_RUN，不用 H2 模拟结果替代某厂商方言的真实通过。

运行回归前记录现有工程、角色和权限基线。临时文件、工程和隔离 Schema 可清理；任务和证据审计按现有保留政策处理。不能以回归为理由调整生产用户权限、扫描所有数据库或让模型执行写入。

## 发布与回滚

先在应用开关关闭的条件部署兼容契约，再对指定普通用户和已授权资产灰度。普通开发者的元数据读取能力通过原 RBAC 配置，不由发布脚本自动增加广泛角色授权。

发布核验包括 API/MCP 登录、工具目录和契约版本、旧离线生成、Metadata-only 拒绝执行、Schema 更新、事件和凭据脱敏。回滚关闭应用开关并恢复兼容入口，保留审计与新增应用记录；数据库迁移仅增量扩展，不能删除原工程或权限表。

## 当前交付与实施边界

P0 的架构审查与契约已交付。P1 已落地公开元数据的资产/表字段授权投影、生成会话来源索引和拒绝执行边界，见[权限基础实施记录](17-metadata-permission-foundation.md)。P2 首批已落地域工作流、版本化分页、持久化发现上下文和逻辑绑定，见[发现基础实施记录](18-metadata-discovery-foundation.md)。P2 剩余能力及 P3 到 P5 尚未完成，也没有把现有离线部署验证计入本次十项联机验收。

后续继续将 P2 发现上下文接入工具型 Agent、Schema 浏览器和证据链；确认摘要的完整绑定在独立执行入口开放前继续验证。真实角色、模型和测试库的联机结果仍须在 P5 独立记录。
