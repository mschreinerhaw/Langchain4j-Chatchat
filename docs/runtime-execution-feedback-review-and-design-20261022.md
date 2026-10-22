# Runtime OS 执行反馈闭环：审查与实施设计

## 1. 实际链路与已有职责

Java `CodingAnalysisService` 创建不可变任务合约；`DurableCodingTaskService` 在现有 PostgreSQL Runtime checkpoint/journal 中管理权限、预算、租约、fencing、操作意图和事件。`ClineCodingAdapter` 向 Node `/v1/runs` 委派任务。Node `runner.js` 在同一 `@cline/agents` 会话内调用 Java `execute-python/get-python`，Java `CodingPythonExecutionBridge` 再调用 MCP 的受管操作接口。`PythonManagedExecutionService` 持久化操作并创建既有 Docker Sandbox，Python `_runtime_capture.py` 包装子进程。停止证据、回执和产物 manifest 回到 Java；`CodingEvidenceCollector` 校验输出契约，再决定是否登记结果。Node 只有 Java 回调工具，没有 Docker、数据库、密钥或安装依赖权限。

已有机制可直接复用：代码 SHA256 与局部 edits 修复、操作 ID 幂等、输入版本冻结、模型/执行预算、任务截止时间、取消与停止证据核对、SDK checkpoint/continue、用户资产 Silo 归档、PostgreSQL 事件、Task 监控及 Node 交互监控。不得另建 Agent、调度器、事件数据库或沙箱。

## 2. 已确认差异

| 项目 | 现状与缺口 | 复用及改造位置 |
|---|---|---|
| 错误返回 | Python/MCP 已产生固定诊断码及提示，但 Java `diagnosticMetadata()` 二次过滤，只留下异常类型/行号；上一轮修复未贯通至模型 | 在公共反馈契约统一读取受限诊断，贯通 get-python 返回 |
| 执行结果 | 分散的 Map 包含进程状态、退出码、codeHash、manifestHash；耗时、阶段、修复策略、上下文关联缺少统一表示 | 保留旧 Map 字段，增量追加版本化 executionFeedback |
| 修复 | Node 会把 tool receipt 放回同一会话，支持 baseCodeSha256+唯一锚点 edits；没有明确的 Java 分类/独立修复次数决策 | Java 原任务 journal 记录反馈和尝试，执行前检查修复上限；继续使用原 SDK 会话 |
| 完成 | 已校验必需文件、JSONL、schema、引用与源数据复制；进程成功不等于任务成功 | 保留 EvidenceCollector 为结果事实权威 |
| 日志 | Java 有局部异常 warn，Node 有操作事件；缺少跨链路的稳定结构化关联与终态反馈日志 | 现有 SLF4J 输出 JSON 元数据；完整反馈存在原 journal/资产，不打印客户数据 |
| 持久化 | MCP 操作表、Runtime journal、代码证据、SDK checkpoint、RuntimePythonAssetArchiver 已有；原始流因数据边界明确丢弃 | 不恢复原始业务 stdout/stderr；保存脱敏技术日志、定位栈与日志引用；stdout/stderr 明确标记已脱敏 |
| 监控 | 已有代码版本、事件、阶段；修复次数按版本数推算，缺少错误分类、执行耗时和反馈终态 | 从 Java 权威反馈投影执行/修复时间线，保留原 Node 交互展示 |
| 性能 | 初始上下文/文件描述按会话加载，修复支持 edits；每次工具调用都做新沙箱；状态轮询可能反复取回执/登记校验 | 缓存已核验的终态反馈，重复轮询不重复下载/校验；不复用脏容器。授权文件跨修复按不可变引用复用 |

## 3. 统一反馈契约

采用 `runtime_os.execution_feedback.v1`，作为现有 get-python 回执的新增字段，旧字段保留。Java 公共 record、JSON schema、Node TypeScript 类型与 Python 诊断版本共同约束。标识包括 taskId、executionId（既有 operationId）、attemptId（执行序号）、traceId（既有 runId），保留 codeHash、输入快照/Schema 引用、输出与校验引用。包含进程状态/退出码、执行耗时、错误类别/固定安全消息/定位栈、stdout/stderr 脱敏表示、验证状态及 Java 的修复决策。

不把自由文本异常、字段值、源码内容、客户记录或凭据复制进反馈、事件、服务端日志。异常消息采用与真实异常匹配的固定技术说明；保留异常类型及受限主脚本帧，完整脱敏详情沿用任务资产归档。授权 Schema 引用提供上下文，不扩大输入权限。

## 4. 状态与错误策略

继续使用任务及操作现有状态机，增加反馈阶段：EXECUTING → EXECUTION_FAILED / RESULT_VALIDATION_FAILED / VALIDATED。Syntax/Indentation、KeyError/IndexError、常规程序类型/API 错误和输出契约错误允许同任务修复。缺依赖明确禁止安装，可改用已发布环境中的可用包。权限拒绝、取消、超时、OOM、环境漂移、未知停止状态、数据/授权或预算失败禁止自动执行下一版；未知状态先走既有 reconciliation。

修复次数由 Java 策略配置上限、原任务 maxCodeExecutions 和剩余预算共同限制；不会新建预算。重放同一个 operationId 不消耗新修复序号、不启动第二个容器；不同新版本仍需既有 reserve/startIntent。截止时间、Token/CPU/内存/产物预算继续由 A2/MCP 负责。错误反馈不会变更模板流程、授权参数或文件集合。

## 5. 分阶段实施和验收

1. 补充公共契约、脱敏诊断/定位栈和 Java 分类器，先修通实际信息丢失断点。
2. 将反馈和执行尝试写入原 journal，Java 服务端结构化日志，记录代码提交、执行、反馈与修复限额；接入已有资产归档。
3. Node 在原工具结果中接收反馈、遵守 Java 决策、优先 edits；检查点保存修复状态，前端投影执行时间线。
4. 执行本地单元/协议测试及真实 Python 子进程测试，随后在线真实 Sandbox + Java + Node/Cline 集成测试。Mock 仅证明分支规则，不能算真实集成验收。

验收按用户列出的 14 项逐项记录真实结果，包含语法、字段、缺包、资源/权限/超时、退出 0 但契约失败、修复耗尽、进程重启、幂等副作用、时间线/日志、并发隔离、隐私、既有工作流回归。未实测项明确保留，不以推断或 Mock 代替。性能比较记录重复回调的产物下载/校验次数、上下文加载次数、修复生成量与真实耗时；模型耗时波动不得归因为本次改造。
