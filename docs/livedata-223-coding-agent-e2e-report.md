# LiveData测试库_223 Runtime OS + Coding Agent 端到端验收报告

验收时间：2026-10-16；分支：`main`；源码提交：`bdc7f898`。本轮已通过已发布的 `db_ops_assistant` 向平台真实提交业务需求，真实调用 `hy3` 模型并经授权 MCP 模板读取 `LiveData测试库_223`。整体端到端 PoC 结论为：**部分通过**。

## 真实任务执行结果

共提交 4 个任务。前两个综合任务分别被查询协议校验和查询 DSL 预算拒绝，未触碰数据库；随后按平台当前能力拆分为两个任务，均成功完成。

| 任务 ID | 目的 | 结果 | 关键证据 |
|---|---|---|---|
| `895a4b33-f1dd-30b4-9ceb-7a7131fdd304` | 一次性执行完整 InnoDB 诊断 | 失败 | `QUERY_REQUIRES_STRUCTURED_CONSTRAINTS`；7 个事件；未执行 SQL |
| `ee453339-2181-3835-8875-5ce9f80a7951` | 使用结构化约束重试完整诊断 | 失败 | `QUERY_DSL_BUDGET_EXCEEDED`，估算 520 个 clauses；7 个事件；未执行 SQL |
| `47657bbc-f125-3ca0-8829-a96e5c54660e` | 资产发现、InnoDB、事务和实例配置 | 成功 | Runtime `COMPLETED`，110 个任务事件，3 个模板最终成功 |
| `b0a92e00-9b60-32c8-bb88-b587b76d138b` | 连接、状态、数据库及大表容量 | 成功 | Runtime `COMPLETED`，149 个任务事件，5 个模板成功 |

按原始提交计算完成率为 2/4（50%）；按适配现有查询协议后的两个拆分任务计算为 2/2。成功任务合计耗时 1,358.295 秒，模型调用 28 次且无调用失败；Provider 报告输入 121,324 tokens、输出 51,054 tokens。两个失败任务的查询优化阶段没有纳入这组 Coding/分析用量。

## 实际协作链路

平台完成了以下真实链路：

`Agent API → db_ops_assistant → hy3 → Runtime Analysis Loop → MCP 资产/模板发现 → 已授权只读模板执行 → 结构化观察与 Evidence → 模型总结`

资产被识别为 MySQL Community Server 5.7.41、DEV 环境，资产 ID 为 `c5c085c4-067b-4a0f-876c-e1c4f808232f`，没有根据名称中的 223 推断地址。两项成功任务最终执行 8 个已授权模板，全部返回 complete。实际 SQL、哈希、采样时间和行数见 [SQL 执行引用](livedata-223-evidence/db-ops-executed-sql-references.json)。数据库分析结论见 [InnoDB 报告](livedata-223-innodb-analysis-report.md)。

第一项成功任务的首次执行错误地同时提交 `templateId` 和原始 SQL，触发 `SQL_EXECUTION_SOURCE_CONFLICT`。Runtime 和模型根据错误重写计划，第二轮三个模板均成功。这验证了真实的计划生成、执行、诊断、重写和重试，但属于 Runtime/MCP 调用修复，不是 Cline 生成 Python 后的代码修复。

## 模型结论与确定性验算

完整结构化证据能够支持主要 InnoDB、连接和容量计算。独立验算同时发现最终模型文本存在四类实质偏差：

- InnoDB 任务在重试成功后仍保留首次执行失败的过期警告。
- 容量任务把历史 `Max_used_connections_time` 当作本次采样时间。
- PROCESSLIST 实际为 64 个 `dolphinscheduler` 会话和 35 个 `LiveDOP` 会话，模型写成 99 个 LiveDOP 会话。
- 当前连接容量使用率应为 `99/550=18.0%`；模型用当前连接数除以历史峰值 133，得到不成立的 74.4%。模型还错误声称 359 行完整 SHOW STATUS 被截断。

因此真实模型调用与结构化数据获取通过，但最终自然语言报告不能在缺少确定性校验时直接作为可信业务结论。本轮报告使用 [InnoDB 独立验算](livedata-223-evidence/db-ops-discovery-deterministic-validation.json) 和 [容量独立验算](livedata-223-evidence/db-ops-capacity-deterministic-validation.json) 修正上述问题。

## Coding Agent、Sandbox 与部署边界

