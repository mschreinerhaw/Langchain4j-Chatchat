# Coding Agent 有限预算完成能力（并入上下文性能专项）

## 权威边界

优化目标为授权 Token、调用次数和时间预算内的有效完成率，不能以少用 Token 替代完成任务。Java 只负责资源、授权、租约、证据与恢复；Cline 继续负责分析、选择工具、修复代码与报告语义。没有新增 Agent Loop 或总结 LLM。

每个 SDK prepareTurn 获取 `/internal/coding/cline/budget` 影响原生 basic compaction；每次实际模型 stream（包括 SDK 重试）再次获取最新快照。回调沿用签名、不可变合同、scope、租约及 fencing 校验。快照在一次 journal 读取中计算共享 budget 的实际已知消耗加未结清预留，返回剩余 Token、调用、时间、上下文估算、状态、未核对操作、未知用量及验证资产引用。估算采用 UTF-8 bytes / 4，不能当作供应商计费 Token。预算读取失败则不发送模型请求。

## 完成资源分配

配置 `chatchat.coding.completion-reserve.minimum-ratio`（默认 0.15）和 `maximum-ratio`（默认 0.35）。按合同要求的证据数量、已记录执行失败数和当前输入估算调整完成额度；额度始终包含在原预算中。达到任一资源的预留边界或已有验证资产后，进入 COMPLETION 分配阶段，提示优先完成必要验证与交付。Native compaction 按剩余 Token / 剩余调用缩小软上下文 allowance，完整错误、当前代码身份及完整待审报告受保护。

交付阶段还按同一配置的预留比例收紧当前上下文的软 allowance；同一请求出现完全重复的只读结果时按可识别冗余量收紧。两者只调用 SDK basic compaction，并继续保护原目标、最新完整报告和错误记忆；不需要等上下文接近模型窗口或平均预算上限。测试覆盖预算充足但进入完成阶段的提前压缩。

这是 **逻辑资源分配和模型策略信号**，最终硬限制仍为原 Runtime admission；不是新增可消费预算，也不是独立的强制探索配额。模型仍能在完成阶段选择原授权工具及必要修复，系统不按固定剩余三轮／最后一轮删除工具，不保证存在证据前一定完成，不强制生成结论。预留能否提高具体任务的成功率必须用受控实测验证。

## 阶段成果与恢复

Python 的独立 stop proof、结构/引用/图表验证通过后，将 code、execution、result、validation、bundle 引用及首次验证时间幂等保存在原 task journal 的 `deliverable:<operationId>`。完整内容保留在既有可信 evidence/file 存储。后续失败不删除此前资产；验证通过不等同业务正确或需求完整。失败代码及诊断继续保存在已有 checkpoint/evidence，不冒充有效结果。

最新执行的归一化错误、代码身份与有界源码修复片段作为技术记忆写入原 SDK checkpoint，在 SDK 压缩之后重新注入，始终标为非可信数据。beforeModel 和 onEvent 两条保存路径都保存 SDK compaction sidecar；超限时只丢可重建 sidecar，保留 canonical transcript。后续模型可按原授权 hash/ref 读取已有内容，不重新执行来读取结果。最后一次执行失败仍不能自动拿旧报告冒充本次完成。

Workspace 预取仅保留 SHA-256 和长度 sidecar。只有在实际模型请求经过 SDK 压缩后仍包含完全相同、完整的预取字节时，重复 workspace_read 才返回原上下文位置的引用，不再次消耗 Java 读取预算；若缺失、截断或发生变化，则走原授权读取。没有缓存第二份会话，也不把新的元数据搜索误认为可复用的旧结果。

模型 Java 回调异常退出时，独立归档“本地请求已终止、未交付响应”的事实。该事实 **不证明供应商停止计算，不补造用量**；原预留保持占用。恢复先查询原 operationId 的 response 或本地终止证据。仅对有证明、无响应的新 MODEL 操作，允许从原已完整结算的 checkpoint 安全推进 modelOrdinal，原 taskId/合同/授权/预算不变，并提升 fencing token 隔离已结束的 SDK。未知 Python/工具操作、没有证明、过期预算、停止请求均不能走此路径；原操作不得重复 dispatch。

