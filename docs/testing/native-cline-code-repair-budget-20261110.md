# Native 源码修复及预算验证（2026-11-10）

最新整改版本 `82705e3a5`，不调整 Runtime 每任务模型/工具/执行/Token/时间预算。各次探针保持独立 taskId 和源码基线。

## 源码映射

`node --test test/*.test.mjs`（cline-agent-server）62 项通过，覆盖远距离头尾修改、中间不变代码、大规模新增/删除/替换、重复锚点、Unicode/CRLF、连续版本及不可唯一分段的超大改动。大段重复插入从同一边界逆序插入，保留短唯一上下文。`nativeProposal` 仅表示官方 Cline 已产生的源码变化；Runtime 使用现有 `prepareProgram` 顺序回放，验证 parentCodeVersion、baseCodeSha256 和唯一锚点，不提供修复代码。

`node --test test/code-repair.test.js test/completion-context.test.js`（coding-agent-node）10 项通过，包含真实反馈源码绑定、原始无效语法锚点、未知操作阻断和 completion 上下文约束。

## 模型反馈

有效当前 Runtime 回执才设置响应修复许可；无许可或无效许可的新错误撤销旧许可，成功响应结束连续修复序列。缺失 Token 用量不再投影为零。用量结算仍由 Java Runtime 完成，Native wire projection 不补造 Token、不自动重派未知操作。

第二条真实请求已出现 `MODEL_OUTPUT_TRUNCATED`，随后同一 Native 会话取得完整工具响应；原始模型用量及逐次回执保留服务器，最终预算对账与业务交付仍待完整任务终态确认。

首条请求三次真实 Python 进程成功均缺少必需报告，最终按 OUTPUT_CONTRACT STOP；不能称为真实 Python 异常修复成功。历史 ValueError 原因已定位，但本版本“真实 Python 异常→Cline 修复→重新执行并完成报告”仍须另行取得证据。

第七条真实 Python `TypeError / JSON_VALUE_NOT_SERIALIZABLE`（第 97 行）后，Cline 有修改源码动作，但一次未改源码重执行先在 Java Runtime `CodingExecutionFeedbackService.admit` 被 `UNCHANGED_REPAIR` 拒绝；Native 已提前记录新版本并加入 activeOperations，后续修复因此被“previous operation unresolved”阻挡。没有第二次真实 Python 执行，也没有成功报告。修复将与当前源码 Hash 绑定的 unchanged 检查前置到 prepare/dispatch 之前；测试验证不创建脚本版本、不留 active operation、真实修改仍可执行。第七条历史 UNKNOWN 原样保留，不凭空清除。

已记录的第七条 Runtime 回执：12 次模型调用，consumed.modelTokens=392841，codeExecutions=1；reserved.modelTokens=3097、reserved.cpuMillis=25000，meteringComplete=false。这些剩余保留量不伪装成零，也不能据此宣称全部计量已完成。最终逐模型回执和保留量须以本次证据快照为准。

专项编辑配置：同一固定官方 Standalone 4.1.26 / 同一 Worker 镜像，editTool 从 editor 改为已支持的 apply_patch；不改 Core，不放宽 Runtime 16000/32 补丁限制，不提高任何预算。该配置变化与源码版本一同归档，不能把跨配置探针拼成固定配置成功率。
