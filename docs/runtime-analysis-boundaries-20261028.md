# Runtime OS 分析链路整改审计与验收

基线：`378aff526`；工作分支：`fix/runtime-analysis-boundaries-20261028`。本次只调整分析边界、输入交付、受控工具和必要的部署配置，不创建新的 Agent、Reviewer、存储服务或业务 MCP 入口。现场验证使用隔离的 API/MCP 配置库和独立 Cline 节点；生产容器不替换，News 不重启。

## 1. 实际调用链与审计结论

```text
Interaction / Agent 请求
  -> ProblemAnalysisPlanner：现有模型理解任务，保留授权及澄清边界
  -> AgentOrchestrationEngine / InterpretationPlanRuntime
     -> 现有 MCP 发现、模型语义选择及参数绑定、Runtime 执行查询
  -> AnalysisCoverageCoordinator：已授权 DatasetHandle 集合
  -> CodingAnalysisService：任务预算、固定数据范围、不可变配置与输入
  -> CodingInputSnapshotService / CodingInputFilePublisher
     -> CodingSharedFiles + MCP 注册 + 归档 + manifest/SHA256
  -> ClineCodingAdapter：签名、租约、fencing、Node 能力检查
  -> Node runner + 原生 Cline SDK
     -> Java 模型回调：实际已选择模型、请求超时、剩余任务预算
     -> input_read / skill_read / report_submit（任务明确授权）
     -> python_execute（需要计算时）
  -> PythonManagedExecutionService / PythonDockerRuntime
     -> 只读脚本与输入、受控输出、无网络、实际镜像与依赖
  -> CodingEvidenceCollector：停止证明、输入及产物校验、结果/证据归档
  -> FinalSynthesisNode：发布既有结果，不再调用模型复述
```

| 检查项 | 源码及方法 | 发现与处理 |
|---|---|---|
| Java 上下文 | `chatchat-api/.../coding/analysis/ClineContextAnswerService.answer` | 原来把 question/history/summary/evidence/instructions/完整文件计划混为输入，并要求 Python 序列化答案。改为 objective、constraints、明确标注的原始材料；不传编排/Reviewer 历史。历史摘要与参考材料不冒充当前业务证据。 |
| Java 文件计划 | `CodingAnalysisService.prepareFileSources` | 停止生成 `runtime-file-plan:` 数据集。Java 判断授权及任务类型，Cline 决定分析方法。 |
| runtime_input 来源 | `CodingInputLayout.describe`、`CodingAnalysisService`、`coding-agent-node/src/runner.js` | 这些位置原来直接教模型调用内部 helper；改为真实文件描述、标准/已安装 reader。 |
| runtime_input 实际存在性 | `chatchat-mcp-server/src/main/resources/python/runtime_input.py`；`PythonDockerRuntime.createManaged` | helper **确实存在且原受控执行路径会复制它**。没有按“模块不存在”的假设删除历史兼容功能，也没有新增虚构包。`CodingTableCodec` 的 Parquet message 名称不是 Python import。 |
| 不可变文件 | `CodingInputFilePublisher.capture/publish/materialize`、`CodingInputRows.scan`、`CodingSharedFiles` | 复用内容校验、作用域、归档恢复与 MCP 注册；新增可选 `nativeFiles`，交付原始字节及实际路径，旧 manifest 缺字段时为空列表。 |
| Cline 补数 | `CodingDataSupplyService`、`CodingAnalysisService.run` | 原 data_request 有 Java 授权约束，不能等同任意 DB 访问，但仍让 Cline 选择补充模板。本次新任务不再授予此工具/授权目录，回调网关也不发布该路径。旧持久任务的解析代码暂留，迁移须先排空。 |
| 本地阅读 | `CodingInputSnapshotService.readForCoding`、`CodingInputText`、`ClineModelCallbackController` | 只读当前任务授权快照；SHA/大小/分片顺序校验；分页上限 16000 Unicode 码点；原始文件可以直接阅读；读取计入既有 TOOL 预算及真实停止回执，不调用 Python。 |
| 纯文字提交 | `CodingEvidenceCollector.collectText/textResult`、`CodingTaskContract.permitsTextResult` | 复用 v3、原结果行/归档/证据库。仅明确 TEXT_OR_CODE 且未执行 Python 的任务可提交；不伪造代码或 Python 执行回执，计算验证为 INCONCLUSIVE，明确未独立计算。 |
| 工具注册 | `ClineToolProfiles`、`ClineRuntimeModelBridge.complete/allowedTool`、Node `createCodingRun` | 工具由冻结任务白名单限定，不能注册业务 MCP、DB 或 host_shell。现场发现旧固定 4 工具上限拒绝了合法 5 工具组合，改为任务授权集合的上限并拒绝重名。 |
| Skills | `CodingConfigurationSnapshot`、`CodingRuntimeContext`、Node `promptConfiguration/skill_read` | 正常路径已有目录卡片+授权按需读取；不重新搭建 Skills 服务。未声称所有历史 fallback 已无全文注入。 |
| 重复审查 | `AgentOrchestrationEngine.reviewInterpretationPlanToolResult/selectPublishedTemplates`、`AnalysisCoverageCoordinator.analyzeWithCoding`、`FinalSynthesisNode` | 已有确定性执行准入、Coding-owned 分析和直接发布。模板/资产发现仍需语义判断，不能一并跳过。`SemanticClaimReviewer` 无生产调用点，不能以删除死代码宣称减少运行调用。 |
| 有限修复/取消 | `SchemaDrivenTemplateParameterBinder`、既有 Coding 预算/租约/执行反馈与 Node loop | 模板协议最多一次修复；增加选择完成与修复前取消检查。Python 技术修复仍由实际反馈、既有次数/时间/调用预算限制。 |
| 长材料 | `ModelProtocolJson.userInputView`、`ProblemAnalysisPlanner`、`AgentOrchestrationEngine`、`CodingAnalysisService.executePrimaryAnalysis` | 规划模型默认只看到带完整性标记及 SHA 的限长视图；原文完整入快照并按需读取。过长业务供数请求不能用被省略的约束静默绑定默认值，须澄清。没有用关键词代替语义选择。 |
| Python 依赖与语法 | `runtime_capture.py.check_source/main` | 同一受限镜像内先 compile/检查无条件顶层 imports，失败不运行用户语句；可选导入仍交由原程序处理。没有包安装。实际 NameError 等仍走真实执行反馈。 |
| 用户进度 | `runtimeObservationPresentation.js` | 已有真实后端事件投影与状态仲裁继续使用；本地读取、报告提交归入业务步骤，文件分析使用“准备数据/正在分析/生成结果”。不模拟进度，不把单次模型失败直接当父任务终态。 |

