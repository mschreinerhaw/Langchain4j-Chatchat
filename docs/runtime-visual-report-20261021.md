# Runtime OS 图文报告

## 基线与范围

开发基线为 `e7bbfdb00`，已建立并推送标记 `baseline/visual-report-20261021`；功能在 `feature/runtime-visual-report` 分支开发。原工作区没有未提交的用户修改。

现有通用对话已经由 Cline 编写答案并利用 Python 输出结构化结果；数据分析也已有不可变输入快照、Python Sandbox、执行收据、证据登记及 Silo 归档。本次增量扩展这些契约，没有新增分析引擎、调度器、数据获取流程或 RAG。

## 请求和状态

底部工具栏的「图文报告」开关默认关闭。开关仅作用于下一次发送，发送后立即关闭；新建对话、恢复历史、切换身份和刷新页面不会恢复开启状态。开关不写入草稿或历史记录，不改变 Web Search 语义。

Agent API 沿用 `POST /api/v1/agent/tasks`：

```json
{
  "tenantId": "当前认证租户",
  "userId": "当前认证用户",
  "agentId": "general",
  "mode": "llm_chat",
  "modelName": "qwen3.6-plus",
  "query": "分析已选择的文件",
  "toolInput": {
    "reportMode": "visual",
    "__userAssetIds": ["已上传且当前用户有权访问的文件 ID"]
  }
}
```

`reportMode` 缺省等同 `off`，只接受 `off|visual`。所选文件引用仍由现有 AssetAnalysisInputs 校验归属、大小、类型并生成带哈希的原文件快照，完整文件不进入模型提示词。聊天附件开启报告时通过此入口；关闭时保留既有文件分析接口和行为。没有附件的普通聊天请求仍使用原 Agent API。

已分类的数据分析任务增加图文输出要求；已分类的知识问答不增加要求。默认通用对话原先没有 Java 任务分类结果，采用可选报告字段：只有实际需求要求对已提供数据计算时才输出报告，普通解释、写作和代码设计明确不输出报告。这没有增加新的模型分类调用，也没有新增知识问答计算任务。固定工具/资产模板的业务参数排除 `reportMode`，原参数绑定和执行路径保留。

## Python 与结果契约

Cline 根据问题自主决定指标、方法、图表和章节。原始数据、报告指标、摘要表、图表和文字结论使用同次计算对象。原 `rows.jsonl` 单行结果保留 `reportMarkdown`、`evidenceReferences`、`limitations`，开启时扩展 `visualReport`：

```text
schemaVersion = python_visual_report.v1
title, summary, status = SUCCESS | PARTIAL | NO_DATA | FAILED
metrics = [{id, label, value: string, unit, calculation}]
tables = [{id, title, columns, rows, scope}]
charts = [{id, title, kind: png|svg|plotly, fileName, caption}]
sections = [{title, narrative, chartIds, tableIds}]
dataSources = [实际使用的完整输入引用]
validation = {checks: [{name, status: PASSED|FAILED, detail}], warnings}
timings = {analysisMs, chartMs}  // 程序实际测量，可省略
```

图表写入现有 `/workspace/output/results`，使用安全的单层文件名；Plotly 使用 `fig.write_json`、普通数组和 `template=None`，不提交 HTML/JavaScript。前端不重新计算业务指标，精确数值以字符串显示。

报告标题、摘要、文字、指标标签和表头使用用户提问的语言。单位只采用源数据元信息或用户明确提供的声明，未声明时留空，不能根据字段名擅自补充币种或数量级。

Runtime 仅从已停止执行、已验证清单和哈希的本次输出接纳文件，补充用户空间文件引用、代码/结果/收据哈希、任务与操作 ID、状态和实际执行代码引用。通过现有 `CODING_VISUAL_REPORT` 证据记录及血缘存储，文件沿用 Silo、关系和证据沿用 PostgreSQL。运行产物继续默认隐藏于用户文件列表。

前端结果位于既有 `uiResponse.visualReport`；较大消息使用既有 UI Artifact 的 `VisualReport` 节点和资源，历史恢复使用同一组件。模型通过现有 `artifact_read` 获知 `visualReportValidation` 并可在既有预算内修复技术产物问题。

## 校验、隔离与降级

