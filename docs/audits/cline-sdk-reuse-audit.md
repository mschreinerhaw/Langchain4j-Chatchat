# Cline SDK 公开能力复用审查与实施

审查日期：2026-11-05。固定 main：`0693c15fd9184f112d0111f3085b16943841a72d`。
整改分支：`refactor/cline-sdk-reuse-20261105`；生产代码提交：`3e28607b9`。
以下结论基于当前调用链、安装包实测及本轮验证，不把 10 月文档中的建议算作当前缺陷。

## 版本与迁移决定

`coding-agent-node/package.json`、`package-lock.json`、本机安装目录以及部署主机运行镜像中的
`@cline/agents`、`@cline/core`、`@cline/sdk`、`@cline/llms`、`@cline/shared` 均为 **0.0.93**。
锁文件包含下游依赖的完整版本和 integrity；没有修改锁文件或混装 Cline 版本。
`npm.cmd view @cline/{agents,core,sdk} version` 分别返回 **0.2.0**。

本轮保留裸 `@cline/agents` 执行入口。当前版本已经提供所需的 Editor、工具错误反馈、
prepareTurn/basic compaction 和 snapshot/restore，重复部分可以直接收敛。
[0.2.0 发行说明](https://github.com/cline/cline/releases/tag/sdk/sdk/v0.2.0)
涉及 Hub 会话重新连接、重试和压缩估算变化，但没有证据表明解决本轮问题必须整体升级。
不将官方 main 当作 0.0.93 的已发布功能，也不以 Core 迁移作为交付前提。

## 实际调用链和治理边界

| 路径 | 当前职责及审查结果 |
|---|---|
| `CodingAnalysisService.executePrimaryAnalysis` → `CodingAgentSPI` → `ClineCodingAdapter` | 生成授权输入、输出契约和固定预算；dispatch/poll 处理工作进程与恢复，不生成修复代码 |
| `server.httpGateway/createCodingServer` → `runner.createCodingRun` | 校验入口、签名回调、fencing、工具白名单；建立一个 SDK Agent |
| `createRuntimeModel.stream` → Java `ClineModelCallbackController.model` / `ClineRuntimeModelBridge` | 使用平台模型连接，每次实际 transport attempt 逐次准入和结算 |
| SDK `python_execute` → `CodingPythonExecutionBridge` → MCP Python Sandbox | Java 分配脚本名、操作身份及隔离执行；未确认停止不发布结果，也不盲目重放 |
| `CodingExecutionFeedbackService.finish/admit` → SDK `afterTool` | Java 决定技术修复是否允许；SDK 获得 `isError` 和真实反馈，继续同一逻辑任务 |
| `CodingCodeArtifactStore.commit` / `CodingEvidenceCollector` | 不可变代码 hash、版本、结果结构和证据；SDK completed 不能单独证明交付成功 |
| `DurableCodingTaskService` / `ClineCodingAdapter` 恢复路径 | 保留 Java 任务权威、租约、预算、未决操作和终态保护；SDK restore 不重置平台计费 |

`completion-context.js` 仅提供执行事实和预算提示；`request-context.js`、`context-budget.js`
只测量请求/估算上下文，不替代供应商用量或准入。
`skills.js` 使用公开 `parseSkillConfigFromMarkdown`，按任务目录发现/加载，并保留来源 hash、
路径/符号链接校验。无需引入第二套 Skills 注册中心。

## 本轮变更

1. `sdk-adapter.editProgram` 删除调用自研 `prepareProgram` 预演并比较结果的步骤。
   SDK Editor 原本会按已有文件行尾转换补丁，预演却以原始字符串匹配，导致合法 LF 锚点无法修改 CRLF 源码。
   现在替换和唯一性判断由已安装 SDK 完成；Runtime 仍校验父 hash、大小、取消和固定临时路径。
2. `python-project.proposeProject` 删除第二份 `split/replace` 编辑算法，复用同一个 SDK 编辑入口。
   改为异步并传递取消信号；受控空文件初始化保留。路径、大小、重复名称、adapter 不可修改等约束继续执行。
3. `runtime-model.stream` 对 Runtime 授权的截断反馈保留 `max-tokens`，
   `runner.completionGuard` 不再为 native 模式重写 SDK 的响应修复提示。
   每次重试仍通过实际 stream 的预算边界，`errorRetryable:false` 保留。
4. `CodeHistory` 不再保存或重新输出 `protectedFragments/forbiddenFragments`；
   单版本旧片段压为 `editCount/patchHash`，仍读取旧检查点的版本/hash/验证状态。
   没有删掉当前代码、最近已验证版本、父链、rollback 验证或 Java artifact。
5. `runner.forward` 隔离普通进度回调失败，保留本地 `TELEMETRY_DELIVERY_FAILED` 记录。
   模型/Python 准入、结算、检查点和最终结果仍是必需回调，不能被该容错吞掉。

## “SyntaxError 后修复计数为 0”的生产证据

读取本轮部署前的 monitor 记录：

- `analysis-coding-c43d7ccb3fbde7d982dfa8deee243731381b06b89cca90dd83adba9373c42980`：
  Python 首次失败为 `SYNTAX_ERROR`，`repairAllowed=true`、`repairAttempts=0`；
  随后出现 `CODE_PROPOSAL_REJECTED / CODE_PROPOSAL_INVALID`，下一次模型请求超时。
  没有第二次已执行的 Python，因此计数没有增加。不能据此认定 Java 将 SyntaxError 错误 STOP。
- `analysis-coding-f87a9ecd47adddaa0d6097f765b76cbe0c657ce9d97655e8a47c23a0cf03c315`：
  `SYNTAX_ERROR → NUMERIC_TYPE_MISMATCH → SYNTAX_ERROR → PROGRAM_ERROR`，实际修复计数为 0、1、2、3，
  最后不允许继续，符合修复额度上限；这也说明字面版本链不能证明语义回归。

这两条记录支持“提案失败、模型超时、多轮代码回退是不同问题”。
旧日志不能进一步证明第一条提案为何失配；CRLF 拒绝问题由独立可复现用例确认，未冒充该任务的根因。
本轮配对联机任务另行验证当前 SyntaxError 分类和原生修复链。

## 计划、影响和剩余边界

实施顺序为固定基线/独立分支 → 本地行为基线 → 最小生产改动 → 隔离 Node/Docker 门禁
→ 只替换 Worker → 签名回调及同源真实模型复测 → 证据与 PR。
无 Java 生产代码、数据库迁移、前端、模型配置、Agent 配置、MCP 权限或业务数据改动。

仍保留 `prepareProgram` 作为旧非 native 模式和旧检查点读取的兼容路径；删除条件见
[整改台账](cline-redundancy-removal-matrix.md)。SDK 精确锚点仍会拒绝歧义和猜测，不保证任意模型提案都成功。
Core 持久会话 PoC、全面 SDK 升级和异步事件批处理没有进入生产入口。
同步遥测失败已隔离，网络超时前仍可能增加等待。
canonical transcript 和代码仍有界内联保存，超大多轮任务的检查点容量上限仍需专项迁移验证。

测试命令、结果和范围见 [回归报告](../testing/cline-sdk-native-regression.md)，
实际配对指标见 [性能报告](../testing/cline-sdk-performance-comparison.md)，
上游版本/Issue/PR 状态见 [上游调查](cline-upstream-issues-prs.md)。
