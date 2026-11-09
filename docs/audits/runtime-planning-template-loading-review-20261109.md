# Runtime 解释计划与模板加载复核（2026-11-09）

本轮按用户最新要求，优先整改 Java Runtime 的取数计划生成、上下文及模板供给。客户 070200046604 的真实数据入口是已绑定模板，不能以 owner 资产未包含客户号推断数据不存在。30 次 Native 稳定性验收尚未开始，不以当前预检或单测替代。

## 真实基线

专项 Runtime 历史账户请求 `att-1-0050957d-34f6-46ae-ad89-17ab02c18b8a` 与 `att-1-850328c2-d52a-4e66-b5f7-a0029e8d2161`：规划输入均为 18,175 字符、估算 5,280 Token，实际规划模型调用分别为 46,992 ms、61,438 ms。两次均首个候选合法，不将额外经验优化调用认定为这些样本的失败原因。股票样本单工具输入 11,688 字符，实际模型调用 71,774 ms。

第二个账户请求的模板发现耗时 391 ms，回执为 `NO_MATCHING_TEMPLATE`；`configuredTemplateCount=5`、`candidateUniverseCount=0`、`returnedCount=0`、`bindingComplete=true`。这发生在模板发现阶段，客户数据查询尚未执行。当前同一 Runtime 调用者的只读模板目录可返回 631 项，不能概括为没有授权数据或没有模板。

`TemplateQueryBindingService.resolvePolicy` 先核对服务、租户、角色、用户绑定，再与 `TemplateAssetCatalogService.listAuthorizedForRoleAndType` 的当前有效模板相交。配置计数与有效集合计数不同；Publisher 的完整性判定仅比较有效集合与可用候选。空交集因此可能被标为完整，但不能据此判断客户数据不存在。仍需对照当前绑定键、角色授权及模板可用状态确定具体落差，不通过增加授权或放宽过滤绕过。

## 源码及部署判断的更正

依赖包或 class 的 Hash 不同，不足以证明运行逻辑陈旧。本轮进一步对部署中的 `AgentPlannerPromptBuilder`、`BoundTemplateCandidateRetriever`、`TemplateQueryMcpToolPublisher` 与 `6019794e2` 基线做 `javap -p -c` 比较，方法字节码一致。此前关于旧依赖造成规划慢/模板为空的初步判断已撤回；没有据此更新共享服务或重启 MCP。

## 当前整改

- 已有权威工作流且 Runtime 提供 Cline 分析能力时，使用只负责授权取数的简短规划合同；保留原始维护者系统指令，不再先拼接长篇 Coding 说明再截断系统策略。要求紧凑 JSON，数据未取得前只保留简短待取数说明，不预写业务报告。
- 保留既有工作流节点、依赖、可选工具适用性判断、原查询、预算、环境、完整调用 Schema 和 Runtime 校验。未新增 Agent Loop、任务调度器、编码策略或模型预算。
- 精简工具描述优先使用发布者已有适用性摘要，显式维护者描述仍优先；精简路径补回发布者的固定模板范围、合法过滤字段及适用/禁用范围。
- 复用既有 `ToolReviewPromptContext` 对重复大 Schema 做可还原共享，仅在实际缩短时使用；没有重复时仍输出原始合同。不会把完整 Schema 截断为摘要。
- 通用路径中，同一个批量执行 Schema 不再同时在 capability 合同和 Formal inputSchema 重复输出；保留完整 Schema 一次和原始 capability 声明。

## 量化与回归

使用真实账户请求及三项当前授权工具元数据离线复放同一配置工作流。基线 15,583 字符，整改后 14,806 字符，减少 777（约 5%）；原查询、系统策略和完整 Schema 保留，同时新增了原精简路径遗漏的固定模板范围。此为离线上下文比较，**不是模型耗时改善或业务成功的证明**。历史实际 18,175 字符包含完整 Runtime 装配上下文，不与该离线数值直接相减。

专项规划/无损投影回归 14 项通过；此前模板发布及绑定召回回归 16 项通过。扩大到 Planner/Orchestrator 后出现 82 项失败；在独立未修改 `6019794e2` 工作区复跑，失败用例集合完全一致，未将其解释为本轮全部回归通过。后续生产类变更仍须再次复核。

原先 Native 适配修复的 Node 46 项、Java 146 项及扩展 Java 104 项（103 通过、1 跳过）与 20 模块完整构建另有日志；不替代本次规划整改的部署和真实请求验证。

## 待完成

