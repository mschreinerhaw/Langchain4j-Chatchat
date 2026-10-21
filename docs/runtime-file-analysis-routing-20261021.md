# Runtime OS 文件分析路由纠偏

分支：`fix/runtime-file-analysis-routing`。检查时工作区干净，基线为 `24ee1dd9614ef0df499a72e11e3e51951832e4ba`。本次没有修改助手的必选工具配置、权限或受控模板，没有新建文件分析引擎。

## 根因和修改

1. `ChatAssistantView.requestAssistantAnswer` 在普通报告模式下，把附件交给同步 `/interactions/user-assets/analyses`，等待最终收据后才设置任务 ID。这个接口直接调用 Coding，观察者为空，前端收不到 Agent 任务的真实执行事件。图文模式则走异步 Agent API。现在交互式附件统一走原有 Agent Task API、事件流、结果恢复与失败处理；保留旧同步接口以兼容其他调用方。
2. `AgentChatModeHandler` 原来看到附件就直接进入 Cline，既不判断业务依赖，也不执行规划。现在先解析登记且授权的文件，再使用现有问题规划器生成任务及数据源依赖。独立文件任务进入已有 Cline 路径；需要业务数据的混合任务进入原有受控工作流。
3. 受控分析原来只投影工具结果。文件 ID 又会被内部参数过滤器排除，混合任务不能可靠把文件交给后续 Coding。现在显式传递从已接受计划提取的文件 ID，在原有分析入口合并授权原文件及计划上下文，并在业务数据缺失时按依赖拒绝或继续。
4. `ClineContextAnswerService` 原来先读取附件，随后才启动 Run、进入异常处理。现在先建立并校验 Run，再准备文件；读取异常会结束 Run，输入句柄在统一的 finally 中释放。文件准备、Cline 提交、结果校验产生后端事件，Python 和 Cline 进度继续使用原有 Coding 事件。
5. 原有 Coding 输入预览可能包含文件 Base64 前缀。现在用户原文件的模型预览只保留登记元数据；完整字节仅进入已有不可变输入快照和共享目录，由 Cline 的 Python 程序读取、校验和解析。

截图中的旧等待状态与同步附件入口吻合；本次没有仅替换等待提示语。联机排查又暴露了混合计划拆成多个工作流的问题，规划约束已明确：为同一分析提供业务记录的查询应是 DATA_ANALYSIS 的数据要求，不能误作独立的资产使用说明任务。

## 最小计划扩展和执行边界

`ProblemAnalysisPlan` 增加可选 `fileAnalysis`，旧构造方法和无附件计划继续兼容。其 `inputs` 来自 `AssetAnalysisInputs.describe`：文件名、内容类型、大小、SHA-256 和 `user-asset:<登记ID>:<SHA-256>`。这是现有授权文件引用，不是模型构造的 MinIO 对象路径。规划器只见元数据，拒绝模型回填文件引用；混合任务实际读取时再次验证登记信息未变化。

`toolDataDependency` 明确区分 `NONE`、`OPTIONAL`、`REQUIRED`，缺少或非法判断会阻止执行。目标、原始问题、分析任务、预期输出和校验要求沿用现有计划及 Coding 任务契约；执行环境、预算、权限、取消、文件快照和产物接纳仍由原机制负责。

`NONE` 任务不把无关业务工具作为前置条件。`OPTIONAL` 只有在成功工具的原始结果确实为空时才能继续文件分析：失败、确认请求、未知结果和非空但未选定的候选都不能冒充空结果。`asset_query_result` / `template_query_result` 的空集合与零返回数按既有协议解释，过滤条件和控制元数据不当作业务记录。`REQUIRED` 缺少业务证据时不加载附件到 Coding，也不提交分析。

工具工作流自身的完成状态继续保留。控制链测试明确断言 `mandatoryWorkflowCompleted=false`；允许完成的是独立成立的文件分析，不是把未执行的模板节点伪装为成功。没有 Java Excel 探索、逐行质量计算或分析代码生成。

## 验证和复现

最终选定后端回归 **344 项通过**，前端 **304 项通过**，前端构建通过。逐套计数见 [tests.json](file-analysis-20261021-evidence/tests.json)，代码清单见 [changed-files.txt](file-analysis-20261021-evidence/changed-files.txt)。这不包含下文明确列出的旧用例失败，也不代表全仓库全量通过。

