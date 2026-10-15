# SQL Studio Cline 集成前置验收复核

日期：2026-10-16。用户要求：Python 工作完成并通过验收后，才开始 SQL Studio 的 Cline Coding 集成。

用户已于 2026-10-16 明确确认：**包含全部待办，全部验收后再集成 SQL Studio**。生产 Coding v1 的通过不单独构成启动 SQL Studio 的条件。

**本次尚未修改 SQL Studio 或启动集成编码。** 生产 Coding v1 通过复核，但此前完整待办尚不能全部标为通过。不得将 v1 成功任务替代全部历史门槛，也不得为关闭门槛编造 provider 回执或加入 Runtime OS 业务硬编码。

## 已复核通过

- 线上 API、MCP 构件哈希与生产验收清单一致，回滚备份和源码快照存在，生产 Coding 开关启用。
- 认证后重提原请求，仍返回 `69c831bb-732d-3ecb-9b32-2f1b7fe88e1b` / SUCCESS。
- 持久事件序号仍为 75，答案未改变，Coding 子任务 COMPLETED、六项证据完整、Python 执行次数仍为 1，无待协调标志。
- 新增补跑既有测试：`CodingBudgetMathTest` 9 项、`CodingEvidenceCollectorTest` 4 项、`CodingInputSnapshotServiceTest` 1 项、`CodingInvocationContextTest` 2 项，16 项全部通过。没有修改生产代码或放宽测试条件。

证据：`target/coding-production-restart-idempotency.json`、`target/coding-prerequisite-additional-tests.log`，以及[生产部署验收记录](runtime-os-coding-production-20261016.md)。此前相关 Java 196 项与本轮 16 项不重复，合计 212 项通过；这不等于仓库全量测试通过。

## 完整待办仍存在的缺口

| 项目 | 当前事实 | 关闭所需证据 |
| --- | --- | --- |
| 历史 provider 超时 | 历史隔离任务 `d1-smoke-529585c8ee094b6a811b4c05bc1a6044` 最后一次可信记录为 RUNNING / reconciliationRequired；当前没有新 provider 回执 | 可核对的 provider 执行、停止和计费事实，或依据真实事实的受审计人工核对；不得按本地超时推断 provider 已停止 |
| 活跃 Coding 会话跨进程恢复 | Node `server.js` 使用内存 Map，runner 暴露 snapshot 但未接入持久化恢复；已完成任务恢复通过不代表活跃 SDK 上下文恢复通过 | 持久 checkpoint、恢复后的 ordinal/预算连续性、无重复模型或工具执行的跨进程验收 |
| 动态补数 v2 | Java `CodingProtocol` 与 Node 提交入口只接受 `runtime_os.coding.v1` | 按既有设计实现版本化合同、输入版本集、补给账本、Java 授权工作流交接、Node continue 与证据血缘验收 |
| D3/G3 完整验收 | 数值、文本、真实生产计数和早期技术故障修复已测；尚无完整五类金融故障、组合注入与未注入对照验收 | 类型、缺失交易日、重复记录、精度、收益对账的真实 Coding 对照与独立验算，业务夹具与答案留在测试侧 |
| 旧 Harness 回归基线 | 旧测试仍使用历史 String 模型调用与旧协议声明 | 将测试迁移到真实 ChatRequest、计划与当前输出协议，并保留原有安全/行为断言；不放宽生产协议 |
| 本地 Harness 优化的新版本验收 | 已完成模型自主 Coding 接线优化、状态连续性、取消/截止时间与结果准入；78 项相关本地测试及完整 API 打包通过，尚未部署 | 新构件须完成相应真实链路与部署验收，不以本地测试替代生产证据 |

主分析 `RUN_CODING` 接线已随生产 v1 实现。较早文档中的“D2 未编码”“生产关闭”属于当时状态，应结合后续验收记录阅读；完整 D3/G3 尚无通过记录。

## SQL Studio 已确认的现有边界

仅进行了只读源码核对：`SqlStudioService.prepare` 当前经现有 Runtime 任务提出文件建议，生成会话不执行数据库 SQL；`SqlStudioGenerationToolPolicy` 基于服务端持久会话，仅允许明确声明的元数据读取。后续 Cline 集成须保留这两个边界，以及现有文件版本与变更审阅流程，不能继承 Python 分析任务的数据访问权限或开放直接数据库连接。

前置条件已由用户确认为“此前完整待办全部验收”，SQL Studio 集成保持未启动。逐项取得实际验收证据后才能改变门槛状态。

本地 Harness 新版本的实现与验证见[优化计划及实施记录](harness-cline-analysis-optimization-plan-20261016.md)。其本地 API 构件 SHA-256 为 `4456a99534f5f5d2d6347dddb9e384b4f30db20c6957c31c94555769efdfe39e`，与已部署生产 v1 的构件分开记录。此次范围确认不改变任何历史未知任务的状态或预算预留。
