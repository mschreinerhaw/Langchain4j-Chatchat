# Cline 上下文与完成度改进

基线为 `bf23fdda2`。本地已确认 `3be0afab6` 是当前 HEAD 的祖先；因此“main 仍停留在 10 月 30 日”不适用于本轮源码。保留 Cline SDK 0.0.93、既有任务与代码生命周期，不改 Skills 冻结实现。

## 完成度与验证范围

Java 分析目标与 Node 系统提示明确要求：只按用户请求的交付项及显式约束判断 COMPLETE / PARTIAL / INPUT_REQUIRED。无关的趋势、期间、税费或外推限制不能单独把已完成请求降为 PARTIAL；降级必须指出具体未完成的请求及其证据缺口。没有删去字段语义、权限或引用校验，也没有让 Runtime 通过关键词强制把模型报告改成 COMPLETE。

这属于未来任务的提示规则纠偏，不追溯改写历史报告；模型能否持续正确遵守仍需实模回归。执行成功、产物有效、业务已核验、需求完成保持独立事实：SUCCEEDED / VALIDATED 的执行或存档回执不证明业务正确，INCONCLUSIVE 不单独证明需求没完成。既有状态字段及前端兼容协议没有迁移。

2026-11-03 联机复测又确认 `VisualReportArtifacts.collect` 会把任意程序警告混入展示降级条件。现已区分图表缺失等真实展示问题与程序声明的数据限制：后者保留在 validation/warnings 中，不单独将 SUCCESS 改为 PARTIAL。新增可选 requirementStatus 原样记录报告行的 COMPLETE / PARTIAL / INPUT_REQUIRED；明确不完整的需求不会因产物有效而升级，计算失败、未决验算、缺少检查及无效图表的既有处理保留。此修复没有新增业务语义模型调用。

## 源码按需读取

`sdk-adapter.js` 原来在 SDK 压缩后每轮追加最新完整代码。现在仅在明确授权 `code_read`、单程序代码生命周期已启用、当前完整报告及引用有效、当前版本已 VALIDATED 且视觉验收未失败时，省去这份额外源码注入，保留版本、codeHash 和 lastValidatedHash。原始历史消息可能仍含源码；本次没有删除规范会话历史，也不声称从整个请求消除了全部旧代码。

需要检查列选择或精确修复时，Agent 可调用 `code_read(codeHash)`。它只读当前任务内存中的当前程序，拒绝过期哈希，不接受路径，不读取业务数据，不执行 Python，不绕过任何 Java 回调权限。读取后恢复完整源码注入；后续执行清除该读取状态，新的合格报告再次进入轻量审核。不能因为省去源码而跳过必要的源程序审查。按需读取可能增加一个工具回合；这项改动不保证每个任务的总 Token 或耗时都下降，需用固定样本做实际对照。

执行失败、报告引用无效、项目型 Python 输入及没有新授权的旧任务继续获得原有完整源码。恢复时缓存未重建会走保守路径；代码历史、哈希和精确修改机制不变。

Java 只对启用代码生命周期的单程序 reviewed analysis 授权新工具；Node 显式声明能力，Java 在初始派发和恢复前检查 worker 协商结果。新 Java 必须配套新 worker；旧 worker 不支持时会在派发前明确拒绝，而不是让模型中途调用未知工具。旧任务不增加新工具，不要求迁移历史快照。

## 请求开销与预算

压缩前从软额度预留可能重新附加的当前程序及完整报告空间；保留材料仍可能超过额度，不能通过截断证据或源码制造“达标”。模型流调用入口、SDK 完成准备和附加材料后计量，随既有 `MODEL_TIMING` 事件记录以下字段，不增加一次遥测回调：

- system、tools、task、skills、code、report、history、envelope 的序列化字节数。
- 总请求字节、按 UTF-8 字节除以 4 的输入 Token 估算。
- 有软额度时记录 softInputTokenBudget 和 exceedsSoftBudget。

计量对象是最终 SDK 请求投影，不是 Java 转换后的提供商 HTTP 请求。分类按消息部件归属，初始 task 配置中内嵌的技能目录仍计入 task；skills 表示按需技能调用及结果，code 包括 Python 源码/修复调用。Python 回执中的 reviewedArtifact 作为 report，其他执行诊断留在 history。它是可解释的开销归因，不是精确的提供商分词或计费拆分。各项字节含 envelope 可加总，逐项向上取整的估算 Token 不保证可加总。

