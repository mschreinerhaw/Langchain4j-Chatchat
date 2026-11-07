# Phase 4：A、C、E NameError 逐项失败根因

任务一先行提交；审查基线 `ca0f7a47d`，独立分支 `fix/native-coding-phase4-delivery-20261107`。只审查原第四批三个失败任务，未修改、续跑或重新结算原任务。逐模型原始工具参数、源码版本、执行及停止证明见 [只读证据投影](../testing/evidence/native-coding-phase4/before/summary.json)。原任务状态与 usage 审计前后逐字段相同。

## 结论与最小整改矩阵

| 场景 | 已证实的归因 | 不能据此断言 | P0 最小修复 |
| --- | --- | --- | --- |
| A | A 类：模型生成超出原 ASCII slug 要求的 Unicode/fold 功能及相互冲突的自建测试。最终 18 项测试仍有 1 fail。E 类：先用 2 次执行探查目录/解释器，之后一批测试及示例检查再用 2 次；修改到 v5 后已无执行余额 | 不能把失败测试删掉，也不能把其中原需求 holdout 通过当成项目通过；不能证明仅补充状态事实一定修好模型代码 | 保留真实失败诊断与验证版本；通过现有 SDK prepareTurn 披露原预算余额、最新声明验证状态。不得注入正确实现或特定字符处理规则 |
| C | C 类：v1 的声明验证确实通过，随后不改源码；仍继续检查并耗尽 4 次执行及 8 次模型调用。E 类：SDK 保存的压缩投影不含该验证回执，Runtime 技术记忆只注入 lastExecution，没有独立声明验证事实 | **不是 Java 丢失 lastVerification，也不是现有管道策略再次误拒。** 后续测试在代码预算为 0 时拒绝合法；尚不能证明模型继续调用全由压缩造成 | 复用原持久 lastVerification，以准确、绑定当前 manifest 的状态投影进现有 prepareTurn；同时披露已有预算，不代替模型结束 |
| E NameError | C/E 类：v1 已产生正确代码，v2 的声明测试通过；模型继续拓展类型规则，额外测试触发 TypeError，之后又修改到 v3/v4。最终源码独立通过，但最后声明验证只属于 v2。v4 重测全部因原执行预算耗尽而拒绝 | **不是当前版本已有有效声明验证却被误判。** 不能直接复用 v2 的通过认证 v4；v1–v4 独立都通过，不应称为已证明源码退化 | 精确披露 PASSED/FAILED/STALE 和当前代码身份；源码修改即失效，保留历史证据；展示原预算余额帮助原 Agent 自主决定后续操作 |

三项均无证据证明 SDK 原生编辑算法失效。没有发现需要修改 SDK/vendored 文件、扩大工具或增加预算的理由。任务一以多因素归因为准，不将全部 FAILED 归为模型问题。

## 原始任务与结果

| 场景 | 原始 taskId | 已知 Token | 模型 / 实际 CODE | 最终版本 / 独立复核 |
| --- | --- | ---: | --- | --- |
| A | `native-6428a84104aac650c62301d7bbe1e2a3babd152bfd9a30dd` | 49532 | 8 / 4 | v5，失败 |
| C | `native-9e113a68ec4416c1ff650c3f426b0acba98d18b41eb8c02a` | 38118 | 8 / 4 | v1，通过 |
| E NameError | `native-ddc2e4064b9b77fb75072aa5fad1a2af39ba1f003fa9c092` | 40501 | 8 / 4 | v4，通过 |

固定 `qwen3.8-flash`，8192 最大输出；每任务 8 次模型、262144 Token、16 次工具、4 次 CODE、300 秒；Sandbox 75 秒、1 CPU/512 MiB/NONE 网络，镜像 `sha256:a3a279fc391bda1bb5aefb62acd8ee56200215ce2905cfb83d189398c33c5828`。CPU/成本计量不完整，保留原 reservation，不能报成零实际消耗。全部原任务 FAILED 且独立停止，无待核对操作。

## A：生成的规格与测试冲突

原始需求是多文件 ASCII slug/CLI、至少六项 unittest。模型自行增加 Unicode 分解、特殊字符转换、fold/tokenize、stdin 和额外类型契约。v4 声明测试失败后，模型增加 `ß → ss` 等映射，修复其中部分检查，却留下自建断言 `fold("ÀÉÎÕÜß") == fold("aeiou")`；最终实际得到 `aeiouss != aeiou`，18 项测试 1 fail。不能以人工修改该断言制造成功。

