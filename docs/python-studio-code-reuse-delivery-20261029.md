# Python Studio 与 Cline 代码复用交付记录

本次在现有 Python Studio、CodingAnalysisService、Cline runner 和 MCP Python Sandbox 之间补齐授权源码输入及受审回写。没有新增执行器、数据库、Coding Agent、连接池或业务函数路由。原生成工作区仍为只读/提案、零执行预算；读取或生成代码不会自动取得执行权限。

## 1. 审查与实际调用链

先行审查见 [源码审查](python-studio-code-reuse-audit-20261029.md)。原有链路存在三个明确边界：

1. `PythonCodeAssistantService → CodingWorkspaceService → Cline` 负责生成提案，允许读取/提案，不能执行 Python。
2. Studio 原 `scripts/{id}/execute → testScript → MCP preview` 是单脚本预览，不等于分析/修复闭环。
3. `CodingAnalysisService → DurableCodingTaskService → ClineCodingAdapter → @cline/agents → python_execute → ClinePythonCallbackController → CodingPythonExecutionBridge → MCP PythonManagedExecutionService → PythonDockerRuntime` 已有分析、预算、修复、归档能力，但没有冻结的用户多文件源码输入。

新增链路：

```text
PythonReusePanel
  → code-reuse.prepare：按当前用户/租户读取指定版本，验证 SHA256，冻结输入
  → 用户单独确认执行该快照
  → code-reuse.execute → 原 CodingAnalysisService / DurableCodingTaskService
  → 原 Cline runner：复用原项目，或提交锚点修改/新增文件
  → 原 python_execute / Java 回调 / 代码版本与证据存储
  → 原 MCP Sandbox：在同一容器内还原项目并运行入口
  → 原 execution_feedback.v1 / 修复预算 / 执行证据
  → status 投影真实状态、源码版本、诊断、产物和报告
  → 用户审查逐文件 Diff 后显式 apply
  → 原 Studio 保存接口：锁定源文件和目录，比较版本与摘要，追加版本
```

数据继续由 Runtime 形成授权、不可变的输入快照；Cline 决定如何分析。项目源码与业务数据分开传递，不授予业务 MCP 或外网访问权限。执行采用现有默认已发布 Python 环境；Studio 当前选中的环境是新增文件的回写目标，页面授权文字明确这一点。

## 2. 代码资产引用契约

入口：`/api/v1/data-science/python/code-reuse`，沿用 data-science 工作区可用性与现有身份系统。请求不接受可信 owner/tenant；后端通过 ObjectAccessService 获得稳定用户 ID 和 Studio 历史 username。

| kind | id / version / path 的含义 | 授权与保真 |
| --- | --- | --- |
| SCRIPT | Studio script ID、精确历史版本、实际工程相对路径 | 当前用户及租户所有；读取版本表正文并验证存储摘要；拒绝路径重映射 |
| ASSET | 个人文件 ID、不可变版本 1、项目相对路径 | 现有 UserAssets 所有权检查；限定 Python 源码类型、严格 UTF-8 |
| SNIPPET | 正文、相对路径及正文摘要 | 先验证摘要，再保存到原 UserAssets CODE；冻结后统一为 ASSET |
| TEMPLATE | 已发布模板 ID、其 scriptVersion、相对路径 | 当前仅允许本人所有的已发布模板源码；发布可调用不等于允许读取他人源码 |
| WORKSPACE | 本人完成的现有 Python Coding Workspace task ID、outcome revision、提案路径 | 复用原 workspace 读取入口；必须完成且版本匹配 |
| ANALYSIS | `<priorRequestId>:<cline-operationId>`、实际 scriptVersion、项目路径 | 验证本人历史快照及代码归档、版本、摘要；复制到原 UserAssets CODE，冻结为 ASSET，避免递归历史引用 |

所有引用必须提供精确 `sha256`。`entryPath` 必须指向项目已有文件。项目协议为 `runtime_os.python.code-project.v1`，最多 32 文件、源码 UTF-8 合计 128000 字节；拒绝绝对路径、反斜杠、冒号、空段、`.`、`..`、大小写冲突及缺失入口。保留空 `__init__.py`、目录、换行和文件正文。

`prepare` 返回 `requestId / snapshotSha256 / sources / dataFiles / executionAuthorized:false`。相同请求 ID 不允许改绑另一个需求或源码快照。`execute` 必须同时提供该摘要和 `authorizeExecution:true`，并重新验证源码、数据与选定 Skills 授权。只保存或加载不会执行分析。