- 接纳前保留既有输入授权、停止证明、代码/结果/清单哈希、计算结果结构及引用校验；报告额外校验唯一 ID、章节引用、表格宽度、文件格式及复杂度。
- 每份报告最多 128 个指标、64 张图、64 张摘要表，每表最多 200 行、40 列。清单最多 1 MiB，PNG/SVG 单图最多 8 MiB，Plotly JSON 最多 2 MiB；这些是资源上限，不是固定业务模板。沿用既有 Sandbox 执行时间、内存、CPU、网络和产物总量限制。
- PNG 验证签名、像素及完整解码；SVG 禁止脚本、外部图像、链接、事件、实体、任意 DTD 和主动内容，只接受 Matplotlib 标准惰性 SVG 声明。Plotly JSON 禁止外部 URL、地图/图像层、原型字段、可执行标签及二进制数组传输。
- 图表通过已有认证文件内容接口读取，继续执行当前用户与租户检查；没有公开对象桶或凭据传给 iframe。代码按需通过同一授权接口显示。
- Plotly 运行库固定为本地 `plotly.js-dist-min@4.1.2`，仅在需要时加载。结构化图在 `sandbox="allow-scripts"` 的不透明来源 iframe 中渲染，CSP 禁止联网及嵌套框架，没有 `allow-same-origin`。主页面从不执行模型 HTML/JS。PNG/SVG 作为图像显示。
- 图表损坏或缺失时保留已取得的指标、表格和文字，并明确为 `PARTIAL`；程序校验失败标记 `FAILED`，空输入必须为 `NO_DATA` 且不能夹带计算数值。整个报告结构失败时，原文字答案仍保留。浏览器加载/渲染失败也会在交付状态中显示部分完成，不改写原始执行事实。
- 程序声明的计算检查与文件完整性校验不代表通用业务认证，界面明确说明。未知口径或统计假设仍需 Cline 在结果中列出，不能由 Runtime 假装证明。

## 耗时和兼容性

沿用现有 Cline 模型调用、Python 操作及任务日志区分代码生成与执行时间；Python 可记录实际 `analysisMs/chartMs`；Java 记录产物接纳耗时；前端通过 Performance Measure 和 `runtime:report-chart-rendered` 事件记录实际图表读取/渲染耗时。

本次没有修改或重建 MCP、Coding Node、Python 镜像。验证环境原 Python 镜像已有 pandas 2.3.3、NumPy 2.3.5、SciPy 1.16.3、Matplotlib 3.10.0、Plotly 7.1.0。新增的浏览器 Plotly 文件约 4.8 MB，按需获取。PDF 导出、外部地图和远程图像不在本次实现范围。

## 验证和复现

后端相关测试首轮 144 项通过；最终重新打包时复核报告接纳 10 项、证据登记 11 项、Cline 对话 12 项、分析链路 25 项、UI Artifact 17 项、固定工具参数 1 项，共 76 项通过，其中覆盖真实 Matplotlib SVG、恶意图表、缺失图、空数据、失败检查及证据重放。前端图文功能 11 项通过，既有 UI/聊天回归 302 项通过。构建成功；现有大型前端分块警告仍存在。

本地浏览器测试使用真实 Python 生成的 PNG、SVG、Plotly JSON，但授权接口为测试夹具，不冒充完整 Cline 联机测试。桌面和移动端均验证精确数值、静态图、Plotly 交互、不透明 iframe、历史产物组件和无外部网络请求。复现：

1. 在已有批准的 Python 环境运行 `scripts/prepare-visual-report-browser-fixtures.py /output`，将产物放入 `target/assets-local/visual-report-fixtures`，或设置 `VISUAL_REPORT_FIXTURES`。
2. 在 `chatchat-api/web-app` 运行 `npm run test:visual-report`、`npm run test:visual-report:browser`。浏览器测试需 Edge/Chromium，可通过 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 指定。
3. 联机验收使用 [acceptance.csv](visual-report-20261021-evidence/acceptance.csv)，拖入文件，开启图文报告，要求 Decimal 汇总、缺失/负值说明、静态及 Plotly 图。预期有效 5 条、缺失 1 条，总额 `100.00`，A/B/C 组分别 `30.75/30.75/38.50`。复现同一模型时，Agent API 指定 `modelName=qwen3.6-plus`，或使用已配置该模型的已发布 Agent；页面通用对话继续采用现有默认模型。