MODEL_USAGE 继续保存实际提供商输入输出用量；估算没有替代真实用量。软额度超限是观测信号，不因粗略估算直接中断合法任务；Java 保持累计预算、实际调用次数及授权准入的最终责任。没有新增模型调用做摘要或性能分析。

Node 监控与 Java 持久化只白名单保留有界数值、估算器标识和超限布尔值，不保存源码、提示或业务数据。遥测失败不授权操作，也不阻断既有模型调用。

## 固定回归与观察门禁

样本在 `coding-agent-node/test/fixtures/analysis-regressions.json`，均为合成数据：

| 样本 | 目标及独立预期 | 完成状态 |
| --- | --- | --- |
| simple-sales-csv | 5 行；金额 43.75 元、销量 10、区域缺失 1；缺失检查、区域汇总及图表，不要求时间或税费 | COMPLETE |
| multi-file-explicit-join | 显式客户键；4 笔订单、金额 20.00、未匹配 1 笔 4.40；不得丢弃未匹配记录 | COMPLETE |
| exact-repair-preserves-results | 精确修复标题语法；保留已验证汇总 30.50 与父版本绑定 | COMPLETE |
| account-missing-semantic-definition | 2 行账户结构；金额、币种、时间语义和风险规则缺失，不得编造净流入或风险图 | INPUT_REQUIRED |

initialBrokenSource 保留为小型锚点单测；sourceFixture 指向完整的 `repair-analysis.py.txt`，通过正式 Python Studio 代码复用接口冻结版本、授权执行。联机记录包括真实 SyntaxError、父版本哈希、单行引号修复、实际读入 CSV 的 Decimal 汇总和已归档图表，没有拿片段充当已执行程序。

`scripts/check-analysis-regression.mjs` 检查实际观察的规范化摘要。输入是四个对象组成的 JSON 数组，每项需包含：caseId、taskId、reportSha256、taskStatus、codingStatus、analysisStatus；deliveryChecks 为 `{id,passed,evidenceRef}`；numericalChecks 为 `{id,actual,evidenceRef}`，actual 使用样本声明的小数字符串；measurements 包含 durationMs、modelCalls、inputTokens、outputTokens、pythonExecutions。交付项与数值检查的 ID 使用样本定义。缺任务、缺证据、错误完成状态、错误数值或未知用量均不通过；未知数值不能伪装成 0。

```powershell
cd coding-agent-node
node scripts/check-analysis-regression.mjs observations.json
```

观察摘要必须来自实际报告、独立计算及原始运行事件；脚本只校验摘要的一致性，不证明引用真实存在，也不替代语义审查。门禁单测中的 fixture 记录不是实际模型验收。现已完成四类实模业务验收，结果见[联机验收记录](cline-context-completion-20261103-evidence/acceptance.md)。expectedTaskStatus 明确区分普通任务 SUCCEEDED、缺少请求所需语义的 PARTIAL，以及 Python Studio 任务 COMPLETED；不能把这些不同通道和完成范围强行统一成 SUCCEEDED。

## 验证与交付边界

Node 构建及 **162 项**适配层测试通过；Java **124 项**不同的定向测试覆盖分析契约、模型工具授权、worker 能力协商、恢复、报告结构、回答状态及遥测白名单。最终计量/准入改动后又复验其中 **17 项**回调测试，不重复计入总数。CLI 负向检查确认缺少四项实测观察时全部拒绝。各类结果、日志摘要及源文件指纹见[测试记录](cline-context-completion-20261102-tests.json)。

后续联机修复后，Node 在本地及部署镜像内各 **163 项**通过，Java 新增 **55 项**专项回归通过（与首轮 124 项中的 27 项重叠）。离线 worker 镜像模板补齐 Skills 目录、原生发现链接和构建期完整性检查。最终部署为 API `cline-context-completion-20261102-r3`、worker `cline-context-completion-20261102-r2`。四类业务观察已通过门禁，原始报告和图表哈希、实际 Token、历史报告只读重验及健康检查均已记录。没有测得受控前后性能改善比例，也没有宣称全仓回归通过。