多文件适配器将经过约束的项目嵌入原单脚本契约，在原 Sandbox `/tmp/runtime-python-project` 还原文件；包入口使用 `python -m` 保留相对导入。子进程仍属于原容器/cgroup/网络与截止时间。它没有独立调度、日志库或权限机制。原源码各自摘要与实际适配脚本摘要分别保留，不能把适配器 Diff 当作用户代码 Diff。

## 3. 本次修复的实际断点

- 多文件原先未交付实际 Sandbox。现在原 CodeHistory 负责整份项目版本；`project.reuse` 原样运行，修改使用唯一文本锚点、新文件和父版本摘要，禁止借新增覆盖已有文件。
- 把源码目录放在输出目录会破坏原平面产物归档；最终使用原 Sandbox tmpfs 的 `/tmp`，不将源码目录伪装成结果产物。
- 原反馈 frame 只接收文件名，真实项目 KeyError 在 `project/main.py` 的定位会被拒绝。现契约、采集器和 Java 边界只接受真实执行源码声明的安全项目路径，仍不保存客户原始错误消息或任意宿主路径。
- SDK 多工具回合的 message-added 事件可能处于尚有未配对 tool result 的中间状态；只在完整工具边界提交 checkpoint。Java 原严格校验未放宽，使用固定 SDK 的回归覆盖。
- 项目适配器含原始源码，运维 monitor 现在只记录真实字节数、摘要及版本，源码保留在已有受权代码归档。不会在通用监控重复保存 base64 原文。
- 恢复时保留原 task、预算、deadline、代码与结果；父模型绑定丢失需要显式恢复原任务，不新建预算。页面由真实后端状态驱动，不因 HTTP 等待或临时查询失败提前终止。
- 取消可能在回执归档前完成，页面计数明确为“已归档执行回执”，不把缺少回执推断为未执行或成功。真实终态由任务与执行器停止确认决定。

保留一份早期输出路径适配模板仅用于解码已保存历史提案；新执行必须匹配任务冻结的当前适配模板，不能换成历史模板运行。

## 4. 原始代码、回写与结果

- Cline 修改始终先进入现有代码归档，Studio 原文件保持原样。
- status 返回每次真实回执、真实执行代码摘要、完整项目文件和逐版本 Diff；历史源码版本仍可读取。
- apply 显式选择 operation、代码 SHA、文件路径和新增文件目标环境。事务内锁定全部原 SCRIPT 及目录，确认版本、正文摘要和原路径后才调用原 saveScript。源文件已有用户修改则整组拒绝。
- 新文件也走原 Studio 版本管理。现有同环境 basename 唯一限制仍在；碰到不同目录同名文件拒绝，不能平铺或静默覆盖。
- 回写元数据回执存储于原 Runtime checkpoint，与 Studio 写入参与原事务；状态接口可恢复路径、文件 ID、版本、摘要、确认时间。不新建表。
- 代码生成成功、Python 执行成功、结构/产物有效和业务正确性分开。通用 Runtime 不具备客户业务验算规则时显示 NOT_EVALUATED/INCONCLUSIVE，不伪装为业务校验通过。

## 5. 自动化验证

本轮变更文件及 SHA-256 完整清单为 [changed-files.json](python-studio-code-reuse-20261029-evidence/changed-files.json)，共39个源码、测试、文档与浏览器验收文件；证据文件另列于目录。主要改动分组：

| 文件/范围 | 职责 |
| --- | --- |
| PythonCodeReuseController / PythonCodeReuseService | 授权引用、只读准备、原分析入口、状态投影、原版本管理回写 |
| PythonCodeProject / 两份适配模板 / coding-analysis及execution-feedback schema | 有界工程与精确模板协议、历史解码、安全诊断路径 |
| CodingAnalysisService / ClinePythonCallbackController / CodingExecutionFeedbackService | 原分析上下文、冻结项目校验、原始异常与执行版本关联 |
| PythonExecutionLogBoundary / runtime_capture.py | 原采集与脱敏边界保留真实项目定位 |
| python-project.js / runner.js / sdk-adapter.js / monitor.ts | 原 Cline 工具内最小修改、SDK 完整回合 checkpoint、运维源码最少留存 |
| PythonReusePanel及原DataScienceView挂载 | 文件选择、独立同意、真实状态、Diff、确认回写、恢复 |
| PythonDataScienceService / PythonRepositories / PythonCodeAssistantService / UserAssetService | 复用原文件/版本/Skills服务，支持安全的空包文件与精确历史读取 |