## 评估定义

评估入口 `coding-agent-node/src/completion-evaluation.js`。输入为固定、去重的已接纳任务队列与 Runtime 证据导出；必须包含失败和未完成任务，不能只保留成功样本。

| 指标 | 定义 |
|---|---|
| Budgeted Task Success Rate | Runtime COMPLETED、需求验收通过、有验证资产、真实用量完整且未超过原调用/Token/时间预算的任务数 / 接纳任务数。业务验收来源须明确，不能仅以 Node completed 判定。 |
| Verified Deliverable Rate | 至少一个完整性及结构验证通过的持久化结果资产的任务数 / 接纳任务数。与业务结论正确性分开。 |
| Tokens per Successful Task | 全队列真实 input + output Token（包括失败工作）/ 预算内成功数；有未知回执或没有成功时为 null，并显示已知下界及缺失调用数。cached/reasoning 不重复累加。 |
| Model Calls per Successful Task | 全队列实际请求尝试数（包括失败）/ 预算内成功数；无成功为 null。 |
| Time to First Valid Result | 每个任务首次验证资产时间 - Runtime startedAt；没有资产的任务列为 censored，不从样本中静默删除。 |
| Reconciliation Recovery Rate | 按原 task/operation 身份、验证 stop proof、无重复 dispatch 并继续原任务的核对操作数 / 所有进入核对的操作数。没有核对操作为 null。 |
| Wasted Repeated Context Ratio | 同一实际请求内重复的完全相同只读工具输出字节 / 有该埋点请求的总字节。不同分页、必要历史、系统指令、跨请求重放和缓存 Token 均不能直接认定浪费。单位为精确 UTF-8 字节比例，不伪称供应商逐段 Token 计量；历史无埋点时为 null。 |

原故障的只读实测见 [诊断](coding-completion-failure-767792a2.md)、[原始指标摘要](coding-completion-failure-767792a2.json) 和 [评估输出](coding-completion-evaluation-767792a2.json)。单个失败病例不能证明提升；既有上下文专项的固定模型小样本也未证明 Token/耗时改善。本补充不把测试桩的完成率宣称为真实模型性能验收通过。

## 验证记录

- Node 原生 SDK、回调协议、恢复及监控测试：204/204 通过（新增交付阶段提前压缩测试）。
- Java 核对、回调、本地终止证明和动态预算测试：60/60 通过。
- 第一轮真实 `qwen3.8-omni-flash` 同工作负载测试：4/4 完成；旧基线输入 35,378、输出 4,558、11 次调用、97,472 ms；候选第一轮输入 42,597、输出 4,617、12 次调用、119,223 ms。未达到 Token 降低目标，负结果完整保留在 [r1 实测](coding-completion-provider-comparison-r1.json)。据此精简重复预算提示并修复可见预取内容的重复读取，再进行第二轮测试。
- 第一轮确定性 Java/Node 集成测试 4/5 通过；Hive 用例仍显示此前已知的重复预取读取计数（预期 5、实际 6）。保留原断言，以真实消除重复读取修复，不调整断言隐藏失败。
- 第二轮在精简预算提示和按可见内容哈希复用预取后：确定性集成 **5/5**、真实模型集成 **4/4** 均通过，原 Hive 工具预算计数断言恢复通过。真实模型输入 **36,805**、输出 **4,813**、**11** 次调用、**103,734 ms**，见 [第二轮实测](coding-completion-provider-comparison.json)。相比旧基线输入仍高约 4.0%，不能宣称相同任务 Token 已降低；代表性长任务与多次配对实验仍待验收。
- 额外运行的既有 Python 部署测试中，Windows archive path 校验测试原有失败 1 项；本次不涉及该前端 ZIP 路径，不能因此宣称全部部署测试通过。

以上样本是隔离 H2 元数据/SQLGlot 源码候选交付，不执行真实业务 SQL，也不是原股票分析重放。真实 Python 成果保存、长上下文、崩溃恢复与代表性任务完成率仍需生产测试和更多配对样本验收。
