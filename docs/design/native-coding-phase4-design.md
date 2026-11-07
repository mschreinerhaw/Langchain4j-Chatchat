# Phase 4 P0 最小整改设计

依据先行审计 `0be922534`，先限于 A、C、E NameError。暂停 MCP、Skills、语言、工作区限额和业务组件开发。

## 数据来源与状态

不建立验证存储：继续使用 Java/Silo 原始 `native-last-verification`、`native-last-execution`、immutable CODE/工具回执和 manifest。已有普通检查不覆盖声明验证、后续声明失败使旧通过失效、源码变更拒绝旧验证的机制保留。

新增纯事实投影，经已有 SDK prepareTurn/technicalMemory 给模型：taskId、验证 codeManifestHash、当前 manifest、executionOperationId、原 commandHash/策略/声明身份、exitCode、resultAvailable、executorStopped、stopProofRef、verificationStatus。PASSED 必须匹配当前版本、声明、可用结果及独立停止；失败/缺结果保留真实状态；不同版本标 STALE，绝不自动选择历史版本交付。失败诊断只保留有界原始尾部及原文 Hash，不生成摘要或正确实现。

声明验证状态与独立验收、SDK 完成、Runtime 交付分开。独立验收继续 NOT_EVALUATED，不把一次执行成功转成项目成功。模型仍可继续编辑、检查或正常给出最终回答，completionGuard/Java 最终准入仍核对原始回执。

## 原预算可见性

复用 prepareTurn 已调用的 budgetSnapshot，不增加预算调用链或推理。将已存在的剩余模型调用、Token、时间及 chargedUsage.codeExecutions 与合同原 maxCodeExecutions 做有界投影。缺失/不可计量字段用未知，不用零冒充。标明 SDK 正规化后的每条命令各占一次 Sandbox CODE；不改变计费单位、并发装配、SDK Schema 或执行上限。

## 恢复与兼容

准备或重载工作区时，同时从 Host 重读 lastExecution 和 lastVerification，不能混用旧本地验证与新 manifest。SDK 原 checkpoint/restore/continue 和原生 compaction 保留；验证投影不成为新的授权依据，也不通过 checkpoint 虚构停止证明。

P0 实现集中于 Node 原生 v2 profile 的事实投影及重载，保留旧业务和旧 native profile 语义。SDK prepareTurn 只将已有 budget 传给可选 technicalMemory 回调；既有零参数回调继续工作。SDK/vendored、Java 修复决策、Sandbox、模型、预算和测试合同不修改。

## 验证顺序

1. 安装版 SDK 回归先复现：完成验证后继续检查/压缩，模型请求缺声明验证事实；编辑后请求缺 STALE 状态。
2. 最小修复后覆盖成功、失败、缺结果、缺 stop proof、过期源码、恢复/重载及原预算余额；不得让测试内异常被 SDK failed 状态隐藏。
3. 同 SDK/模型/参数/原数据/预算/Sandbox，只替换隔离 Worker 候选镜像，先真实运行 A、C、E NameError。
4. 真实结果与原始停止、源码 Hash、归档再读取单独验收；保留全部失败。三项未通过则不推进 P1，也不以加 Prompt 规则、扩大预算或手工代码补丁优化通过率。

回滚候选前先停止新准入，核对全部模型/工具/CODE 独立停止后恢复旧 Worker；保留原 Silo 与失败证据。只改变模型可见事实，不能在旧执行状态未知时换 Worker。