v0–v5 独立复核全部未通过；没有可直接回滚为正确交付的历史版本。v4 的声明验证实际退出 1，后续示例检查退出 0 不能替代测试；v5 尚未获得新执行回执。完整文件 Hash、每版本结果及创建时间见原第四批 [历史证明](../testing/evidence/native-coding-20261107/C-general-r4-history.json) 和本报告逐项投影。

## C：验证已保存，但收敛没有发生

v1 manifest `3f685d245c2e79e6b40e815119abc115dd47ab8fe0fea23163bc43f4b1e610e0`。真实声明测试操作 `cline-699d94ea752cf25c86e1eb04946e9de082387aec3ed7d9d972f06778768a2bf2`：exitCode=0、resultAvailable=true、executorStopped=true、声明匹配、新策略有效；停止证明 `mcp-python-receipt:08e7aabe886d3b0b42595e8b014625167055d5857443ea9f4ab6d4c9063fa21f`。

随后计数 helper 的检查操作 `cline-48b397abfd1be92708a26258fc7e4f76e438750e69df76065855737e74f260eb` 成为 lastExecution，但 Java `native-last-verification` 仍是前者，源码也未变。完成 guard 在模型选择结束时可以认可它；实际八轮均返回工具，没有普通最终回答，最终 SDK failed，Java 保存 failed 意图，未发生 completed → FAILED 的误映射。

## E NameError：最终正确不等于最终版本已验证

最后声明通过属于 v2 manifest `d1dd88edd62feb0737199e0d2b3b96eb7a381ba1335fedfc1dc01e79337a43a0`，操作 `cline-3319239ee9488e0ddcdcc7d1e4bcb0ebffed7e2c23323545deb760609e8bbe21`。随后同批额外类型检查退出 1，消耗第四次执行。模型继续两次编辑得到 v4 manifest `97b6858f5291da5079807e45e89d45792227a9fe347cf51d029d83cd79b9aa07`；这一版本独立正确，但原任务没有其匹配的声明测试回执。Runtime 必须拒绝把 v2 回执用于 v4。

## Context / Checkpoint：区分三种证据

Agent 原始 transcript 仍含旧工具结果；这不等于模型实际请求包含它。三项保存的 SDK `compactionState.messages` 都只有 3 条，均保留原始需求，但不含对应最后声明验证操作/manifest。Node `technicalMemory` 仅有 workspaceFiles、lastExecution(exitCode/operationId)，缺验证策略、当前版本绑定、通过/失败状态和原预算余额。见 [保存的压缩投影事实](../testing/evidence/native-coding-phase4/before/compaction-facts.json)。不能笼统称为“整个任务需求丢失”。

Java 已持久保存完整 lastVerification，并且普通检查不会覆盖；现有源码修改后 guard 拒绝旧版本、后续匹配测试失败使旧通过失效。缺口主要是这些权威事实没有准确提供给模型，以及 lost-ACK 重载分支只更新 lastExecution、未刷新 lastVerification。应保留现有持久化，修正有界事实投影及重载，不新建验证数据库。

SDK 0.0.93 `definitions.ts:createDefaultTools` 默认 `enableSubmitAndExit=false`；`agent-runtime.ts` 通过无工具最终回答和 completionGuard 结束。Runtime 当前与此机制一致，不能仅因没有 submit_and_exit 判定能力丢失；本轮不增加终止工具或强制结束规则。

## 证据限制（F 类）

历史逐轮原始模型请求正文没有完整留存：现有 journal 更新最新 SDK checkpoint，每模型仅保留 checkpoint ordinal/Hash，响应及原始工具调用则是不可变证据。`ClineRuntimeModelBridge` 仅保存请求大小、Schema 指纹等有界信息。因此本报告没有将重建请求当原始请求，也不声称独立证明每一轮模型看到/没看到哪段文字。压缩状态遗漏验证字段和源码事实投影缺口已可由保存状态及源码直接证明，其对随机模型行为的因果效果必须由最小修复后的真实模型回归验证。

本轮 P0 验收首先限定 A、C、E NameError；三项真实完整任务未通过前，MCP、Skills、更多语言及工作区配额扩展停止。原失败、历史版本和原预算不变，最终状态不得人工改写。