专项部署和两次真实上游探针已完成，结果见下文。模板选择合同去重已完成源码及专项回归，尚未部署或完成真实模型复测。尚不能宣称模板加载或规划延迟已稳定修复，更不能宣称达到 30/30。

## 首次真实上游探针及后续修复

`a7dacaad4` 再次完成 20 模块打包（复用此前完整前端构建并执行资源校验）。规划类覆盖已在专项 API 部署；未改 MCP、Agent 全局绑定或模型预算。扩大回归为 199 项、46 failures、36 errors；两组原有 Planner/Orchestrator 的 82 个失败名称与独立基线完全相同，新增专项测试仍通过。

真实 Runtime 任务 `ecb034e3-9d08-3024-b7ce-fbc57b0207a2`，运行 `att-1-18338022-1ce4-4fbf-9bcc-3bbbbfe18797`，采用已授权 `financial_indicator` 客户模板工作流。历史 `financial_data_analysis` 金融子工具配置的五个 `sample_*` 是市场模板，并非该客户的数据入口；没有将其改绑到其他数据源。不同 Agent 不作同口径延迟改善比较。请求模型名为 qwen3.7-plus，实际结果 metadata 模型名为 qwen3.7-flash-2026-07-15，必须保留这一区别。

这次真实链路发现 10 个客户模板候选，批量执行 4 个 API 模板，客户参数 `khh`/`CUST_NUM` 均由 user_query 绑定为字符串 `070200046604`，前导零保留；日期仍由模板默认值供给并明确标记 `REQUIRES_SCOPE_VERIFICATION`，不能冒充当前日期。供给 4 份 Dataset、58 条记录，Parquet 快照准备耗时 1,171 ms。模板发现阶段总耗时约 23,445 ms（包含其整体处理，不据此等同远端数据库耗时），API 批量执行回执耗时 2,952 ms。

任务最终 **FAILED**，错误 `Unknown Coding context field`，尚无 Native Session、Python operation 或交付物。这是 `6019794e2` 新 Native 适配引入的合同字段兼容错误：`nativeAnalysisContract` 不在既有公共上下文允许字段内。修复改用已支持的 `analysisInstructions`，首项为 Native profile，之后为按序拼接的来源及模板绑定 JSON 块；不扩大公共内核允许字段，不截断声明。补充 Native/SDK 两种模式的完整合同构造及现有严格上下文校验，Java 67 项及 Node 46 项通过。

真实事件还表明，正式 Runtime 开始之前的任务类型识别阶段约耗时 45 秒，不能全部归因于 InterpretationPlan。该分类只返回 QA/DATA_ANALYSIS，却原样携带工具执行描述；后续修改仅在分类输入保留工具标识和已声明 data_type，将完整描述留给既有取数阶段，并记录分类实际模型、提示词长度和推理耗时。分类与工作流回归 31 项通过，未改默认模型或环境预算。源码默认分类输出上限为 2048，但第二次真实探针日志确认专项环境实际配置为 **4096**，两者不能混用。

此探针与其真实失败证据单独保留，不计作正式 30 次验收成功，不重用 taskId 重派。后续版本须重新构建、部署并验证实际 Native 合同交付。

回滚仅撤回专项实例新增的规划类覆盖并重启该 API；保留旧依赖、任务、会话、回执及监控证据。不得用旧 taskId 重派执行，不修改生产默认 Coding Agent，不重启或替换旧节点。

## 第二次真实探针：确认慢点在模板选择模型

部署源码 `ef7f8b1156513ac2ec8b17f7557e08e57cc1b7e6`，Runtime taskId `55619f50-32b0-3feb-958e-c6e34e22194e`，runId `att-1-76faff62-4492-4725-8d67-589a5474ac15`。沿用同一 `financial_indicator` Agent、同一客户及原始业务请求，重新创建任务，没有重派旧 taskId。

| 阶段 | 实际证据 | 判断 |
| --- | --- | --- |
| 任务类型分类 | 实际模型 qwen3.7-flash-2026-07-15；1726 字符；9478 ms；配置输出上限 4096 | 上一次约 45 秒是分类阶段事件间隔，不能当作同口径模型调用对照，更不能用单样本证明稳定提速 |
| 初始 InterpretationPlan | 生命周期明确记录既有 fixed bound template discovery and execution plan | 此路径未出现初始 planner 模型调用，不能把通用 Planner 离线精简归因于本次耗时变化 |
| 客户模板发现及选择 | 10 个候选，选中 3 个；阶段总计 30319 ms | 包含模型选择及参数绑定，不能全部归因于 MCP 或数据库 |
| 模板选择模型 | 1 次调用，29964 ms；输入 5913 Token，输出 3139 Token；selectionDurationMs=30004 | 本次模板发现阶段的主要耗时点；Token 为模型回执用量，不等于可见 JSON 字符数 |
| 查询工具执行 | Runtime toolExecutionTimeMs=188；MCP 内部 api_template_query 日志为 34 ms | 两者测量边界不同，不互相替代 |
| API 模板执行 | 总计 13973 ms；toolExecutionTimeMs=13791 | 有实际数据执行，不是空模板；外部请求延迟仍需分别跟踪 |
| Parquet 快照准备 | 1118 ms，随后创建 Native 会话并执行 Python | 已越过前次合同未知字段失败点 |

