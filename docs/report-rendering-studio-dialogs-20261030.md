# 图文报告与 SQL Studio 弹窗整改

已修复原报告的排版引用与来源绑定问题、图表局部失败处理、流式更新覆盖问题，以及高级设置和模板发布弹窗样式。原计算校验、数据来源权限、文件完整性和安全渲染规则继续执行。没有新建平行报告组件、Agent Runtime 或 UI 框架。

## 实际根因

从目标环境读取了与截图标题、7 天观测数据完全对应的已停止任务 `analysis-coding-20131eae617cd98a663adbd33a4e620d2f9597991d51d8667d0c02e8a75fda26` 的原始输出及回执；没有重新执行分析或查询业务数据库。

1. 原排版 `report_layout.v1` 的第 4、7 个 block 使用“运行耗时趋势”“流程执行时间分析”这些章节标题作为 `ref`，而 `sections` 没有提供 ID。后端为章节生成 `section-1`、`section-2`，严格校验因此抛出 **`IllegalArgumentException: Unknown report element reference`**。严格校验器的复现堆栈见证据目录。
2. 原 5 个指标、1 张表和 1 个图表没有逐项 `dataRefs`，也不符合新版排版的来源绑定要求。原报告只有一个已通过引用范围检查的数据来源，具备明确、受限的恢复条件。
3. 原代码只要任一排版块失败就放弃整份作者排版，把排版问题加入分析警告并将状态降为 PARTIAL；前端又同时展示“部分完成”和“排版描述未通过校验”。这是两条笼统提示叠加的直接原因。
4. **原 PNG 并未丢失，也不是图表组件未注册。** 原 PNG 的 SHA-256 为 `a0b5e7e4ec9c90121aadabe838109c3549df6d965b76cfe530ac8e74ecda38b0`，与原回执一致。浏览器复放确认它在旧兼容布局中可以加载，但被排在多个 KPI 之后：同一 1200px 视口下图表顶端约从 901px 提前到 590px。不能把截图中的警告等同于 PNG 生成失败。
5. 高级设置使用 Teleport 挂到 body；原部分表单样式依赖 `.sql-studio` 祖先，无法作用到该原生 dialog，导致按钮和选择框退回浏览器默认样式。模板弹窗原本直接使用“关闭”文字按钮。

原始 `rows.jsonl` SHA-256 为 `294cf685a4cc0ecf75b67f072a574e9ed7479e543b591e4eb47436c593742a38`。原始私有导出保留在本地 `target/report-screenshot-original/`；提交的回归夹具只将来源标识替换为 `fixture:scheduling-records`，保留实际布局、文字、数值和原 PNG。

## 代码修改

| 环节 | 实际文件 | 修改 |
| --- | --- | --- |
| 严格契约与恢复 | `chatchat-common/.../ReportLayout.java`、`ReportPresentation.java` | 保留原 `validate`；增加逐块恢复，只有唯一章节标题才映射到已登记 ID。坏块保留诊断，不清空有效块；生成提示明确章节必须使用 ID。组件词汇仍为原 7 种，与 Schema 和渲染分支一致。 |
| 数据转换与准入 | `chatchat-api/.../VisualReportArtifacts.java` | 仅在报告来源恰好一个且已通过引用范围检查时继承来源，标记 `INHERITED_REPORT_SOURCE`；多源缺绑定仍失败，越权来源仍拒绝。保留计算检查和原始数值，区分分析状态与展示诊断。 |
| 单图隔离 | 同上 | 有效图保留；坏文件、格式、标题等错误转换为该图位置的安全占位和结构化诊断。可辨识的文件格式仍须完整通过 PNG/SVG/Plotly 原校验，不能只凭扩展名放行。 |
| 布局与状态展示 | `web-app/src/js/utils/reportLayout.js`、`ReportRenderer.vue`、`js/components/PythonVisualReport.js` | 局部恢复并保留全部未放置内容。恢复成功不继续显示失败横幅；局部组件错误不新增整份报告失败提示。旧 FAILED 排版可经既有、带权限及执行哈希校验的归档接口重新准入，不重跑任务。 |
| 图表渲染 | `PythonReportChart.vue`、`js/components/PythonReportChart.js`、`js/utils/pythonVisualReport.js` | 继续使用既有离线 Plotly iframe。按实际 Plotly 注册表识别不支持类型，使用相同已校验数据的有界表格替代；空图显示明确提示。记录加载、渲染及恢复事件。 |
| 流式/异步状态 | 上述两个组件脚本 | 文件 ID 不变但描述完成时也重新加载；取消旧请求并用序号隔离。过期归档复核结果不能覆盖后来的最终报告。 |
| 高级设置 | `SqlAssistantPanel.vue/.js`、`sql-assistant-dialog.css` | 原组件内分为模型配置、上下文配置；复用 `SqlSearchSelect`、既有折叠和图标，保留即时生效行为。新增局部 scoped 样式，使用既有设计变量，不改全局 CSS。 |
| 模板发布 | `SqlTemplatePublicationPanel.vue/.js/.css` | 使用现有 Lucide X 图标，透明低强调按钮、标题及无障碍标签、焦点样式；统一字段边框与间距，滚动时保留头部关闭入口。关闭事件、草稿/发布/版本行为不变。 |

逐文件路径及源码提交见 [verification.json](report-rendering-studio-dialogs-20261030-evidence/verification.json)。报告结构支持既有标题、章节及 Markdown 分级标题、文本、KPI、图表、表格、限制及来源；没有引入模型可执行的 HTML/JavaScript。原图表安全校验、SVG 外部实体限制、离线 CSP 和 iframe 隔离均保留。

