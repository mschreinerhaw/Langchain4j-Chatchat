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
- 未绑定来源的计算/图表、未完成的浏览器检查标为 INCOMPLETE。声称 PASSED 时，交付端要求绑定计算/图表来源，并要求浏览器记录匹配最终 HTML 摘要。
- 禁网浏览器验证全部系列数据、图表实例与尺寸、断点、控制台错误、桌面/手机溢出、首屏摘要和 PDF 图表。
- 灰度启用后，类型为 REPORT 的 HTML 缺少专业报告清单时拒绝交付；普通源码 HTML 和关闭开关的旧模式沿用原规则。

`quality_report.json` 中的技术 PASSED **不表示** 因果解释、投资价值或业务解释已获认证；`semanticReview` 保持独立。任意自然语言判断无法仅靠结构检查证明。外部观点必须保留原文链接与发布日期；未知不补写。

## 原银行报告的修正

真实原版来自既有归档任务的 `report.html` 和原始 `source-1.jsonl`，没有用合成银行数据代替。比较包的 `source.json` 保留五家证券的原始记录、交易所来源链接与字段；来源映射同时记录原始全量输入的摘要。

1. 原报告用 3—4 次行情观测及新闻片段给出“最近一年综合表现”排序，证据不足；新版取消长期收益、因果及投资价值排序。
2. 五家银行中只有平安银行存在最新 PE=5.84，其余四家缺失，且盈余口径未明确；无法判断其“PE 最低”或“具有安全边际”。
3. 沪市四家银行没有 11 月 12 日观测。原代码同时排除了平安银行该日有效记录；新版保留该记录，并对沪市缺失保留断点。
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
