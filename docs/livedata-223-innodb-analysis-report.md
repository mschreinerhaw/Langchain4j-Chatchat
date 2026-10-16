# LiveData测试库_223 InnoDB 运行情况分析报告

采样日期：2026-10-16；数据库运行情况：**存在风险，但当前快照没有显示严重 InnoDB 性能瓶颈**。结论来自数据库运维助手 `db_ops_assistant` 的两个真实成功任务，以及对完整 MCP 返回数据的独立确定性复核。单次快照只能描述采样时刻。

## 数据库与采样范围

- MCP 资产 ID：`c5c085c4-067b-4a0f-876c-e1c4f808232f`；环境：DEV；类型：MySQL Community Server 5.7.41；catalog/schema：`LiveDOP`；受控工具：`db_query_mysql_223_livedata`。
- InnoDB 快照：2026-10-16 16:40:13 至 16:40:14（Asia/Shanghai）。连接和空间快照：16:57:37 至 16:57:42。
- 实际执行 8 个已授权只读模板，全部最终成功且结果标记 complete。SQL、语句哈希、行数及时间见 [实际执行引用](livedata-223-evidence/db-ops-executed-sql-references.json)。
- 第一个任务首次把 `templateId` 与原始 SQL 同时提交，触发 `SQL_EXECUTION_SOURCE_CONFLICT`；Runtime 自动重写执行计划后，第二轮三个模板全部成功。最终模型文字仍保留“源采集失败”的旧警告，和第二轮结构化证据冲突；本报告以完整运行记录中的最终成功结果为准。

## Buffer Pool 与 I/O

Buffer Pool 配置为 4 GiB，共 262,112 页；空闲 8,194 页（3.126%），使用约 96.874%，数据页占 95.740%，脏页为 0。52 秒窗口内命中率为 1000/1000，物理读 0 次/秒，Buffer Pool 等待读写均为 0。全局累计命中率按 `1 - Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests` 独立计算为 99.921%。

该快照显示缓存利用率高且当时没有换页或刷脏压力。空闲页比例较低本身不构成内存不足证据，因为同时观察到高命中率、零物理读和零等待；应以多时点的物理读、wait-free、页淘汰和工作负载变化继续判断。

文件 I/O 窗口为读 0 次/秒、写 6.56 次/秒、fsync 3.17 次/秒；Redo log I/O 为 2.19 次/秒。Log sequence、flushed up to 和 pages flushed up to 均为 117895086390，last checkpoint 为 117895086331，checkpoint age 仅 59 字节；无 pending log flush、checkpoint write 或 `Innodb_log_waits`。采样时未发现 I/O 或 Redo 堵塞。

## 事务、连接、锁与死锁

`information_schema.INNODB_TRX` 返回 0 行；InnoDB Monitor 的 history list length 为 19，查询队列和 InnoDB 内部查询均为 0。采样时没有活跃事务、事务等待或行锁持有证据。输出中没有 `LATEST DETECTED DEADLOCK` 段，只能说明本次快照未返回最近死锁信息。

累计状态包含 55 次 `Innodb_row_lock_waits`，历史最大行锁等待 51,004 ms，`Table_locks_waited=0`。这些是约 212 天 uptime 内的累计值，缺少第二时点差分，不能判定当前锁风险；51 秒历史峰值值得纳入持续监控。

连接上限 550，当前连接 99、运行 1、Sleep 98，当前容量使用率 18.0%；历史最大连接 133，占上限 24.182%，没有 max-connections 拒绝。PROCESSLIST 中 64 个会话属于 `dolphinscheduler`，35 个属于 `LiveDOP`；5 个 Sleep 超过 1 小时，3 个超过 4 小时，最长 24,783 秒（约 6.9 小时）。大量休眠和长时间休眠是当前最明确的连接治理风险，应核查连接池归还、`wait_timeout`、任务连接生命周期和 root 账号使用范围。

## 存储与大表

实例数据库合计约 6,611.10 MiB；`LiveDOP` 为 4,048.22 MiB，占 61.234%。Top 50 表合计 6,502.87 MiB，其中数据 5,745.35 MiB、索引 757.58 MiB；`LiveDOP` 有 33 张表进入 Top 50，合计 4,000.41 MiB，索引 632.89 MiB。

主要空间对象：

| Schema / 表 | 总空间 | 数据 | 索引 | 估算行数 | 观察 |
|---|---:|---:|---:|---:|---|
| `LiveDOP.ld_trino_queries` | 1,219.58 MiB | 1,219.58 MiB | 0 | 1,477 | 单行平均占用异常高或统计已陈旧，应检查大字段、历史明细和表统计 |
| `dolphinscheduler.t_ds_task_instance` | 1,165.14 MiB | 1,113.00 MiB | 52.14 MiB | 592,784 | 属于同实例其他 schema，应与 LiveDOP 容量治理分开 |
| `LiveDOP.ld_rs_table_column` | 962.72 MiB | 520.00 MiB | 442.72 MiB | 5,287,859 | 索引占约 45.99%，适合检查重复、低选择性及未使用索引 |

information_schema 的行数为估算值，本次没有历史样本，不能推断增长速度。建议为上述表建立每日或每周容量基线，再决定归档、分区或索引调整。

## 配置与风险判断

- `innodb_flush_log_at_trx_commit=2`、`sync_binlog=1`。前者是性能与崩溃恢复能力的明确取舍，需确认 DEV 环境的数据丢失容忍目标；本任务未修改配置。
- `slow_query_log=OFF`、`long_query_time=10`，因此 `Slow_queries=0` 不能证明没有慢 SQL。需要现有低开销性能视图或经审批的短期采样补证。
- `read_only=OFF`、`super_read_only=OFF` 表明实例自身可写；本轮所有动作仍通过只读模板执行。
- Buffer Pool、Redo、I/O 和事务快照均无急性压力证据。连接生命周期、慢查询可观测性、历史 51 秒锁等待、两个大表的空间结构属于需跟踪风险。

## 优先级建议

1. P1：排查 98 个 Sleep 连接及 5 个超过一小时的会话来源，核对连接池、超时与 root 账号使用；先观察再调整。
2. P1：对 `ld_trino_queries` 和 `ld_rs_table_column` 做字段、索引、数据保留期和统计信息复核，建立容量历史序列。
3. P1：确认 `innodb_flush_log_at_trx_commit=2` 是否符合该 DEV 数据的恢复点目标。
4. P2：补采第二时点的全局状态，计算物理读写、锁等待、连接创建、Redo 和吞吐速率；本报告不把累计计数器当作当前速率。
5. P2：通过现有授权监控能力补充慢 SQL 证据。任何日志或性能采样强度变更均需另行审批。

原始 Agent 回答、任务事件和压缩后的完整 Runtime 证据位于 [证据目录](livedata-223-evidence/)。独立复核结果分别见 [InnoDB 验算](livedata-223-evidence/db-ops-discovery-deterministic-validation.json) 和 [连接/容量验算](livedata-223-evidence/db-ops-capacity-deterministic-validation.json)。