## 对比与截图

- [修复前：原回执与旧版渲染代码复放](report-rendering-studio-dialogs-20261030-evidence/report-before.png)
- [修复后：实际 PNG、5 个指标及 7 行明细](report-rendering-studio-dialogs-20261030-evidence/report-after.png)
- [单个不支持图表转为数据表，其余折线/柱状/饼图保留](report-rendering-studio-dialogs-20261030-evidence/chart-local-fallback.png)
- [已部署前端的高级设置](report-rendering-studio-dialogs-20261030-evidence/settings-desktop.png)
- [已部署前端的模板发布弹窗](report-rendering-studio-dialogs-20261030-evidence/publication-desktop.png)
- [窄窗口高级设置](report-rendering-studio-dialogs-20261030-evidence/settings-mobile.png)

弹窗线上截图使用实际部署的 JS/CSS 和明确标注的受控接口夹具，不代表使用客户账号发布过模板。报告截图使用原始真实 PNG、实际 Java 准入后的清单及真实 Vue/Plotly 渲染，文件传输采用 HTTP 夹具，未使用图表 DOM stub。

## 测试结果

| 检查 | 结果与范围 |
| --- | --- |
| Java 发布回归 | 135 项通过、0 失败、0 跳过，包含报告准入、Evidence、计算校验、SQL 工程/元数据、真实 SQLGlot、Runtime 工作区；后续图表元数据边界补测 25 项通过（与前述套件重叠，不累计虚增总数）。 |
| 图文前端 | 45 项通过，覆盖局部恢复、危险内容拒绝、精确值保留、旧异步结果隔离、同 ID 文件描述更新及空图。 |
| SQL 前端 | 166 项通过，覆盖配置选择、已发布 Agent 过滤、模板操作、版本及关闭行为。 |
| Node | 128 项通过。 |
| 浏览器报告链路 | 原始 PNG 解码成功；5 个 KPI、7 行明细与 76.0 原值保留；折线、柱状、饼图的实际 Plotly 数据均为预期值；不支持类型局部转表格，空图明确提示；无外部请求或页面异常。 |
| 浏览器工作台 | 本地与已部署前端均通过补全、分页、焦点/滚动、侧栏、文档、Agent/模型、任务状态、Diff、历史及工程文件操作；所有业务接口为受控夹具，无生产写入或业务 SQL 执行。 |
| 构建与资源 | Vue 结构检查、正式前端构建、API/MCP 打包通过；生产首页及 7 个直接引用资源与发布 JAR 字节哈希一致。已有大 chunk 提示保留，没有提高阈值隐藏警告。 |

安全反例包括跨来源引用、未知排版类型/脚本、外部 Plotly 资源、主动 SVG、实体扩展、坏图片、错误图表字段、失败/未决计算检查。不会将这些检查简单删除来换取通过。

## 部署与边界

源码提交：`67769d07877241a8a8e56754afb8fabcd002a207`、`94992565b852e16b3ba835b31181ae53eb90e36c`。

环境：`http://192.168.195.221:18100`。最终 API 镜像为 `runtime-os-api:report-ui-94992565b-20261030`，运行包 SHA-256 为 `d88fd77b394fe42b6752d106a90e3afb55ff63efa2349a65c8e60b2bef409003`；回滚备份位于 `/opt/chatchat-releases/report-ui-isolation-20261030/backup`。API、MCP 处于 running（未配置容器 healthcheck），worker 为 healthy；首页与 7 个引用资源实测通过。源码、运行包 SHA-256、镜像、发布检查及回滚目录均见证据清单。API、MCP、worker 已按累计工作分支发布；随后图表异常元数据隔离仅更新 API。此前本地已提交的 SQL P0 是本次发布基线的一部分，不计作本专项新增代码。

发布前确认没有活动任务；使用原发布机制备份配置、数据库及旧容器，保持 worker 网络限制。检查确认原会话内容、消息索引数量和无关容器不变；没有覆盖用户 SQL 文件，没有运行客户查询，也没有重新调用模型生成原报告。

原样本的展示状态为 `RECOVERED`、产物校验为 `PASSED`，计算检查保持原来的 3 项 PASSED。原有数据范围警告和解读限制仍保留，因此总体记录仍可为 PARTIAL；这不再表示布局或 PNG 失败，也没有伪造业务正确性。

**原账号线上补验已完成：** 用户提供账号后，正常登录并核对为原报告所属用户。真实 HTTP 归档复核与图表下载均为 200，执行及源码哈希身份保持一致；PNG 为 82,050 字节，SHA-256 与原回执一致。浏览器使用线上发布资源与真实历史会话，未拦截或模拟 HTTP；通过原有 `App.selectConversation` 选择真实会话摘要，由现有逻辑加载详情、复核归档和渲染。实际图像解码尺寸为 1785×884，5 个指标及 7 行明细保留，顶部无布局失败提示，无页面异常。没有重跑模型、业务 SQL 或改写历史消息。登录口令及会话令牌仅留在本地忽略目录，不进入提交。证据见 [HTTP 校验](report-rendering-studio-dialogs-20261030-evidence/owner-http-verification.json)、[浏览器校验](report-rendering-studio-dialogs-20261030-evidence/owner-browser-verification.json)、[真实线上报告截图](report-rendering-studio-dialogs-20261030-evidence/owner-live-report.png)。

系统当前声明 `color-scheme: light`，没有现成暗色主题切换；本轮沿用浅色及既有变量，没有扩展全局主题。完整源码/测试和发布证据已归档，原账号线上只读复核已补齐；历史弹窗测试的接口夹具范围说明仍保留，不将其混同于真实模板发布验收。
