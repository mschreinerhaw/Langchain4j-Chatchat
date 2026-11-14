# Cline 专业报告体系与验收

## 边界与启用

本次仅扩展 Cline/Node Skills、共享前端资源和独立校验程序。Java 的分类、规划、附件处理、授权、预算、Silo 成果路径及接口保持原合同；没有增加 Java 的业务分析、Skill 选择、HTML 设计或图表生成职责，也没有报告编排引擎。

新增 `report-designer`、`chart-designer`、`financial-report-analysis`、`evidence-reviewer` 四个原生 Skills。复用已有 `runtime-report-delivery`、`python-data-analysis`、`financial-market-analysis`、`financial-visualization` 和 `frontend-design`。Cline 按任务自主加载指导，选择章节、指标与图表，不需要逐一执行所有 Skills。

`CLINE_PROFESSIONAL_REPORTS=true` 在 Node 上启用。默认关闭；SDK 任务的可信 context 可用 `professionalReports.enabled` 显式覆盖。原生部署按节点灰度，Executor 将其可信环境开关传入任务容器。未声明专业报告清单的旧报告、源码任务及无报告任务不进入新增报告检查。

服务器以源码构建 Node 与资源服务镜像；Java 未变更，无需重新编译 Java。任务使用独立 Docker daemon，部署程序校验其 daemon 身份并导入服务器构建的同一镜像摘要，随后更新 Executor 的镜像固定值。仅在无活动灰度任务时替换第二节点，第一节点保留旧配置。

## 复用组件与数据合同

`coding-agent-node/skills/report-designer/assets/` 是样式、图表脚本及版本的唯一编辑源。执行 `python scripts/sync-professional-report-resources.py` 同步到 Hub 镜像构建目录并更新 Skills 的字节哈希；`--check` 用于验证未漂移。报告资源版本为 **1.0.2**。Hub 提供版本路径、SHA-256、依赖顺序，继续复用已安装的 ECharts；新版本保留旧资产路径。

共享组件包括首屏封面与摘要、KPI、自由章节、ECharts 图表及数据表、结论引用、来源和附录。支持企业蓝和金融投研主题、手机布局及打印图表快照。没有可靠数值时不强制图表。Cline 可改写布局及表达，保留证据映射和必要的元信息即可。

`reportkit.build(model, authorized_output, echarts=local_file)` 在调用方已获授权的输出目录生成：

- `report.html`、`report_manifest.json`、`evidence.json`、`quality_report.json`。
- `charts/` 下的数据，以及复用的 `assets/`。
- `report_model.json`，保存可复现的章节、图表和验证声明。

这些文件通过原来的 `coding_node_delivery_selection.v1` 相对路径选择与不可变 Silo 归档交付。Managed Python 任务仍必须提供原有 rows/visualReport 等输出，不得以伴随文件替换已有合同。离线图表 JSON 内嵌，依赖为本地文件，不依赖 CDN 或 `fetch()` 读取本地 JSON。

报告 Skill 的 `prepare_report_assets.py` 通过已有任务 broker 获取目录中登记的资源，Node 对下载内容再次校验目录 SHA-256；未登记路径、跨任务预览及摘要不符的内容均拒绝。相同的本地文件不重复获取，不下载 npm 或公网依赖。`finalize_report.py` 复用原 `frontend-design/deliver.py`，实际完成桌面、手机及打印预览，保留原始 HTML 摘要及离线变换后的摘要，落地独立 HTML/PDF，并重算最终证据、资源及质量状态。两者属于可复用的交付工具，不决定分析方法或内容章节。

## 质量底线

Python 校验与 Node 交付校验分别执行。Node 在原有产物哈希检查后复算，不执行模型提供的检查代码，也不把模型填写的 PASSED 当作证明。

