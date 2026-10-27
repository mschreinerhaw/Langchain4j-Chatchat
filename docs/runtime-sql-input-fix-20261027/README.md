# SQL 输入到 Coding 任务的契约修复

基线：`425797114`，工作分支保持原分支。用户引用的 `86d496089e34f12b8c7358e99d1cb85092c8bc4d` 是本地历史祖先，包含同一通用校验异常。

## 已复现的根因

`InteractionWorkflowCoordinator` 的文件分支依据真实附件和模型计划分派任务；普通 `llm_chat` 则经 `LlmChatModeHandler` 直接调用 `ClineContextAnswerService`，并不经过该 Coordinator。因此只修 Coordinator 会遗漏聊天直接输入 SQL 的入口。

旧 `ClineContextAnswerService` 把问题、SQL、会话摘要及上下文整体序列化后拼入 `instruction`。`CodingAnalysisService.executeObserved()` 对其调用 `text(...,16000)`，任何字段不合法均抛同一句异常。

在隔离的原基线运行真实旧转换方法和旧校验器，30 字符 SQL 加 20,000 字符会话摘要得到：

```text
SQL_BASELINE_REPRO field=instruction sqlCharacters=30 instructionCharacters=21669
maximum=16000 requestIdValid=true datasetReferenceValid=true
```

旧 `text(instruction,16000)` 确实抛出 `Bounded Coding parameter required`；同次请求的 requestId 和数据引用通过原校验。因此已证明存在 instruction 超长的具体触发路径，不能将所有截图中的同名异常一概归因于缺少 datasetReference。旧服务对所有上下文回答也发布 FILE_ANALYSIS 阶段，不能凭该标签证明实际绑定了文件。

## 修改

- `ProblemAnalysisPlan`：增加可选、可序列化的 `sqlIntent`，保留旧构造方法兼容。
- `ProblemAnalysisPlanner`：只以源代码语法识别 SQL 输入候选；解释、静态校验、执行意图由同一次模型规划返回。精确 JSON 示例和约束均包含 sqlIntent；纯 SQL 默认静态校验，数据源不是静态校验前提。
- `InteractionWorkflowCoordinator`：复用一次计划，解释/校验走现有回答路径，真实查询走现有受控执行边界；没有数据时返回 INPUT_REQUIRED。
- `ClineContextAnswerService`：补齐绕过 Coordinator 的 llm_chat/role_chat 入口；已有计划不重复规划。完整问题和上下文保留在不可变输入，instruction 仅保留有界预览与输入定位，不截断原 SQL。根据运行 ID 和输入种类生成稳定合法的 Coding requestId。
- 真实附件必须通过所有权、文件指纹和已接受的绑定关系验证；源代码/上下文显式标记 `isBusinessDataset=false`，不伪造查询结果或业务测量。上下文回答不获得外部 `data_request` 权限。
- `CodingAnalysisService`：保留全部长度、授权和 Schema 校验，错误明确指出字段、原因和长度；数据引用缺失使用 `CODING_DATA_REFERENCE_REQUIRED`。未删除 Coding 参数校验。
- `RoleChatModeHandler`：已有 INPUT_REQUIRED 等结果不再被“存在回答文字”覆盖成成功。任务公开结果保留 workflowOutcome、sqlIntent 和 codingRequestId。
- 发布 `SQL_INTENT_IDENTIFIED`、`ANALYSIS_TASK_BUILT`、`CODING_REQUEST_VALIDATED`，后续复用输入快照、Cline、Python 和结果验证事件。纯 SQL 不再伪装成文件分析阶段。

## 完整链路

```text
聊天任务（认证用户、request/attempt/run ID）
  → Coordinator，或 llm_chat/role_chat 的 CodingAnswerPort
  → 现有 ProblemAnalysisPlanner（一次模型意图识别）
  → EXPLAIN / VALIDATE：真实源代码上下文；EXECUTE：真实附件或已授权供数
  → Java 构建稳定 requestId、instruction、输入引用、输出 Schema
  → CodingAnalysisService 校验并捕获不可变输入
  → DurableCodingTaskService / 原 Cline SDK
  → sql_validate（SQLGlot 静态校验，不执行数据库查询）
  → python_execute（现有无网络沙箱，真实操作回执）
  → artifact_read、结果 Schema、产物哈希和授权引用校验
  → 用户报告
```

SQL 请求执行但没有数据时，在规划/数据绑定处返回明确补充输入结果，不提交 Coding，也不产出虚假分析数字。执行外部数据库仍须使用既有已授权数据源/模板；选择能力不等于授权，未新增绕过权限的直接 SQL 通道。

## 自动化与真实验证

- Java 定向回归：175 项通过，覆盖上下文超长、真实附件、SQL 模型计划、字段错误、幂等快照、数据权限、普通问答与任务结果传播。最终规划提示词及缺少 sqlIntent 的拒绝行为另经 72 项相关测试通过。
- 隔离旧版本复现：原参数校验真实抛出目标异常；SQL 和 requestId 未伪造。
- `scripts/verify-runtime-sql-input-stage.py`：通过正式登录、上传和任务接口验证，使用可观察的真实 Cline/Python 回执。上传 CSV 是明确标注的合成测试文件，非生产业务数据。
- SQL 解释：任务 `4f89587c-1c62-3250-83c7-6171dbdf84c7` 成功，1 个 Coding 任务、1 次 Python 执行，exitCode=0，执行器停止、容器已回收。
- 非法 SQL：任务 `67b35eb1-1bc8-3c38-8929-38076b7e1f06` 返回明确 INVALID/SYNTAX/ParseError 报告；实际调用 sql_validate，SQLGlot 30.23.0 拒绝语法，随后 1 次 Python 生成验证报告。任务成功交付错误诊断，不表示非法 SQL 被执行。
- 无数据执行：任务 `51403a3f-6a88-36ca-953c-e6d4d9dfa6ce` 返回 INPUT_REQUIRED/PROBLEM_ANALYSIS_CLARIFICATION_REQUIRED，明确要求真实文件或数据源，0 个 Coding 任务、0 次 Python。READY 的 EXECUTE 计划缺少数据时，自动化测试另验证 SQL_DATA_SOURCE_REQUIRED。
- 纯 SQL：任务 `b1f0c95b-ef48-36d3-9775-6a18842fe01a` 在最终规划提示词下成功。

真实文件查询的首个测试已到达 Python 并以 exitCode=0 结束，但后续模型回调失败，未取得最终成功报告；该失败记录保留，并通过正式接口取消未结束的父任务。

随后只通过正式 `/agent/tasks/{taskId}/retry` 接口恢复一次，原请求、授权文件和业务语义不变：任务 `56d610a8-d065-4389-aefa-252b42c3ccb5`、attemptNumber=2 成功。该尝试为 1 个 Coding 任务、1 个 Python 程序版本、1 次真实执行，Cline 有 2 次模型调用（生成及执行反馈）。Python 实际使用 SQLite，包含用户的 `SELECT SUM(amount) ... FROM input_csv`，程序哈希与执行记录一致，结果报告为 `3.75`。exitCode=0，执行器停止；结果产物和授权引用通过既有交付校验。业务独立校验状态保持 INCONCLUSIVE，不冒充自动业务正确性认证。控制证据见 `file-retry-verification.json`。

尚不能用本报告替代“任意外部数据库 SQL 已授权执行”的真实集成证明。已授权能力的分派已有自动化覆盖，既有模板/权限网关保持不变。