## 2. 输入与结果契约

继续使用 `runtime_os.coding.input.v1` 和现有 v3 任务。结构化业务数据保留 Parquet 优先、CSV 兼容以及 cN/pN 的精确类型、missing/null 语义；没有把金额改为浮点或默认补零。

原始附件额外交付 `nativeFiles[]`：`fileId/fileName/originalName/sourceDatasetRef/sha256/sizeBytes`，以及由已验证字段派生、不可由客户端覆盖的 `virtualPath/readOnly/format`。实际挂载为 `/data/input/{fileId}/{fileName}`，只读。TXT/SQL/Markdown/LOG 和原 CSV/Parquet 可以直接用真实格式读取；无需先在模型生成代码里反解 Base64。原先的传输行仍作为兼容和来源证据保留，不能当业务数据统计。全部文件共用既有行数、文件数、逻辑字节及物理字节上限，原文件最多 8 个。

发布时先注册原文件，再生成结构描述和原有传输文件，最后提交 manifest。`nativeFileSchemas` 同时进入 schema 文件和已提交输入证据：Parquet 只读 footer 得到字段及真实行数；CSV 只读表头，不猜测字段类型。解析失败明确标记 `STRUCTURE_UNAVAILABLE`。原消息 TXT 明确标注为原文，不能当作 `analysis-materials.v1` JSON 信封解析。

长用户消息完整保留为 `runtime-user-message:<run>`，同时提供校验过的原文 TXT；规划视图不等于原文，也不允许根据省略的实体/日期授权查询。超出系统数据上限明确拒绝，不悄悄截掉原材料。当前没有声称实现了通用的长材料语义指令提取器：范围不明确时澄清。

`input_read` 是显式工具：当前冻结任务 + datasetReference + offset + limit，无任意路径、SQL、网络地址或新数据源参数。内容标记 `UNTRUSTED_DATA_ONLY`，不升级为执行权限。二进制不能假装 UTF-8 文本，须使用已安装 reader。

