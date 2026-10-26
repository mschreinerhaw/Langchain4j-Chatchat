# Cline 代码生成性能：代码级基线与实施依据

审查基线：`1dcf93600`，优化分支 `perf/cline-codegen-20261026`。本报告在性能代码修改前提交。没有修改生产配置、调用生产业务任务或替换模型。

## 已有实测证据

`studio-cline-history-20261021-evidence/python-verified-acceptance.json` 保存的是提交 `db6edb6` 的历史样本，不能冒充当前分支基线。任务总计 83,454 ms，3 次模型调用耗时分别为 1,927 / 67,767 / 7,995 ms。第二次占总墙钟的 81.2%，输入 1,591 Token、输出 4,158 Token、报告推理 Token 1,902。三次调用耗时合计 77,689 ms；其余 5,765 ms 不能在缺少跨度的情况下全部归入工具执行。回执中 Python 主文件 2,055 字符、测试文件 5,268 字符。

这些记录说明主要耗时落在一次模型响应，而且输出量及推理量较大；不能区分供应商排队、首 Token、推理和生成各占多少。报告推理 Token 不与输出 Token 相加，供应商可能已将其计入输出。原始 prompt、工具参数和响应内容仅在受控私有证据中，仓库没有完整副本，不能还原这次精确请求。SQL 历史样本为 40,099 ms、3 次模型调用，同样不是当前版本复测。

复算命令：`python scripts/analyze-codegen-baseline.py`。当前分支修改前 `cd coding-agent-node && npm test`：78/78 通过。只证明现有回归通过，不代表真实供应商性能。

## 实际调用链和 SDK 可用性

1. Studio/分析服务形成契约，`CodingWorkspaceService` 保存授权输入和预算；`CodingAgentSPI` → `ClineCodingAdapter` 使用已有持久任务、租约、Checkpoint 和 Evidence。
2. Node `createCodingRun` 创建 `@cline/agents` Agent；当前锁定 `@cline/sdk/core/agents` 0.0.93，已安装公开 API 及原有测试核对 `prepareTurn`、Tools、`lifecycle.completesRun`、`completionPolicy`、Hooks、restore/continue/abort、SDK 编辑和上下文压缩。
3. `createRuntimeModel` → 已认证 Java model callback → `ClineRuntimeModelBridge` → Runtime 授权模型。Java 做模型预算准入、取消/Deadline 检查及用量登记，不在 Node 直连供应商。
4. 工作区工具由 Java 读取不可变快照、保存代码、确定性解析、登记 Evidence。分析工具走既有 Python Sandbox 回执、结果产物检查和增量修复策略。Java 仍最终判定成功。

## 可证实的问题与影响范围

| 问题 | 代码依据 | 最小改造及预期收益 |
| --- | --- | --- |
| 工作区任务以一次模型调用请求固定的 `workspace_read` | runner 的工作区 prompt；Java 已持有唯一授权不可变输入 | 仅对显式启用且上下文有界的工作区任务，由同一授权回调预载一次；省去模型决定固定读取的请求。预载仍登记工具操作与 Evidence，不伪造模型工具调用 |
| 工作区提案解析成功后仍让模型收尾 | `workspace_propose` 没有完成工具生命周期，分析快速路径已有 SDK 完成机制 | 复用 SDK 完成工具，在提案有效且 Evidence 引用齐全时完成；失败通过同一会话继续修复，省去可选总结请求 |
| 各次模型请求与工具请求缺少统一有界元数据 | Java 已有 duration/queue/usage；Node 工具已有 afterTool 时长，工作区读取和前置阶段覆盖不完整 | 补充阶段、请求及响应字节、内容哈希、工具数、首事件及总时长；不记录敏感正文，不将缓冲回调首事件伪称供应商首 Token |
| 输出预算只有全局与工作区上限 | Bridge maxOutputTokens | 增加受控快速路径输出上限配置，与原预算取最小值；不默认缩小导致截断、不扩大总预算 |

不因 67.767 秒盲目更换模型、关闭推理或减小输出。当前 Java 已有复杂度模型配置能力，所有性能对比只能使用已授权配置；费用未知时保持 null。复杂分析继续使用现有 Cline 循环，不新增规划器或修复调度器。

## 验证方法与限制

逐项记录关闭/开启策略的同任务对照：模型次数、实际请求字节、工具耗时、代码/校验结果、Token、费用、总耗时。使用真实 SDK 的确定性模型夹具验证固定交互减少，不将夹具耗时或固定延迟外推为供应商收益。Java/Node 跨进程测试保留业务验收断言；正常 SQL/Python、解析失败、多轮修复、取消、超时、恢复、并发和权限测试均需覆盖。

真实模型与隔离端到端测试需要独立运行实例及已授权模型配置。本机 Docker Engine 当前未启动；不能将生产服务器当作本次测试环境。没有真实结果前，性能报告必须标明未验证项，不宣称完整联机通过。通过独立分支 PR 交付，不覆盖主分支。
