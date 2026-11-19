# Runtime OS 与 Cline 模型上下文优化报告

基线提交 `9fdc10b1d`。本报告记录请求入口、源码改动、固定响应夹具的前后对照，以及真实模型验收的缺口。生产历史观测单独列出。

## 模型调用清单

| 阶段 | 请求组装或调用点 | 输入与调用边界 |
| --- | --- | --- |
| Java Agent 交互入口 | `AgentChatModeHandler.resolveSystemPrompt`、`InteractionOrchestrationService`、`AgentOrchestrationEngine` | 当前问题、Agent 指令、角色与当前阶段的运行状态。分析追问把父节点材料通过独立字段交给 Coding。 |
| Java 规划、改写与工具参数 | `AgentPlanner`、`AgentPlannerPromptBuilder`、`NativeToolCallingPlanner`、`InterpretationPlanRewriter`、`PlanGapReconciler` | 规划模型需要当前目标、必选 DAG、工具契约和确有影响的观察。Runtime 已指定工具时，函数调用路径只下发指定的函数。缺失、非法和超时继续走有界恢复。 |
| 模板发现与绑定 | `AgentOrchestrationEngine`、`SchemaDrivenTemplateParameterBinder`、`ModelAssistedRetrievalBridge`、`ModelAssistedContextParameterBridge`、`EnterpriseMetadataSearchBridge`、`ModelQueryRefinementConfiguration` | 固定发布由 Runtime 决定身份，模型绑定当前请求的参数。开放发现仍需匹配。完整上游记录集通过 stepId、路径、字段和行数引用，预览只用于识别契约，执行端用授权的完整行。 |
| 工具结果审核 | `AgentOrchestrationEngine.reviewInterpretationPlanToolResult`、`SemanticClaimReviewer`、`DefaultAgentAnswerReviewer` | 现有确定性准入覆盖 Coding 负责的工具结果、符合契约的模板执行和部分终态发现。仍需语义判断的结果走原审核。 |
| 结论与评估 | `AgentAnswerFinalizer`、`AnswerQualityEvaluator`、`AnswerCriticRepairer`、`FinalSummaryWebSearchEnhancer`、`FinalSynthesisNode`、`AgentRegressionLlmEvaluator` | 正式回答、条件触发的补证和语义修复，以及离线回归评分。离线评分与正式任务分别统计。 |
| Java 分析与辅助入口 | `DataWorkspaceOperations`、`ModelNativeAnalysisHarness`、`TextPartitionExtractor`、`AdaptiveBusinessAnalysisPromptSynthesizer`、`ProblemAnalysisPlanner`、`ConversationMemoryService`、`ImageUnderstandingService` | 可按对应任务启用。这里的文本、媒体和独立辅助调用与固定模板链路分开。 |
| Skills、任务和 API 辅助入口 | `DomainSkillPlanningRouter`、`ModelSkillIntentPlanner`、`ModelDrivenKnowledgeSkillSynthesizer`、`ModelDrivenExternalSkillCompiler`、`AgentTaskService`、`AgentLearningService`、`NotificationContentFormatter`、`NotificationPolicyEvaluator`、`QuestionIdeaService`、`SqlStudioMetadataAssistant` | 部分服务直接使用独立 model 实例，主链 `MeteredChatModel` 不代表这些服务的覆盖率。 |
| Java 模型传输 | `MeteredChatModel`、`DeadlineAwareChatModel`、`CapacityGovernedChatModel`、`RuntimeStreamingChatModel` | Metered 观察逻辑调用并保留原预算估计。下层负责队列、截止时间和传输，单个逻辑调用内的传输尝试需要独立 provider 记录才可区分。 |
| Cline Native 直连 | `cline-agent-server/src/governance-server.mjs` → `model-receipt.mjs` → provider | HTTP 请求和响应使用同一 requestId，支持 Chat Completions 与 Responses，记录请求结构及供应商 usage。截断或失败且缺少 usage 时保留未知。 |
| Cline Native 业务模式 | `business-server.mjs` → `business/model-protocol.mjs` → `ClineRuntimeModelBridge.complete` → provider | Java 按授权投影消息和工具定义，持久化已取得的响应收据。现有同会话修复上限和未知执行恢复继续适用。 |
| Cline SDK 兼容入口 | `coding-agent-node/src/runner.js` → `sdk-adapter.js` → `runtime-model.js` → Java 模型回调 | SDK 的确定性压缩和当前代码引用、版本化诊断继续使用。按需读取需要本任务已授予读权限。已有 `request-context.js` 提供分段字节估计。 |