纯文本和 Python 结果使用相同报告行（reportMarkdown/evidenceReferences/limitations）、相同历史与用户资产归档。纯文本不要求生成“只写 JSON”的 Python；其回执明确 `TEXT_ONLY/pythonExecuted=false/sqlExecuted=false`。一旦 Python 已开始，不能用纯文本提交绕过真实停止及产物验证。v1/v2 和计算任务继续要求实际代码与执行证据。报告排版继续使用既有 ReportRenderer/visualReport 协议，未新增报告系统。

## 3. 网络、进程与迁移

`node-proxy.conf` 新增 8081 精确 POST 路由白名单；Node 默认不再直连 API 8080。`restrict-coding-worker.sh` 只放行当前隔离 worker → API 的 8081，拒绝配置 8080/8791/8792。任务签名、授权、租约和 fencing 仍在 Java 校验；经过同网络命名空间代理的 peer 是 loopback，不信任任意转发头。

新回调入口不发布 `/data-request`、管理 API、SQL 模板、OpenSearch 或任意其它路径。SDK 不提供 shell 或业务 MCP；Python 保留无网络、只读输入/脚本、指定输出、CPU/内存/PID/临时目录限制。网关等待上限不替代实际模型配置超时或任务截止时间。

发布必须先排空旧 Coding 任务，再同时更新 API、Node、代理配置和现有防火墙脚本。单独替换一个组件可能因缺少工具能力而明确拒绝启动任务。现有显式 callback URL 不会被模板自动覆盖，须核对部署 env。旧持久任务的 data_request 不能通过新代理继续补数，不能宣称它们无缝续跑。

MCP 使用宿主 Docker daemon 时，Python 工作目录的**宿主路径与容器路径必须一致**。本次隔离克隆暴露了路径重映射导致脚本启动 exit 2；修复隔离挂载及 `CHATCHAT_MCP_PYTHON_WORKSPACE_ROOT`，没有扩大 Cline 权限。原 Compose 已使用同路径挂载，未为测试改变生产目录。

回退：保存当前镜像、原代理/env/防火墙参数；排空候选任务后恢复匹配的一整组组件，重算 worker/API IP 的既有规则。恢复原 Secret、License、master key 的只读挂载。不要并行启动两个容器打开同一个嵌入式任务库，不删除任务记录、用户资产或业务数据库/索引。新增原文件字段是向后兼容扩展；旧客户端未协商新工具时拒绝新任务。

## 4. 测试与现场证据

构建、日志、真实模型原始响应及含授权范围的材料位于忽略的 `target/runtime-analysis-boundaries/` 和服务器受保护的 `/opt/chatchat-releases/runtime-analysis-boundaries-20261028/private/`，不提交凭据、完整业务数据或 JWT。

已完成：

- 最终 R12 离线 Maven 专项构建成功：497 项，487 通过、10 项门控跳过，零失败/错误。模块总数为 common 105、platform 3、agents 48、chat 18、MCP 4、API 319（含跳过项）。不是整个仓库全量测试。原始日志：`target/runtime-analysis-boundaries/package-r12.log`。
- Node 108 项通过；包含实际原生 SDK 的 input_read → report_submit、零 Python、无多余总结轮，以及工具/预算/故障边界；其中模型网关为受控 fixture，不冒充真实提供商。
- 前端 35 项通过：真实事件投影、ReportRenderer、Python Studio 既有状态。
- 缓存的现场 Python 镜像内最终 13 项通过，包括历史 runtime_input Parquet/CSV 精度回归、预检查及标准解析器错误分类。Windows 系统 Python 缺 pyarrow 的初次失败单列保留；没有临时联网安装替代。
- 真实 Docker 的 7 个受控场景：DWD/DWS/ADS 三天 9 条样本计算与 PNG、空数据、缺失 ADS、缺依赖执行前阻止副作用、只读输入、无网络、超时后停止。它们不是完整 Cline 端到端验收。
- 真实 Nginx 白名单请求检查通过（后端明确是认证 fixture）；真实隔离 Node 的 API 管理端口/MCP/MySQL/OpenSearch/外网连接均被阻断，回调可达，未签名 401，管理和补数路径 404。
- 旧 Reviewer 测试在 `378aff526` 基线复现 8 个失败；更新测试以核对当前原生模板选择协议，保留语义选择、一次调用/有限协议修复及取消的实际断言，没有通过删测试绕过。
- 实际 Compose 检查 11 项通过；SQL/Python Workspace 原生 SDK 回归 4 通过、1 项可选真实模型门控跳过，保留原 SQL 差异审查与文件管理流程。前端构建通过。