任务开始时已有147个改动/新文件；其中137个文件的字节摘要保持不变，10个共享分析/执行/SDK文件按本任务必要边界修改，见 preservation-proof.json。没有删除原任务文件。

| 范围 | 结果 | 具体覆盖 |
| --- | --- | --- |
| Java 8 个测试类 | 131 通过，0 失败/跳过 | 项目协议/真实适配器字节、授权/摘要/历史/只读准备、独立执行同意、回写 CAS/回执、资产空包文件、原分析/回调/反馈/JSON Schema 回归 |
| Node 全套 | 116 通过，0 失败/跳过 | 固定 @cline SDK 完整回合 checkpoint、工程增量与父版本、防越界、原预算/取消/修复及监控回归 |
| 前端 6 个测试文件 | 39 通过 | 原 Studio、代码引用、恢复/跟随实际版本、监控和个人文件回归；计数文案修改后另跑组件 6 项通过 |
| Python runtime_capture | 10 通过 | 错误分类、预检、脱敏、只读输入、可选依赖和真实运行错误 |
| 离线前后端打包 | 成功 | 前端存在既有大 chunk 提示；未通过临时联网获取依赖 |

这些是自动化回归，不替代下面的真实联调。证据和变更摘要见 [证据目录](python-studio-code-reuse-20261029-evidence/)。

## 6. 真实模型、MCP、Sandbox 与页面验收

在授权服务器的隔离预览环境使用实际配置模型 `qwen3.8-flash`、`@cline/* 0.0.93`、现有 MCP 和缓存 Python 3.11 镜像。没有 Mock 模型、Sandbox 回执或浏览器请求。生产容器未替换。

| 场景 | 实际结果 |
| --- | --- |
| A 完全复用 | COMPLETED，1 次 Python；main.py、metrics.py 逐字节不变，报告总计 60 |
| B 部分复用 | COMPLETED，2 次 Python；原 total/concentration 函数正文完全保留，新增 preference.py；真实模型期间曾提交无效工具参数，原重试/预算正常约束 |
| C 错误修复 | COMPLETED，2 次 Python；第一次真实 KeyError、project/main.py 第 5 行；第二版本仅将 missing 改成 amount，真实结果 7 |
| D 多文件 | COMPLETED，5 次 Python；原包目录、空 __init__.py、指标、主入口及测试文件原文均保留，相对导入正常；模型新增验证文件期间也有真实失败/修复，不把主程序成功冒称所有 unittest 均通过 |
| E 源版本 | COMPLETED，1 次 Python且原代码不变；用户保存 v2 后旧提案 apply 返回 409，v1 与用户 v2 都保留 |
| E 回写/历史 | 修复 main.py、新增 preference.py、空 pkg/__init__.py 均通过原 API 精确回写 v1；持久化确认回执重新读取一致；本人历史修复版本可只读准备且不执行 |
| E 取消 | MCP 已为 RUNNING、executorStopped=false 时取消；最终任务 CANCELLED，MCP CANCELLED、executorStopped=true、exitCode=137、resultAvailable=false |
| E 真实 API 拒绝 | 源码摘要伪造409、目录穿越400、未独立同意执行403；拒绝后仍为 PREPARED，无执行回执；隔离库没有其他所有者脚本，跨用户拒绝仍以单元测试为证 |
| F 受控财务数据 | COMPLETED，4 次 Python；两份真实上传的受控 CSV 经 Runtime 快照交付，metrics.py 原文不变；从实际归档 metrics.json 独立核对：资产1000、盈亏50、ROI0.05、集中度0.6、BUY2/SELL1；报告与两个完整来源引用一致 |
| 浏览器 | 7 个真实任务页面及刷新恢复；C 显示真实错误与 Diff，所有终态停止转圈；无页面脚本错误；API 服务更新重启后旧任务仍可恢复 |
| Docker 安全补充 | 7 项真实缓存镜像测试：正常/缺失/空数据、缺失依赖预检、只读输入拒写、外网拒绝、超时后执行器停止。此项是 Docker/采集入口验证，不冒称整条 Cline 链路的安全验收 |