- 关联来源文件、数据集、字段、期间、口径及 SHA-256；检查来源覆盖和实际归档文件摘要。
- 复算求和、比率、价格变化、正值 PE 排序；用 `sourceInputs` 定位原始 JSON 单元格，防止计算正确但输入被替换。
- 检查图表统计口径、维度与来源；支持原值及共同起点 100 的源单元格复算。缺失值保持 null，不以零值填充、不连接断点。
- 显式的长期结论声明在观测不足时失败；错误排名、计算或来源摘要不符时拒绝专业报告交付。
- 长期覆盖从绑定的原始日期字段派生，核对实际日历跨度、唯一观测数及声明频率；不会把五年的 60 条月度观测误当作 60 个交易日。未绑定源日期的声明不能仅靠填写 windowDays 获得 PASSED。
- 未绑定来源的计算/图表、未完成的浏览器检查标为 INCOMPLETE。声称 PASSED 时，交付端要求绑定计算/图表来源，并要求浏览器记录匹配最终 HTML 摘要。
- 禁网浏览器验证全部系列数据、图表实例与尺寸、断点、控制台错误、桌面/手机溢出、首屏摘要和 PDF 图表。
- 灰度启用后，类型为 REPORT 的 HTML 缺少专业报告清单时拒绝交付；普通源码 HTML 和关闭开关的旧模式沿用原规则。

`quality_report.json` 中的技术 PASSED **不表示** 因果解释、投资价值或业务解释已获认证；`semanticReview` 保持独立。任意自然语言判断无法仅靠结构检查证明。外部观点必须保留原文链接与发布日期；未知不补写。

## 原银行报告的修正

真实原版来自既有归档任务的 `report.html` 和原始 `source-1.jsonl`，没有用合成银行数据代替。比较包的 `source.json` 保留五家证券的原始记录、交易所来源链接与字段；来源映射同时记录原始全量输入的摘要。

1. 原报告用 3—4 次行情观测及新闻片段给出“最近一年综合表现”排序，证据不足；新版取消长期收益、因果及投资价值排序。
2. 五家银行中只有平安银行存在最新 PE=5.84，其余四家缺失，且盈余口径未明确；无法判断其“PE 最低”或“具有安全边际”。
3. 沪市四家银行没有 11 月 12 日观测，平安银行该日记录有效。新版保留真实观测，并对沪市缺失保留断点。原脚本虽然存在使用紧凑日期格式的跳过条件，但输入实际使用 ISO 日期，该条件没有命中，不能据此认定原脚本实际删除了该日记录。
4. 新版只展示可复算的短期快照价格变化，明确复权/总回报口径未建立，不生成不可靠的年化波动、长期收益或虚构的 PE/PB/ROE。

## 样例与复现

`examples/` 包含真实银行改造对比、明确标注合成数据的客户资产分析、企业运营分析及 PE 错排回归样例。每个文件夹包含可离线打开的 `report.html`、伴随 JSON、截图与 PDF；真实银行原版为 `before.html`。客户资产和运营样例用于程序及版式泛化，不代表真实客户数据验收。

```powershell
python scripts/sync-professional-report-resources.py --check
python -m unittest scripts.tests.test_professional_reports
node --test coding-agent-node/test/builtin-skills.test.js coding-agent-node/test/professional-reports.test.js
node --test cline-agent-server/test/*.test.mjs
```

服务器复现：`scripts/deploy-professional-reports.py RELEASE [--activate]`；共享资源使用 `scripts/deploy-professional-report-resources.py RELEASE [--activate]`。源码摘要清单、服务器构建/测试日志和不含凭据的部署记录保存在 release 目录。回退使用对应的私有 compose/Executor 配置备份，恢复镜像及开关后按原 compose 项目重建第二节点；关闭开关即可停止新 Skills 分发及新增专业报告 admission。

测试结果及真实模型/Silo 回读记录见同目录证据文件。真实模型任务与静态夹具测试分别记录，不能相互替代。

## 交付与验收记录（2026-11-15）