### 真实模型与真实执行记录

下表的调用数是 Coding 模型回执数，耗时是交互接口记录的整个请求耗时，不能混为单次模型耗时。R 标记区分开发期间候选版本；不是所有场景都在最终 R12 重跑。

| 场景 | Runtime runId | 耗时/秒 | Coding 模型调用/输入 tokens | Python 执行 | 结果 |
|---|---|---:|---:|---:|---|
| Markdown 原文解释（R3） | `c3257c3c-231e-4382-a621-6d357f956f3b` | 63.93 | 2 / 15298 | 0 | COMPLETED；原生文字结果，无伪造代码 |
| 附件 SQL 解释（R3） | `01fb1ccb-4db7-451a-bae2-51fa0c8b8b86` | 191.06 | 4 / 35439 | 0 | COMPLETED；仅静态分析，没有业务 SQL 执行 |
| 10000 行 CSV（R8） | `2e3daf71-ead7-4f48-9a59-cfe25050c9b0` | 163.29 | 5 / 73163 | 1 | COMPLETED；全文件合计 12500 与受控期望一致 |
| 两个 Parquet 联合分析（R8） | `eae82448-5990-41b3-9061-0910aac9f095` | 167.22 | 6 / 104604 | 3 | COMPLETED；按 customer_id 关联并说明缺失侧 |
| 多 CSV（R8） | `fd27b9bb-7fb2-41f3-a698-eedcdf3ef856` | 243.16 | 8 / 162246 | 3 | COMPLETED；C1 合计 30.75 |
| 24212 字符粘贴日志（R5） | `dae63ea3-bdfd-46bd-9788-aae0bd703c51` | 660.13 | 11 / 334974 | 5 | COMPLETED；300 条记录，原文 SHA 校验一致 |
| 同一粘贴日志（R10） | `b69bd3fa-48d0-48ff-90ae-d5fb9793d991` | 462.77 | 8 / 194968 | 4 | COMPLETED；各阶段 1200 秒，合计 3600 秒 |
| 现场金融快照（R3） | `a44871af-7a0a-450d-83a9-89d1cbbad34b` | 316.34 | 12 / 233363 | 4 | COMPLETED；Runtime 供数，无 data_request 授权 |
| 同一金融需求（R9） | `e579a41f-520e-422c-aeaf-5d053c110571` | 222.15 | 8 / 130803 | 3 | COMPLETED；保留两次真实字段访问失败及成功修复 |
| 空 CSV + 人为缺依赖（R11） | `c9404a18-3317-4d08-9f8f-9d789bf040d8` | 198.90 | 5 / 60663 | 2 | COMPLETED；一次 PACKAGE_NOT_INSTALLED 后一次标准库修复，真实记录数 0，不虚构图表 |
| DWD/DWS/ADS 图文（R11） | `b4376d60-42ce-476b-a8b0-73275ab67d19` | 140.02 | 8 / 153860 | 2 | COMPLETED；实际 PNG、两张汇总表；布局提示回退 |
| DWD/DWS/ADS 图文（最终 R12） | `53c7f156-3eb1-4050-8b6c-3bf63a159b18` | 103.05 | 7 / 131614 | 3 | COMPLETED；实际 PNG、两张汇总表；布局提示回退 |

金融需求原文为“查询客户号 070200046604 的交易和资产、盈亏情况，总结客户交易偏好。”使用既有 financial_indicator Agent 及其配置模型 `qwen3.7-flash-2026-07-15`，没有改变 Agent 的模型选择机制。成功图文复测显式选择相同的已配置 `qwen3.7-flash-2026-07-15`，需求收敛为一张趋势图和阶段汇总表；其余表中场景为 `qwen3.8-flash`。因此图文复测不是与早期复杂双图请求的同条件性能比较。金融数据是**现场配置的金融 H2 数据快照**，没有独立证明其为真实客户生产数据。`INCONCLUSIVE / businessValidation=NOT_EVALUATED` 不等于业务语义已被独立审核；它也不否认真实 Python 执行和文件完整性校验。