任务 ID、operation ID、使用次数、摘要和真实输出见 `acceptance-r8.json`、`controlled-financial-proof.json`、`source-version-proof.json`、`cancellation-proof.json`、`browser-proof.json`。截图包括 [真实修复 Diff](python-studio-code-reuse-20261029-evidence/python-code-reuse-C-diff-visible.png)。

开发中失败的早期候选和初始观察记录留在服务器受限 private 目录，未删除、未冒充通过。D/B 曾超出测试客户端 390 秒观察窗口而仍在后台运行；最终通过重新读取真实终态确认完成，没有调高任务总超时或重置预算。早期产物目录适配失败保留原文件并按现有停止/归档流程处理，不在执行状态不明时重跑；最终查询已是 CANCELLED，见 early-candidate-reconciliation.json。

## 7. 部署、回退及兼容性

部署仅替换 `sql-metadata-preview-api`、`sql-metadata-preview-mcp`、`runtime-analysis-preview-node` 隔离容器；沿用原控制网络、loopback 端口、回调代理、防火墙、Secret 与持久卷。代码增量 jar 经目标 SHA 校验、`docker build --network none --pull=false` 构建，未改 License、生产数据库、索引、缓存或业务环境默认值。仅通过正常 API 增加独立验收用 Studio 环境与受控数据，不创建空业务库。

现场基线：API main-d9f39979d-20261028，MCP main-9b18e67f9-20261028。最终隔离镜像为 API python-studio-reuse-20261029-r9、MCP python-studio-reuse-20261029-r6、Node python-studio-reuse-20261029-r7，字节摘要分别见 api-build-r9.json、build-manifest.json 的 MCP 项及 node-build-r7.json。早期 manifest 是阶段记录，不能将其当作最终版本。最终 API r9 只调整回执计数文案；Java 131 项结果来自同一 Java 源码的 r8 构建，r9 复用通过结果打包，未伪造第二份 Java 测试记录。

无数据库迁移。新增源码快照、回写回执使用已有 checkpoint；历史 ANALYSIS 输入规范化为已有 ASSET，避免回退读取递归新引用。协议/诊断字段扩展向旧单脚本兼容；未传 codeProject 时保留原任务指纹。原生成链路零执行权限未改变。

回退步骤：先停止接收新复用执行，等待/显式取消并确认当前任务和 Python 执行器停止；备份已有 checkpoint、代码归档、Studio 数据及挂载；将隔离/后续正式环境恢复至发布前保存的 API/MCP/Node 三个镜像和相同配置，恢复回调代理并重跑原防火墙约束；校验原 Studio/分析/反馈。保留新任务数据与提案，不删除持久卷，不用新请求 ID 重跑停止未确认的操作。已确认回写可在原版本管理里审查后恢复，部署回退不擅自撤销用户确认的代码。

## 8. 尚未覆盖与实际限制

- F 是明确标注的受控样本，尚未取得经过授权的真实客户交易/资产数据，不能声称真实客户业务验收完成。
- 跨用户源码拒绝、独立执行同意、摘要、路径和错误版本有自动化覆盖；本轮未用第二个真实账号完成全部跨租户/失效授权组合联调。
- Docker 补充证明网络/只读/依赖/超时边界；未用这几项代替 Cline 全链路恶意代码或所有依赖修复场景。没有突破现有容器隔离边界的安全保证。
- 已完成真实 KeyError 修复；本轮未对所有异常类型分别做 Cline 自动修复演练。缺失依赖在实际缓存环境预检拒绝，禁止临时联网安装。
- 取消前的中间调用可能尚未形成完整 execution_feedback，页面因此计数归档回执，而不是推测总执行次数。取消真实停止证据另由现有 MCP 管理记录核对。
- 模型有时会新增多余验证代码或提交无效参数；沿用既有模型/工具/执行次数和总 deadline 约束，没有硬编码业务函数映射或保证最少模型轮次。
- 超过32文件/128000源码字节，以及协议包超过原代码/检查点容量的工程会被拒绝；未完成大工程与其他 Python 镜像矩阵验收。
- 前端提供当前工程/文件/上传/片段选择；历史 workspace、模板及分析版本可经同一 API 引用，尚未增加独立历史工程选择器。
- 新增文件同环境 basename 唯一、现有默认分析环境等原系统约束保留；没有借本任务重构文件管理或环境选择。

生产推广仍需按正式变更窗口完成；本任务完成的是代码、离线构建与隔离现场验收，未执行生产替换。
