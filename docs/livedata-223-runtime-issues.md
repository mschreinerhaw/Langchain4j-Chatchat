# LiveData测试库_223 Runtime OS 问题清单

本清单基于 2026-10-16 的真实 `db_ops_assistant` 任务、完整 Runtime 证据、独立计算复核、第二阶段门禁和目标机隔离部署。数据库只执行了已授权的只读模板；未执行 DDL、DML、配置修改、重启或压力测试。

| 优先级 | 问题 | 实际影响 | 建议修复与验收 |
|---|---|---|---|
| P0 | 综合业务请求无法稳定进入 Analysis Loop | 第一次请求被 `QUERY_REQUIRES_STRUCTURED_CONSTRAINTS` 拒绝；结构化重试又因估算 520 clauses 超过查询 DSL 预算而失败。用户的正常运维需求必须人工拆成两个任务 | 调整 Query Optimizer 对约束文本的解析和 clause 估算；加入本次原始请求的回归用例，要求单个业务请求可生成有界分步计划 |
| P0 | MCP 执行计划生成了两个互斥执行源 | 首个成功任务首次同时传 `templateId` 与 raw SQL，触发 `SQL_EXECUTION_SOURCE_CONFLICT`。本次通过模型重写恢复，但增加约 10 分钟任务时延且可能导致任务失败 | 在计划/schema 校验阶段强制二选一；对模板调用只保留 templateId/参数；加入无需模型重试的契约测试 |
| P0 | 最终结果没有以最新成功证据覆盖旧失败观察 | 三个模板重试成功后，模型最终回答仍声明所有源失败，造成状态与 Evidence 冲突 | Result Synthesis 按 attempt 和 observation 时序折叠状态；只允许最新有效尝试决定 source health；增加失败后成功的端到端断言 |
| P0 | 最终模型报告缺少确定性数值验证 | 模型误报采样时间、会话所属 schema、连接利用率和结果截断状态。若直接交付会误导运维判断 | 对时间、比例、分组计数、完整性和单位增加确定性 validator；冲突时以结构化结果为准并标记模型文本不可发布 |
| P0 | 数据库运维助手没有路由到 CodingAgentSPI | 本次真实任务没有 `CodingTaskContract`、DatasetHandle、Cline SDK 或 Python 业务执行，无法验收 AI Coding 数据分析主链路 | 为需要多表关联、异常检测或复杂计算的分析步骤增加显式 coding handoff；用本次数据建立只读、可复现的业务 E2E 验收 |
| P0 | 最新隔离部署没有形成可调用的 Java/Node 链路 | Java JAR 只上传至 staging 目录，18080 未启动；Node 8793 虽健康但回调不到 Java。本次真实业务仍由 8080 既有版本执行 | 建立独立 staging Java 配置、数据库与模型安全注入、回滚脚本，再将 Node 回调指向该实例；不得用 healthz 代替 Coding 任务验证 |
| P0 | 后端完整门禁仍失败 | 3,451 项中 142 失败、64 错误、0 跳过；定向复跑仍有 Tool Access 3/7、MCP discovery 3/8、授权隔离 3/3 错误 | 修复 semantic policy fixture、工具访问与模板发现回归，重跑完整套件；真实场景成功不能覆盖这些回归 |
| P1 | 大结果首次归档仍有 4 MiB/1 万行限制风险 | 本次模板最大 359 行，未触发 Snapshot Manifest、分片传输、断点恢复或分页结果路径 | 用真实 staging Coding 链路跑超过阈值的数据集；验证流式归档、分片失败重试、游标恢复、哈希、取消和显式超限拒绝 |
| P1 | 分片恢复仍可能重新扫描数据源 | 当前内容片可幂等复用，但缺少源游标精确恢复证据 | 在 SpillStore 中持久化受保护的源游标和快照版本；通过故障注入验证不重复扫描且源变化时拒绝混合快照 |
| P1 | Coding 活跃 SDK 会话恢复尚未在真实模型上验证 | 本轮只有 Runtime/MCP 重试，没有 Cline 会话中断、恢复及完整尝试链 | 在隔离 staging 注入 Worker 重启，验证 session/attempt 续接、预算保持、取消传播和 Evidence 完整性 |
| P1 | 慢查询结论证据不足 | `slow_query_log=OFF`，因此累计 `Slow_queries=0` 不能证明查询健康 | 优先使用现有低开销性能视图或经审批的短期采样模板；不得自动开启高开销监控 |
| P1 | 当前数据库连接生命周期存在治理风险 | 99 个连接中 98 个 Sleep，5 个超过 1 小时、3 个超过 4 小时，最长约 6.9 小时；全部以 root 用户连接 | 排查连接池归还、任务生命周期、wait_timeout 和最小权限账号；先观测、评审，再做任何配置变更 |

## 已确认可用部分

- MCP 已正确发现 `LiveData测试库_223`，识别为 MySQL 5.7.41、DEV、catalog `LiveDOP`，没有按名称猜测地址。
- 两个拆分任务通过真实 `hy3` 完成；8 个授权只读模板最终全部成功且 complete。
- 首次 SQL 执行冲突后的计划重写和再次执行真实发生，完整尝试保存在证据归档中。
- 目标机 Python 镜像可在限制条件下执行确定性 fixture；最新隔离 Node Worker 的鉴权健康检查通过。
- 现网 API/MCP 和生产 Worker 未被替换；本次无数据库写操作。

## 风险处置边界

当前可以把 MCP 数据获取和数据库运维助手的真实模型规划标为通过；不能把该结果扩大为 Cline 自主编程、Python Sandbox 业务执行或最新 staging 代码端到端通过。数据库报告中的数值采用独立验证结果，原始模型文本仅作为 Evidence 保存。

候选补充 SQL 仍保存在 [模板建议](livedata-223-innodb-template-candidates.sql)，本轮没有绕过授权执行。部署状态见 [部署清单](livedata-223-evidence/deployment-manifest.json)，任务及运行证据索引见 [证据 README](livedata-223-evidence/README.md)。