长消息原文 SHA256：`b29aaa366c5405d5330f44522ec205e47e72f49e91da90a266d254e32657ca25`。包含问题与材料的整条用户消息在两次执行的原生 TXT 中均完整保留。中间的 R9 失败揭示模型把原文当成 JSON 解析，修复为明确的原文 TEXT 契约及标准解析器错误分类。

### 联调发现及针对性修复

- 输入阅读/原生提交会与后台租约续期竞争 revision。已使用现有 `CodingTaskRevisions.mutate` 对 reserve/start/reconcile/settle 做 CAS 重试，不重复外部执行。
- 初次真实 Python 计算可能正确、但输出字段不符合冻结 Schema。输入上下文和失败修复现在都携带现有 `rowSchema/requiredFiles`，不会让模型通过重复业务查询修复格式。
- Python 执行后已有完整且验证过的报告时，使用现有 SDK beforeModel 扩展点停止重复开放 artifact_read 和原生文字完成路径，保留 Python 修正能力；没有创建额外的规划器。
- 非分类附件遗漏了可选图文协议，R10 修复该条件。图文偏好与真正必须 visualReport 的冻结 Schema 分开：缺省可选字段不会强制生成图表；已提供的图表仍必须校验。
- 最终 R12 消除 VisualReportMode 要求再次 artifact_read 与 SDK 自动读取契约的冲突；返回独立的 artifactValidationStatus/layoutValidationStatus，保留业务 PARTIAL/UNRESOLVED，不要求为消除真实数据局限而重算。仍然保留 Cline 自身检查及实际错误修复，不关闭验证来获取成功状态。
- 真实 SDK/库异常增加安全分类：只读权限、包 API 不可用、本地 SQLite、JSON/CSV 格式、正则及数值类型。回馈安全类别和脚本行，不泄漏原始业务行/堆栈消息。

### 性能比较与失败记录边界

同一金融需求从 R3 的 12 次调用/233363 输入 tokens/316.34 秒变为 R9 的 8 次/130803/222.15 秒；同一长日志从 R5 的 11 次/334974/660.13 秒变为 R10 的 8 次/194968/462.77 秒。这是各版本单次现场观测，含随机模型代码错误及修复，**不是统计基准、不是完整整改前后成功率比较**。尚未在完整旧版环境对所有场景重复采样，因此不提供提升百分比或总体成功率结论。

R1 受控 Markdown 的真实模型调用为 2 次、输入 17829 tokens、模型等待 103037ms，随后受隔离路径错误影响失败，不能用作公平成功基线。R2 第一次真实请求在 Java 旧 4 工具上限被拒绝，未发提供商请求，已据此修复并加回归测试。早期大 CSV 的输出 Schema 错误、多 Parquet 的包 API 错误、旧长日志解析错误均保留；没有删掉失败后只统计成功。

## 5. 尚未关闭的验收项目

- 图文 R12 已完成 SDK → 真实模型 → Python → 产物归档 → 图文读取及历史恢复。PNG 的三天合计为 30/24/36 秒，文件 SHA 与归档一致；重读 report.md/rows.jsonl/PNG 没有增加模型和 Python 次数。**layoutValidationStatus 仍为 FAILED**，使用原有安全布局回退；不能据此声称模型智能排版完全通过。业务报告为 PARTIAL，保留没有开始时间和 SLA 等数据局限。受控真实图表见下方证据文件。
- 失败保留：R8 的 `93e4dfc1-5d4c-4c46-90ea-ca80d3887de4` 虽 COMPLETED，但未带图文协议；R10 的 `d635e778-86b4-42bc-8ec3-972fa25f9d42` 在四次真实技术错误后按修复预算停止。R11 `268d12e2-00be-4e7f-860a-83e52d4f1dcd` 五次进程成功后仍反复修改报告，最终回调 CONTRACT_INVALID；这不是业务分析验收成功。R12 提示词修正和一次成功复测不足以证明所有模型已无冗余自检。已有任务、调用、执行、修复与快照大小硬上限；尚未建立并验收通用的语义“无进展”判定，不能把字节变化当作分析进展。
- 完整 Cline 的缺依赖/空数据已通过真实模型与两次 Python 执行验证。完整 Cline 超时用例 `399936fc-c91a-4d47-b5ca-aac23aed3303` 以实际 `time.sleep(360)` 超过原已发布环境的 300 秒，Python 状态 TIMED_OUT、诊断 TIMEOUT、修复 STOP、仅一次执行；MCP 持久操作记录和 Coding 任务均确认 executorStopped=true、无待核对操作。父 Coding 为 FAILED/EXECUTION_FAILED，**不是任务总预算耗尽的 TIMED_OUT**，没有伪造成功报告。初次测试误用 120 秒低于环境 300 秒限制，只得到缺少结果文件的正确失败，列为无效超时测试，未算通过。
- `qwen3.8-max` 单模型超时 `b72c1b62-1d9c-4e69-8a6b-75d7e504b54d` 的供应商用量仍待核对。已调用正式取消接口，但持久状态仍为 RUNNING + CANCEL_REQUESTED + reconciliationRequired，不能宣称已确认远端提供商停止。任务及费用证据保留，未重放请求。
- 模型超时后供应商用量未知仍须 reconciliation；不能假造用量、重新扣减或因本次测试增加任务总超时。容量拒绝属于准入结果，不是模型计算失败。
- 部分成功任务仍有 `meteringComplete=false` 和待结算预留，不能把记录中的 0 成本/CPU 当作已核对的实际费用或资源消耗；本次没有修改既有计费体系。
- P2 只验证并复用既有多文件、报告和 Python Studio 机制，没有新增分析结果复用系统，也没有声明全部历史 Skills fallback 均已懒加载。
- 没有生产替换，没有对业务数据库/索引作写入或数据重建；容器与挂载不变可以核验，但本次未做生产数据库行数、索引数量和所有缓存的升级前后比对。

