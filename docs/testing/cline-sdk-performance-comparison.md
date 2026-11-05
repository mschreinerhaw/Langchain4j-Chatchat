# Cline SDK 复用整改性能对比

日期 2026-11-05；审查 main `0693c15fd9184f112d0111f3085b16943841a72d`，实现 `3e28607b9`。
SDK 五包保持 **0.0.93**。源码方法、复现和上游状态见
[审查](../audits/cline-sdk-reuse-audit.md)、[上游](../audits/cline-upstream-issues-prs.md)、
[回归命令及结果](cline-sdk-native-regression.md)。
主要改动为 SDK Editor 复用、原生 max-token 反馈、CodeHistory 精简和普通遥测容错。

## 方法与证据

同一环境，模型 `qwen3.7-plus`，相同指令、不可变 CRLF 两模块输入、平台默认预算，不使用业务数据。
baseline 在部署前原 worker 执行；candidate 在只替换五个 Node 模块后执行。每组 **n=1**，
外部模型随机性、服务负载、缓存及网络差异未控制，不能据此作总体性能或成功率推断。

真实联机复现脚本 `scripts/verify-cline-sdk-reuse-live.py`，需要私有发布目录中已鉴权 token 和已准备且显式授权的快照：

```sh
python3 verify-live.py /opt/chatchat-releases/cline-sdk-reuse-20261105-r1 baseline
python3 verify-live.py /opt/chatchat-releases/cline-sdk-reuse-20261105-r1 candidate
```

新一轮测试必须准备新 requestId 和绑定输入，不能对已完成任务重复调用后声称新样本。
两个请求使用相同不可变 source ASSET：

| 文件 | SHA-256 |
|---|---|
| main.py | `8300ffaf0d02f678fa6999318392891b915e9b9bba3adda7c9adb3a875a034a8` |
| calc.py | `ecddb5163cbe675a436c371c38b2064e218415c1478a0e3517c266e9257ac8ac` |

脱敏原始证据：[baseline](evidence/cline-sdk-reuse-20261105/baseline-verification.json)、
[candidate](evidence/cline-sdk-reuse-20261105/candidate-verification.json)。
模型 token 分项从实际 MODEL_USAGE 事件累加，并与 Java ledger 总量核对。
monitor 顶层聚合 modelCalls/inputTokens 为 0，但各事件和账本有值，因此不能使用该聚合 0 冒充未调用模型。
Checkpoint 为 Java journal 中最终 sdkCheckpoint 的 UTF-8 紧凑 JSON 序列化字节数，
不包含 owner journal 其他数据、数据库行转义和分块开销；不是全程峰值。

## 实测

| 指标 | 整改前 | 整改后 |
|---|---:|---:|
| Coding 完成数/样本数 | 1/1 | 1/1 |
| 自动修复成功数/触发任务数 | 1/1 | 1/1 |
| 执行的修复轮次 | 1 | 1 |
| 模型输入 Token | 58,429 | 56,274 |
| 模型输出 Token | 3,119 | 6,493 |
| 模型总 Token（账本一致） | 61,548 | 62,767 |
| 真实模型调用 | 4 | 4 |
| Python 执行次数 | 3 | 2 |
| 提案拒绝事件 | 0 | 1 |
| 客户端端到端耗时 | 79,683 ms | 123,514 ms |
| p50 / p95 | 未测；n=1 不足 | 未测；n=1 不足 |
| Java ↔ Node 完整 RPC 次数 | 未采集 | 未采集 |
| monitor 保留事件数（不是 RPC 数） | 40 | 36 |
| 最终 SDK checkpoint 大小 | 124,256 B | 106,455 B |
| checkpointOrdinal / modelOrdinal | 6 / 4 | 7 / 4 |
| SDK_BASIC 压缩次数 | 2 | 2 |
| 真实模型任务中断恢复率 | 未做故障注入 | 未做故障注入 |
| Node 真进程异常退出后恢复 | 现有用例 | 全套回归通过，模型/Python 不重放 |

候选输入 token 减少，但输出增加、总 token 和耗时增加；Python 次数和最终 checkpoint 变小。
无法把任何变化单独归因为本次代码优化，也不能宣称吞吐、p95 或总体成功率提升。
改进证据主要是可复现的 CRLF 适配问题修复、两份编辑逻辑收敛、冗余状态移除和必要治理未绕过。

两次任务均返回 COMPLETED、resultAvailable，并具备结果、验证及 bundle 引用；
首个失败均为 SyntaxError/repair.allowed=true，最终 attemptsUsed=1。
候选有一次 CODE_PROPOSAL_INVALID，仍在原任务完成；不把它隐去，也不将其算作一次已执行修复。
候选最终 calc.py 只补冒号，main.py 增加 Runtime 合同报告输出。
两次都发生 SDK 压缩并交付相同演示数值，但这不是近最大上下文压力的结果等价证明。

## 计量和验收限制

两次 usage 均 `meteringComplete=false`，仍保留 token、CPU、输出等预算 reservation。
CPU/内存/费用的未报告值不能解释成零开销或免费；本报告不基于它们比较成本。
完整 RPC 计数和峰值 checkpoint 未被现有记录逐次可靠采集，不从事件数或 checkpointOrdinal 推算。
单次真实恢复、SQL/Studio 全业务性能、供应商上限上下文、多样本 p50/p95 尚无本轮数据。
后续若需判定性能收益，应固定模型参数和预算，随机交错多个配对任务，保存每次 ledger、
模型事件、RPC access log 和检查点大小后再计算；不得预先填入提升比例。