同一 Cline 请求在 Worker、Java 回调和模型收据中可能各出现一次。汇总时按同一 run 和 operationId/requestId 去重，并只在相应链路取一次 usage。代理收到的每次 HTTP 请求可单列。旧任务和没有收据的失败单列，避免把未知耗用填成零。

## 提示词与上下文核查

- `1844 → 1300`（减少 544 个 UTF-16 代码单元，约 29.5%）来自历史生产记录 `target/cline-context-production/production-proof.json`，度量对象是存储的 Java systemPrompt 字段。原分析追问将父节点资料连同包装标签纳入 Java 指令。前一轮修改把它放进 `InteractionContext.EXPLORATION_PARENT_CONTEXT`，由 `ExplorationAgentBridge`、`NativeAnalysisBridge` 和 `ClineContextAnswerService` 交给授权的 Cline 材料路径。本地历史证明记录同一问题、Cline 历史文件匹配、Silo 版本和 SHA-256 验收成功。该记录不包含完整 provider 请求，无法据此换算节约的 Token。
- 权威 DAG 场景直接构建自己的规划提示，避免先构造随后被丢弃的通用 acquisition 指令。保留其最小 JSON 示例，帮助模型生成必需字段。固定发布范围的两处工具描述同步为绑定发布者确定的 ID，保留唯一来源、有界分页、声明筛选字段和受权补证规则。
- `SchemaDrivenTemplateParameterBinder` 保留模板 ID、参数 schema、证据引用、缺失输入和默认值规则，合并重复的默认值说明。目录与上下文 JSON 每次请求各序列化一次。对于同一响应中名称及参数字节完全相同的固定绑定提案，只验证一次并生成一批执行参数。冲突、截断或内容过滤的多提案仍进入原有协议纠正。开放模板选择维持原有逻辑。
- 现有 Native 结果准入和 Cline 修复路径依靠版本、执行反馈、收据及完整交付校验。当前没有稳定证据证明再删除这些模型轮次或更早截断工具结果能维持正确性。历史上出现的规划停滞需要结合队列、首包、生成和回执状态排查。

## 请求观测

| 数据来源 | 新增或现有可用字段 | 统计口径 |
| --- | --- | --- |
| Java Agent 调用收据 `calls[]` | `requestContext`、`inputTokens`、`outputTokens`、`totalTokens`、耗时 | 结构化请求分 system、最后一条 user、更早对话及 assistant 调用、tool 定义、tool 结果。纯字符串只计 `unattributedChars`。 |
| Cline 业务模式/SDK 回调收据 `modelInfo` | `requestContext`、`usage`、provider、开始结束时间 | 在 Java 对实际提交的 SDK 请求计量。结构与授权投影完成后的请求一致。SDK 适配器下层仍可能附加传输元数据。 |
| Cline Native HTTP 收据 | `requestContext`、`usage`、`requestSha256`、耗时 | 在已序列化的 HTTP 请求计量字节数和结构化文本长度。输出与输入 Token 取供应商返回的 usage。 |
| Cline SDK 请求测量 | `measureRequestContext` 的 skills、report、code、history、task、tools | 现有分类统计采用 UTF-8 字节，保留单独的估算 Token 字段。 |