## 6. 变更与证据文件

完整实现/测试文件清单及 SHA256：[changed-files.json](runtime-analysis-boundaries-20261028-evidence/changed-files.json)。按职责分组：

| 范围 | 文件与作用 |
|---|---|
| Java 任务与模型 | ModelProtocolJson、ProblemAnalysisPlanner、AgentOrchestrationEngine、SchemaDrivenTemplateParameterBinder：有界规划输入、澄清及取消；保留必要语义选择 |
| 分析入口 | AssetAnalysisController/Inputs、ClineContextAnswerService、CodingAnalysisService：干净材料、原文件、纯文本选择、取消新任务补数授权 |
| 数据交付 | CodingInputManifest、CodingInputText、CodingInputFilePublisher/Rows/SnapshotService/TableCodec、CodingInputLayout：复用快照、哈希、归档与真实格式 |
| 工具与结果 | ClineCodingAdapter/ToolProfiles/RuntimeModelBridge/ModelCallbackController/PythonCallbackController、CodingEvidenceCollector/ValidationPlanService：精确授权、实际 SDK 工具、统一结果证据及 CAS |
| 兼容契约 | CodingRuntimeContext/TaskContract/TaskOutcome、coding-analysis.schema.json：仅显式 v3 文本允许没有代码证据；旧计算任务继续严格校验 |
| SDK 与执行 | runner.js/server.js、runtime_capture.py、PythonExecutionLogBoundary、ExecutionFailureClassifier：本地阅读、原生文本完成、真实预检查/错误分类、隐私与停止证明 |
| 前端与部署 | runtimeObservationPresentation.js、应用 Compose/env/README/proxy/firewall：业务进度命名、最小回调面与协调升级；无新服务 |

可复查的脱敏证据：

- [真实模型与执行摘要](runtime-analysis-boundaries-20261028-evidence/live-cases.json)
- [缺依赖、超时、失败及取消待核对记录](runtime-analysis-boundaries-20261028-evidence/edge-cases-final.json)
- [报告归档与恢复](runtime-analysis-boundaries-20261028-evidence/report-restore-proof.json)
- [实际受控图表](runtime-analysis-boundaries-20261028-evidence/controlled-dwd-chart.png)
- [真实 Docker 组件边界](runtime-analysis-boundaries-20261028-evidence/sandbox-proof.json)、[实际 Node 网络拒绝](runtime-analysis-boundaries-20261028-evidence/network-proof.json)
- [候选镜像和构建 SHA](runtime-analysis-boundaries-20261028-evidence/build-manifest.json)、[生产容器启动时间与挂载摘要](runtime-analysis-boundaries-20261028-evidence/production-containers-final.json)
- [自动化测试摘要](runtime-analysis-boundaries-20261028-evidence/test-results.json)

证据不包含 API Secret、JWT、密钥、完整金融报告或客户明细。当前代码在工作分支待提交，没有推送或替换生产服务。