本次发布的数据库运维助手没有创建 `CodingTaskContract`，没有把 DatasetHandle 提交给 `CodingAgentSPI`，也没有调用 Node Cline SDK 或 Python Sandbox 执行业务分析程序。因此以下能力没有被本次真实数据库任务验证：多数据集 Python 关联、Cline 代码生成与修复、Python 结果分页/归档、Coding Evidence 全尝试链、Coding 任务取消及会话恢复。

最新 Java JAR 已构建、校验并隔离存放在目标机 `/opt/coding-deploy-stage-20261016-phase2/artifacts/`，但未替换 8080/8090 的既有服务。最新 Node Worker 镜像 `runtime-coding-agent:phase2-staged-20261016` 只在 `127.0.0.1:8793` 隔离运行，鉴权健康检查通过；其 Java 回调目标 18080 没有服务。因此，本轮真实业务实际走的是 8080 已发布平台链路，不能声称已验证隔离 staging JAR 或最新 Node Worker 的完整集成。部署详情见 [部署清单](livedata-223-evidence/deployment-manifest.json)。

目标机 Python 镜像已在无网络、只读根文件系统、256 MiB/1 CPU 限制下执行过确定性 fixture 并产生哈希产物，但这只验证运行环境可用，不代表本次数据库业务通过 Sandbox API 或 Cline 执行。fixture 与结果保存在 [证据目录](livedata-223-evidence/)。

## 门禁与验收矩阵

第二阶段既有 G1/G2/G3、Node、前端及选定 Java 测试记录为通过；G3 的模型与模板源是确定性替身。完整后端 3,451 项中仍有 142 项失败、64 项错误、0 项跳过。相关定向复跑仍出现 Tool Access 3/7 失败、MCP 数据库模板发现 3/8 失败、授权隔离 3/3 因缺失已发布 semantic policy 而错误，见 [定向复跑结果](livedata-223-evidence/focused-gate-rerun.json)。真实成功场景证明现网相应资产和模板可用，但不消除全量回归风险。

| 验收项 | 结论 | 说明 |
|---|---|---|
| MCP 资产发现 | 通过 | 正确发现目标资产、版本、环境、catalog 和受控工具 |
| 模板选择与参数绑定 | 通过但有缺陷 | 8 个模板最终成功；首次出现双执行源冲突并自动恢复 |
| 授权数据获取 | 通过 | 仅执行已发布只读模板，结果 complete |
| DatasetHandle/大结果分片 | 未触发 | MCP 返回进入 Runtime Evidence，没有生成 Coding Snapshot Manifest |
| Java CodingTaskContract | 未触发 | 数据库运维助手未走 CodingAgentSPI |
| Node Cline SDK 真实调用 | 受阻 | staging Worker 健康，但没有 Java 18080 回调，也不在本次任务路径 |
| Python Sandbox 业务执行 | 受阻 | 仅环境 fixture 通过，没有执行本次分析代码 |
| Runtime 错误诊断与恢复 | 部分通过 | MCP 参数冲突真实恢复；未验证 Cline 代码修复 |
| Evidence Governance | 部分通过 | MCP、模型、运行和全部尝试已归档；缺少 Coding/Python 证据 |
| Runtime Analysis Loop | 通过但有缺陷 | 两个真实任务完成；最终文本存在需确定性修正的偏差 |
| 事件、预算、取消、恢复 | 部分通过 | 成功任务事件完整；查询预算拒绝得到验证；未主动取消或恢复活跃 SDK 会话 |
| 大结果分片、哈希、归档 | 部分通过 | 完整运行证据已压缩并计算 SHA-256；业务链路未使用 Snapshot 分片协议 |

## 明确结论

- 数据库运行情况：**存在风险**；当前快照无严重 InnoDB 压力，但连接生命周期、慢查询可观测性、历史长锁等待和大表空间需治理。
- MCP 数据获取：**通过**。
- 数据库运维助手真实模型规划与执行：**通过，但最终总结存在确定性错误**。
- Cline 自主编程：**受阻/未触发**。
- Python Sandbox：**环境通过，真实业务链路受阻/未触发**。
- Evidence 回流：**Runtime/MCP 部分通过，Coding Evidence 未触发**。
- Runtime Analysis Loop：**通过但有缺陷**。
- 整体端到端 PoC：**部分通过**。

压缩后的完整 Runtime 运行证据、提交响应、状态事件、原始回答和独立验算均保存在 [证据目录](livedata-223-evidence/)，具体文件和哈希见该目录的 README。