Native taskId `analysis-coding-0285e3b082069098aeb5a36475b75db801bf3a23d771b2189d22171e76db27aa`，Native Session `b59af02f-1bc9-4add-a2f8-85b48a2ce3c1`，官方 Core Session `1794187724252_45yvx`。Python operationId `cline-b93b9a56b2e6c5c754b33a6cd0ac8e56364b978cd37caf2c0ca121c3d56bbdfd`，源码 Hash `91cc1a9cf0be386f0c30a71ba747e449e18d49db2ab02779fd703e356e28293e`。

最终 Runtime **FAILED**、Native **BUDGET_EXCEEDED**：首次 Python 实际报 ValueError（脚本第 60 行），反馈允许在当前上下文修复；后续修复 newText=19017，超过单段 16000 UTF-16 字符限制，被 `REPAIR_SEGMENT_LIMIT` 拒绝，未创建第二次 Python 执行。随后 12 次模型调用预算耗尽。ValueError 的具体业务/技术原因仍需检查归档诊断及对应源码，不能仅凭异常类型归责数据。保留真实失败，不提高预算、不放宽全部修复限制。

该任务已确认 workerStopped=true、remoteStopConfirmed=true、activeOperations=[]，取消对账完成。没有 Python 成功回执、独立验算通过或成功交付文件，不能宣称业务完成。此版本真实探针为 **0/1**，与前一版本及历史 1/13 分开记录，正式固定版本 30 次验收仍未开始。

## 模板选择合同去重及提交时状态

`SchemaDrivenTemplateParameterBinder` 只对除 templateId 枚举外完全相同的 Function Schema 分支合并枚举；保留每个模板的标题、业务描述、ID、参数约束及资产约束。参数或资产合同不同的候选不合并；模型仍决定相关性、覆盖及参数，Java 仍执行原有证据和权限验证。

新增指标记录候选数、分支数、合并前后字符数、catalog/context/function schema 长度、响应正文及参数长度。指标不记录原始业务数据，便于后续区分重复输入和模型输出开销。

以本次实际选中的 3 个模板离线复放，Function Schema 为 **4280 → 1800 字符，减少约 58%**，分支 3 → 1。这不是完整 10 候选请求的长度，也不是模型耗时、业务成功或生产提速证明。回归 44 项（Binder 18、CandidateEvaluator 26）全部通过；保留多实体、多模板、不同参数合同和不同资产不得混用的验证。

此前 `ef7f8b115` 的 20 模块 package 已成功；本次合同去重后的最新完整 package 在 API 打包阶段随用户中断，日志没有完整 Reactor BUILD SUCCESS，**不计作最新完整构建通过**。最新完整构建与真实模型复测待继续。当前仅提交源码和问题记录，不继续部署、派发任务或切换默认节点。

当前问题与后续顺序：

1. P0：模板选择模型输入/输出及约 30 秒耗时，优先完成新版本构建和真实测量；不得仅凭离线 58% 宣称已解决。
2. P0：检查该 ValueError 的真实归档诊断与源码，定位 Native 超大修复和后续预算消耗；连续同因失败时停止该场景重试。
3. P0：日期仍使用模板默认值，范围待核验；账户、多文件、行情的可信独立业务验算仍未闭环。
4. P1：历史空有效绑定与市场数据目录/读取不一致的问题继续保留；不能概括为客户无数据。
5. 回归债务：Planner/Orchestrator 的 82 项既有失败仍未解决，不报告全量回归通过。
6. 恢复债务：此前两条历史 UNKNOWN 仍需真实对账；本次任务停止已确认，不等同历史全部清零。

本地原始日志、私有模板元数据及回执保留在忽略目录 `target/native-stability-20261109/`，远端原始证据保留在 `/opt/cline-native-stability-20261109/evidence/`；凭据及客户明细不入 Git。清理工作区以提交已审核改动为主，保留审计基线和证据，不执行批量删除或 reset。