## 逐轮时间线（UTC）

### A

| 模型轮 / 开始 | 实际工具与起始版本 | 执行结果或拒绝 |
| --- | --- | --- |
| model-1 / 08:31:10.820 | run_commands (v0) | exit=0, 声明=false, cline-065dd45cf1c5<br>exit=0, 声明=false, cline-e6f0cf970d46 |
| model-2 / 08:31:25.405 | editor /workspace/code/textkit/normalize.py (v0)<br>editor /workspace/code/textkit/cli.py (v1)<br>editor /workspace/code/textkit/__init__.py (v2) | 原生编辑结果已归档 |
| model-3 / 08:32:34.965 | editor /workspace/code/tests/test_normalize.py (v3) | 原生编辑结果已归档 |
| model-4 / 08:32:53.186 | run_commands (v4) | exit=1, 声明=true, cline-ff14f72570e6<br>exit=0, 声明=false, cline-11a0d739973f |
| model-5 / 08:33:18.607 | editor /workspace/code/textkit/normalize.py (v4) | 原生编辑结果已归档 |
| model-6 / 08:33:34.363 | read_files (v5) | SDK success |
| model-7 / 08:33:39.615 | run_commands (v5) | BUDGET_EXCEEDED |
| model-8 / 08:33:44.395 | run_commands (v5) | BUDGET_EXCEEDED |

各轮完整命令、原参数/Schema Hash、版本与独立停止证明见 [A 证据](../testing/evidence/native-coding-phase4/before/A.json)。

### C

| 模型轮 / 开始 | 实际工具与起始版本 | 执行结果或拒绝 |
| --- | --- | --- |
| model-1 / 08:34:13.461 | run_commands (v0)<br>search_codebase (v0) | exit=0, 声明=false, cline-86ce4e7863b6<br>exit=0, 声明=false, cline-e53257ba0785<br>SDK success<br>SDK success |
| model-2 / 08:34:26.993 | read_files (v0) | SDK success<br>SDK success |
| model-3 / 08:34:29.192 | editor /workspace/code/large_module.py (v0) | 原生编辑结果已归档 |
| model-4 / 08:34:53.444 | run_commands (v1) | exit=0, 声明=true, cline-699d94ea752c<br>exit=0, 声明=false, cline-48b397abfd1b |
| model-5 / 08:35:07.794 | run_commands (v1) | BUDGET_EXCEEDED |
| model-6 / 08:35:13.295 | run_commands (v1) | BUDGET_EXCEEDED |
| model-7 / 08:35:17.379 | read_files (v1) | SDK success<br>SDK success |
| model-8 / 08:35:21.965 | run_commands (v1) | BUDGET_EXCEEDED |

各轮完整命令、原参数/Schema Hash、版本与独立停止证明见 [C 证据](../testing/evidence/native-coding-phase4/before/C.json)。

### E-name

| 模型轮 / 开始 | 实际工具与起始版本 | 执行结果或拒绝 |
| --- | --- | --- |
| model-1 / 08:39:59.238 | read_files (v0)<br>run_commands (v0) | SDK success<br>SDK success<br>exit=1, 声明=true, cline-d8d8f78355c7 |
| model-2 / 08:40:07.167 | editor /workspace/code/transform.py (v0) | 原生编辑结果已归档 |
| model-3 / 08:40:17.779 | editor /workspace/code/transform.py (v1) | 原生编辑结果已归档 |
| model-4 / 08:40:30.577 | run_commands (v2) | exit=0, 声明=false, cline-665a1821aa53<br>exit=0, 声明=true, cline-3319239ee948<br>exit=1, 声明=false, cline-66a74c8c8bbc |
| model-5 / 08:40:55.991 | editor /workspace/code/transform.py (v2)<br>editor /workspace/code/transform.py (v3) | 原生编辑结果已归档 |
| model-6 / 08:41:11.049 | run_commands (v4) | BUDGET_EXCEEDED<br>BUDGET_EXCEEDED<br>BUDGET_EXCEEDED |
| model-7 / 08:41:18.921 | read_files (v4) | SDK success<br>SDK success |
| model-8 / 08:41:23.766 | run_commands (v4) | BUDGET_EXCEEDED |

各轮完整命令、原参数/Schema Hash、版本与独立停止证明见 [E-name 证据](../testing/evidence/native-coding-phase4/before/E-name.json)。