后端最终验证命令：

```text
mvn -pl chatchat-api -am package -Dtest=VisualReportArtifactsTest,CodingEvidenceCollectorTest,ClineContextAnswerServiceTest,CodingAnalysisServiceTest,UiArtifactServiceTest,ToolDirectReportModeTest -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true -Dfrontend.install.skip=true
```

联机执行记录、部署哈希和截图见同目录验收证据，最终以实际执行结果为准。

最终样例的真实任务为 `f89441ee-b1e4-42bd-b67e-1b66d7667a5d`。Cline/Python 已完成中文报告，未声明的数值单位留空；有效记录 5 条、缺失 1 条，总额 `100.00`，三个分组合计与表格、PNG 及 Plotly 数据一致。产物逐个核对了大小和 SHA-256，代码文件哈希也与执行收据一致。服务端证据确认只有一个不可变输入快照，任务经过原有技术修复和审查循环，没有为图表新增数据获取流程。

截图：[桌面报告](visual-report-20261021-evidence/desktop-history.png)、[Python 静态图](visual-report-20261021-evidence/static-chart.png)、[Plotly 交互图](visual-report-20261021-evidence/interactive-chart.png)、[手机报告](visual-report-20261021-evidence/mobile.png)。[联机验收](visual-report-20261021-evidence/stage-acceptance.json)、[执行审计](visual-report-20261021-evidence/execution-audit.json)和[最终服务检查](visual-report-20261021-evidence/final-health.json)均为通过，匿名下载图表返回拒绝状态，最终无活动父任务、Cline 会话或 Python 容器。临时登录令牌与本地 SSH 私密配置已清理。

完整上传分析在后端最终版本上通过；随后仅调整前端提交文本，使附件提问与界面保存内容一致，避免后台再添加一条没有附件注释的重复提问。302 项前端回归重新通过，最终部署后再验证同一报告的历史恢复，不重复运行分析。测试期间创建的五个旧会话已清理，保留最终样例会话和任务审计产物。

## 联机排障记录与已知边界

首轮 `qwen3.8-max` 生成 725 行程序，模型响应约 431 秒，超过现有 300 秒 Coding 回调窗口。第二轮更简短的程序仍遇到长推理（约 693 秒），`qwen3.8-flash` 的测试也未完成；超时/取消记录保留，没有伪装为成功或放大执行权限。最终图文验收使用环境中原已发布的 `qwen3.6-plus`，通过 API 的既有 `modelName` 参数指定，没有修改默认模型或后台模型配置。模型响应过慢仍会受既有回调和任务预算限制。

联机还发现接纳耗时最初被写入不可变证据，最终交付再次登记同一产物时，耗时变化导致哈希冲突。现已将该耗时保留在日志中，报告只保存 Python 原始计算中的稳定耗时；新增强制不同接纳耗时的字节一致性测试，以及数据库重开后的重复收集测试，均通过。

第一份完整交付的样例数值和图表均正确，但模型给未声明单位的 `value` 字段补了 `currency`。最终增加通用的单位来源和输出语言约束，并以真实联机报告核对，不添加金融或其他业务的固定模板规则。浏览器验收脚本也修正了登录初始化仅作用于顶层页面，避免在不透明 iframe 中误读 localStorage；隔离规则始终保留。

普通知识问答分别以 `off` 和 `visual` 在真实 Agent API、Cline 和 Python 链路完成，均返回文字答案，图文报告和图表数为 0。既有普通对话链路本来就使用 Python 序列化答案；这里验证的是报告开关不额外引入报告计算，而不是宣称原系统从不调用 Python。

## 变更入口

主要改动为 `VisualReportMode`、`ClineContextAnswerService`、`CodingAnalysisService`、`CodingEvidenceCollector`、`VisualReportArtifacts`、`AnalysisCoverageCoordinator`、`AgentTaskService`、`UiArtifactService`，以及前端 `PromptComposer`、`ChatAssistantView`、`ChatMessageList`、`PythonVisualReport`、`PythonReportChart` 和 UI Artifact 注册。对应测试、浏览器夹具与生成脚本随代码提交。完整[变更文件清单](visual-report-20261021-evidence/changed-files.txt)可对照基线标记与功能提交的 `git diff --name-status`。