字符按 UTF-16 代码单元统计，工具定义按序列化表示计量。合入同一文本中的 skills 和 evidence 在通用收据中为 `null`，不会按标题猜测。文本统计不包含媒体的等价 Token。每段实际 Token 无供应商归因时保留未知，预算估算与 provider/SDK usage 分开保存。新收据只附加长度和计数，不新增内容日志。

## 优化前后对照

使用同一个测试方法、任务、模板、固定模型响应序列、执行契约和纠错上限。隔离 worktree 运行基线，只复制该测试文件。使用一个有效提案重复两次的首次响应，纠错响应始终返回同一有效提案。

| 指标 | 基线 | 优化后 |
| --- | ---: | ---: |
| Java 首次绑定 system 字符 | 1,319 | 1,178 |
| 首次 user 字符 | 146 | 146 |
| 首次工具 schema 字符 | 974 | 974 |
| 模型调用 | 2 | 1 |
| 累计输入 / 输出 / 总 Token（夹具收据） | 240 / 40 / 280 | 120 / 20 / 140 |
| 有效绑定、授权 ID、参数值、证据来源和原文引用一致 | 1/1 | 1/1 |

这是绑定阶段回归。Token 由同一测试模型的预设收据提供，未用于推算真实流量的节省比例。首次记录的绑定阶段本地耗时为 564 ms 和 5 ms，受单次 JVM 预热和测试顺序影响，未作为延迟收益。历史同题生产样本的绑定耗时约为 50 秒和 106 秒，无法归因到此次修改。固定真实模型与固定数据快照的端到端耗时、真实累计 Token、业务成功率和结果正确率尚未测得。

### 回归证据

- Java 定向回归共 166 项通过（Agents 66 项、Chat 29 项、API 71 项），Maven Reactor 构建成功。API 和 Chat 测试在当前工作区运行，包含前一轮探索追问的本地代码。
- 基线绑定夹具通过。优化后检查重复响应、冲突响应、截断响应、非法参数、只读数据集引用和原始异常保留。
- Java 规划检查固定模板范围、DAG 与预算。Cline 回调检查模型身份、授权工具、同会话修复协议和用量收据。API 探索检查父节点历史材料的交付边界。
- 扩大范围运行 `AgentPlannerTest` 共 37 项，出现 6 项断言失败及 3 项筛选字段空指针。独立基线 worktree 复现了完全相同的用例集合，涉及资产发现、筛选归一化、DAG 校验和已有提示断言。
- `cline-agent-server` 的 Node 套件通过 123 项，有 7 项按环境条件跳过。`coding-agent-node` 的上下文、补全和修复定向套件通过 21 项。
- 真实对照还需要两套隔离运行环境、固定测试数据授权及真实 provider 配置。当前本地 Docker Desktop daemon 无法连接。生产历史记录只作诊断证据，本次源码尚未在生产运行。

### 可复现入口

```powershell
mvn.cmd -B -pl chatchat-agents -am "-Dtest=SchemaDrivenTemplateParameterBinderTest#identicalFixedBindingProposalsProduceTheSameBatchWithFewerCalls" "-Dsurefire.failIfNoSpecifiedTests=false" test
npm.cmd --prefix cline-agent-server test
node --test coding-agent-node/test/request-context.test.js coding-agent-node/test/completion-context.test.js coding-agent-node/test/completion-evaluation.test.js coding-agent-node/test/code-repair.test.js
```

基线在 `9fdc10b1d` worktree 使用同一测试文件，并附加 `-DfixedBinding.baseline=true`。实际模型对照应固定模型及 provider profile、数据快照及黄金结果、模型参数、授权工具清单、超时、重试和 Worker 资源，记录 ID 与版本。用例应覆盖首节点和 10/20 节点分支、规划超时、无效和截断输出、取消及恢复、缺数据的部分交付、Coding 修复和产物校验。对照以完成日期、金额/行数和证据版本等业务断言判分，交付收据单独记录。累计 Token 和耗时按全部尝试统计，保留超时和缺 usage 样本。