| 场景 | 证据 |
| --- | --- |
| 单 Excel 真实分析 | 任务 `47281dd5-8d13-3731-9494-88b6532dfd73`；[报告及数据库审计](file-analysis-20261021-evidence/single/) |
| 双 Excel 与跨文件比较 | 发布 Agent `financial_indicator`；任务 `608da455-3943-3147-aec4-8216af487cf8`；[报告及数据库审计](file-analysis-20261021-evidence/two/) |
| 普通浏览器拖拽、异步提交、真实进度 | 任务 `4c11ff49-73b7-42dc-85af-a07ddb598dc9`；[浏览器验收](file-analysis-20261021-evidence/browser-acceptance.json) |
| 必选 MCP 空结果可选/强依赖 | `AgentOrchestratorTest.optionalEmptyMandatoryWorkflowContinuesOneFileAnalysisWithoutInvokingBlockedDescendants` 的 OPTIONAL / REQUIRED 两个参数场景；真实 Orchestrator、计划运行时和 ToolRuntimeService，MCP 注册表与 Coding 端口为测试替身 |
| 混合非空数据 | `AnalysisCoverageCoordinatorTest.businessSourceDependencyControlsFileAnalysis`：REQUIRED/OPTIONAL 非空来源与文件同时进入一次 Coding 调用；空来源与失败来源单独验证 |
| 文件不存在、权限不足、对象读取失败 | `ClineContextAnswerServiceTest.filePreparationFailuresTerminateTheRunBeforeAnyCodingSubmission`、`AssetAnalysisInputsTest`、`UserAssetServiceTest`、`UserAssetControllerHttpTest` |
| Python 失败及既有修复 | Coding 服务回归；真实单文件收据还保留一次 exitCode=1 的失败，修复后的已接纳执行 exitCode=0，没有将失败收据记为成功 |
| 不重复调度、不无限等待 | 控制链断言查询一次、被阻塞后继零次、Coding 一次、最终 Run COMPLETED；发布 API 幂等重放返回相同任务；浏览器一次提交并完成 |
| 原有流程回归 | 必选工作流恢复、DAG/参数执行、普通对话、角色对话、发布 API、输入快照、Coding 和旧同步附件接口测试 |

单文件有 4 条记录：缺失 1、非数值 1、负数 1、重复 id=2，有效数值合计 5。第二份 3 条记录合计 82。跨文件报告列出 id=1 的 10/12 差异、id=4 的 invalid/40，以及单边 id=2、id=3。原始合成 Excel 随证据保存，可重复上传。

单文件和浏览器验收通过既有 API 参数明确选用已配置的 `qwen3.6-plus`；双文件使用发布 IDS 助手原有的 `qwen3.7-plus`。没有修改默认模型或助手配置。最终部署后又恢复了普通 Excel 的历史结果，浏览器没有再次提交分析，且没有页面错误。[结果截图](file-analysis-20261021-evidence/browser-completed.png)。失败联机尝试创建的 12 个原始测试附件已清理，成功样例和运行审计保留。

数据库审计从 `runtime_evidence_entry` 检查 `CODING_INPUT_SNAPSHOT.inputVersions`，证明所有原文件引用进入 Cline，不依赖前端日志。每个任务只有一个不可变输入快照；Cline 原有审查/修复可以产生多次程序提交和执行。归档 Python 收据另经授权接口下载并校验哈希和 `stopConfirmed`，证据中保留成功和失败记录。

复现命令（私密认证文件由环境维护，不提交仓库）：

```powershell
python scripts/verify-file-analysis-routing-stage.py --auth <private-auth.json> --output <evidence-dir> --case single
python scripts/verify-file-analysis-routing-stage.py --auth <private-auth.json> --output <evidence-dir> --case two
```

`optional` / `required` 用于观察环境现有业务检索；脚本保留请求、终态和事件，不会仅凭终态声称空结果测试通过。已有任务可用 `--resume-task` 观察，避免重复提交。服务器端使用 `scripts/audit-file-analysis-stage.py` 对登记引用及 Coding 执行证据复核。

## 失败记录与边界

- 环境当前 API 资产检索对合成的缺失名称仍返回 1 个候选，随后语义选择失败。这不是空结果。本次 OPTIONAL 和 REQUIRED 联机任务均明确失败，没有被伪装为空结果放行；详见 [stage-attempts.json](file-analysis-20261021-evidence/stage-attempts.json)。因此，真实空 MCP 与真实 Cline 同一次任务的联机验收尚缺稳定业务夹具。空结果路由由确定性控制链集成验证，原文件实际执行由独立的真实 Cline/Python 验证，二者证据范围已分别注明。
- 较长、含自然语言否定的混合请求触发了原有 `QUERY_REQUIRES_STRUCTURED_CONSTRAINTS`。本次保留查询优化和结构化过滤契约，失败记录留存，没有放松参数约束。
- 扩大探测时，旧 `authoritativeFallbackStopsAtEmptyAssetInsteadOfRunningTemplateDiscovery` 用例使用空 `ModelsConfig`，在模型名称校验处失败。该旧测试及断言原样保留；新增控制链测试显式配置本地测试模型和工具声明。不能把本次选定回归称为全仓库全量通过。
- 浏览器验证期间发现事件列表默认只返回 50 条，验收脚本已明确请求最多 1000 条，避免把旧分页误认为后端没有后续事件。产品事件流和现有游标逻辑保持原样。

最终部署哈希、服务检查和配置未变证明见 [deployment.json](file-analysis-20261021-evidence/deployment.json)、[final-health.json](file-analysis-20261021-evidence/final-health.json)。
