# Python Studio 已有代码复用：实际调用链审查

## 当前能力

| 能力 | 当前源码与边界 |
| --- | --- |
| 文件和版本 | `PythonDataScienceService.saveScript` 保存 `ds_python_script`，同时追加 `ds_python_script_version` 的正文、版本及 SHA-256；更新已有文件有行锁和 expectedVersion。目录在现有 folder 表。 |
| Cline 生成/补全 | `PythonCodeAssistantService.startCoding` 将浏览器提供的当前文件及工程文本交给 `CodingWorkspaceService`。原 `python-studio.output.v1` 只允许 workspace_read/workspace_propose、网络 NONE、maxCodeExecutions=0。语法通过不是执行通过。 |
| 修改审查 | Python Studio 已有 CodeChangeReview/PythonCodeDiff；生成结果不会自动成为保存版本。但现有生成输入是文本，不能当作已验证的持久化代码资产引用。 |
| Studio 直接执行 | `/scripts/{id}/execute → PythonDataScienceService.testScript → McpPythonControlPlanePort.preview`。不是 Cline 自动执行/修复循环。`PythonRuntimeService` 另有编辑器 ruff/旧直接 Docker 方法，本任务不扩展它作为分析执行器。 |
| Runtime 分析 | `CodingAnalysisService → CodingAgentSPI → DurableCodingTaskService → ClineCodingAdapter → 现有 Node runner/@cline/agents`，固定输入快照、已发布 Python 环境和预算。 |
| Python 执行 | Node python_execute → ClinePythonCallbackController → CodingPythonExecutionBridge → 现有 MCP PythonManagedExecutionService / PythonOperationWorkspace / PythonDockerRuntime。执行必须在契约里显式允许 CODE_EXECUTION。每轮新 Sandbox，没有上轮文件。 |
| 修复与证据 | 原 CodeHistory、Java 代码版本分配、CodingCodeArtifactStore、execution_feedback.v1、CodingExecutionFeedbackService、CodingEvidenceCollector 已提供精确父版本修复、真实异常、受限日志、结果 Schema 校验、原始输入复制检测和持久化回执。 |

## 已证明的缺口

1. 生成工作区没有自动获得执行权限；不能简单放开 workspace 工具或把生成成功当分析成功。
2. 工程多文件当前只是生成上下文。分析 python_execute 接收一份 Runtime 分配名称的脚本，Sandbox 不继承生成工作区的目录。
3. 代码资产引用未统一绑定 Studio 文件版本、个人文件资产、代码片段和历史提案；代码文本不能替代来源授权。
4. Runtime 分析路径尚未接收冻结的代码工程，不能保证优先复用原函数；代码与业务数据必须区分，不能把代码作为计算样本。
5. Studio 的旧 username 所有权与 Runtime/个人文件的稳定用户 ID 不同，必须通过已有 ObjectAccessService.legacyUser 映射，不能相信请求自报 owner。
6. 现有 Python Studio 同环境内文件名唯一，目录只是组织结构；同名文件跨目录的回写不能静默覆盖。执行工程可以保留目录，但遇到现有管理结构无法表达的回写应拒绝并保留提案。

## 最小实施边界

- 代码和版本继续由现有 Studio/个人文件/工作区提案维护。代码引用快照和执行提案放现有 Runtime Evidence/Checkpoint，不新增文件管理库或监控库。
- 保持原生成契约零执行预算。分析另行明确授权，复用现有分析契约、Cline runner、Python Sandbox、预算和错误反馈。
- 多文件只做执行输入适配：保留独立文件正文、相对路径和 SHA；在现有单脚本执行契约里使用 Runtime 管理的工程打包适配程序，所有实际运行仍在原 Sandbox。打包适配不包含业务函数映射，不分析数据，不作为第二执行平台。
- Cline 针对原工程提交复用或精确文件修改，保留每轮工程清单；现有代码生命周期仍绑定实际执行程序哈希。源码提案与执行适配程序明确分开，不把适配程序 Diff 冒充用户源码 Diff。
- 业务数据只来自已授权不可变数据快照；代码读取不授予业务 MCP、网络或额外文件访问。
- 回写锁定所有源文件并检查原版本/摘要，整组验证后才保存；未确认或冲突不覆盖源工程。执行结果与业务校验状态独立显示。

本文是源码审查记录，不是现场验收证明。真实模型、Sandbox、修复、取消和业务数据验证需分别留证；受控样本不得称为真实客户数据验收。