服务器 `192.168.195.221` 使用 Git 源码字节在服务器构建镜像，第二节点灰度启用；第一节点保留原模式。应用部署提交为 `babd7ceb55e12f76cdfd776e646b4f7f88da9382`，资源版本为 1.0.2。最终文档提交另外包含测试脚本和真实产物，不改变已部署的应用代码。部署记录的 remainingAcceptance 是部署当时的待办快照，完成状态以这里的真实任务回读记录为准。

| 验收项 | 证据与结果 |
| --- | --- |
| 真实银行改造前后 | [原报告](examples/bank-original/before.html)、[新版](examples/bank-original/report.html)、[PDF](examples/bank-original/report.pdf)；不再把快照价格变化包装为一年收益，PE 缺失不参与排名 |
| 错误结论拦截 | [原问题结构化回归](examples/bank-original/before_quality_report.json)为 FAILED；这是两个已知问题的可执行回归，不声称能理解任意自然语言 |
| 首屏、移动、图表、禁网打印 | [四份样例浏览器检查](verification/examples-browser.json)验证实际系列值、图表实例数、尺寸、缺失断点、日志与溢出 |
| 客户资产泛化 | [静态样例](examples/client-assets/report.html)、[真实 Cline 交付](native-client-assets/report.html)、[归档回读](verification/native-client-assets.json)；明确标注合成测试数据 |
| 企业运营泛化 | [静态样例](examples/operations/report.html)、[真实 Cline 交付](native-operations/report.html)、[归档回读](verification/native-operations.json)；按运营问题组织章节，明确标注合成测试数据 |
| 独立程序校验 | [Node 重放](trusted-admission.json)重新计算归档哈希、指标及图表；[绕过回归](verification/kpi-bypass-regression.json)确认省略公式的未验证 KPI 被拒绝 |
| 资源复用 | [Hub 实际预览](verification/hub-preview.json)含桌面、手机、printChecked；[Skill 字节校验](verification/skill-integrity.json)验证全部 21 个包、63 个资源文件 |
| 兼容与回退 | 开关关闭保留原模式，源代码/无报告交付兼容；独立回退备份路径见[部署记录](verification/deployment.json)，私密配置不入库 |
| Java 轻量化 | 本次没有修改 Java 应用代码，复用原任务授权、产物选择、Silo 归档和 HTML 查看接口 |

最终自动化回归：原生 Node 运行时 124 项，121 通过、3 跳过；Skills 注册及兼容检查 5 项通过；Python 报告测试 8 项通过。合计 **134 通过、3 跳过、0 失败**，另有浏览器、Hub 和真实归档验证。跳过项属于原套件已有条件测试，不算通过。

两份真实 Cline 泛化任务的输入来自先前鉴权回读的合成数据：客户资产使用 CSV/JSON，运营使用保留三期观测的 JSON 快照。报告模型经审阅修正后，再由 Cline 完成 Python 计算、资源获取、真实预览、PDF、产物选择和 Silo 交付。因此它们验证可复用生成/交付闭环，不能冒充完全无提示的首次分析测试。

最初自主分析也用于发现并修复问题：一次客户任务因原执行证据目录缺少 record.json，归档重试直至 BUDGET_EXCEEDED；修复采用原子元数据、保留真实输出和明确的 EVIDENCE_INCOMPLETE 状态，没有补造命令或将失败任务改为成功。人工审阅还发现初版客户样例套用未证明适用的 HHI 等级、重复金额/比例柱状图；最终样例删除这些内容，Skills 加强阈值适用性与图表去重指导。

所有非空 KPI 都要求可执行源数据绑定；没有支持的检查时保留 INCOMPLETE，不通过删掉 formula 字段规避。技术 PASSED 不代表投资建议、因果解释或任意业务判断已获认证；这些内容需要证据审阅，不能用渲染成功代替专业判断。

离线交付需保留整个报告文件夹及相对资源路径。`report.html` 可直接打开，图表数据表和下载随包提供。原始归档文件保持字节不变，禁网复验在副本上执行，以免改写归档质量记录及 PDF。PDF 逐页人工检查见 [PDF 记录](pdf-review.json)。
