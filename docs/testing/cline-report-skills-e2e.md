# Cline 报告 Skills 真实验收

2026-11-04，环境 `http://192.168.195.221:18100`。本记录区分真实执行、自动化回归和未通过项；部署健康不代表所有业务验收通过。

**整体验收结论：未通过全部任务书要求。** 审查、改造、Skills 集成、部署及真实最小执行证据已交付；客户综合报告、新资源计算自修复、部分业务推理质量及超时后的完整收尾仍存在缺口。不得用单元测试、既有历史图表或 PARTIAL 状态替代完整业务验收。

## 执行与证据

`scripts/verify-cline-report-live.mjs` 使用真实认证、Agent/MCP、模型和 Python；`scripts/verify-cline-report-browser.mjs` 通过运行中的应用提交和正常历史入口重开，不注入报告。原始证据位于忽略目录 `target/cline-report-live/<case>`，包含任务、事件、模型回执、Python、报告、浏览器截图；登录材料仅存私有文件，不提交。`scripts/summarize-cline-report-evidence.mjs` 仅导出白名单元数据到 [证据目录](cline-report-skills-evidence)，不导出客户原始数据。

| 案例 | 真实任务 ID | 结论 |
| --- | --- | --- |
| A 华星实体区分 | `fbdf04fc-1633-3944-800e-da1bb34039ff` | FAILED；真实 MCP、4 次 Python，输出范围声明不一致，保留失败 |
| A2 华是亏损资讯 | `87c6891b-ee3d-3017-aa51-418b7af55c6c` | PARTIAL；5 次 Python，仍夹有无关公司与逐条清单，不计报告质量通过 |
| B 资源授权不足 | `15d18607-f7f6-4bb8-b42f-c81ee931eeae` | PARTIAL；实际只有知识文档权限，诚实说明无行情，未虚构图表；正常历史重开 |
| C 账户首轮 | `ee474911-b1d7-3c7b-9f72-a76f533ce955` | FAILED；真实模板调用、5 次 Python；末两次技术校验通过，预算耗尽前未明确复核；中文图表出现方框，不计可读性通过 |
| D 定性图文偏好 | `82b2eb2a-ee30-47a1-8c03-e631e08d2eac` | SUCCEEDED；3 次模型、无 Python/图表，真实 UI 展示完整文字并正常重开历史 |
| E 主动取消 | `1d1e43ca-85ec-4444-9f0a-82d9b5fd1fda` | 真实取消后 CANCELLED；历史恢复无成功报告、无伪造图表，浏览器无脚本错误 |
| B3 已授权业务工具行情 | `e8abe700-85db-4a8d-99c9-f8c2a906119c` | PARTIAL；2 次 Python；市场背景图表、完整报告与历史重开通过，个股行情缺失，不计个股完整分析通过 |
| C2 账户复验 | `3bb2c89b-9d75-3549-a9d5-80ef18df1698` | FAILED；4 次 Python，输出结构/程序错误未修复，不能算通过 |
| A3 UI 华是资讯复验 | `f2b558dd-e823-42d7-a9e3-6efcbc6c4c27` | PARTIAL；6 次 Python，仍为大篇原始清单；使用第三轮前端合同，不能代表新合同修复效果 |
| B4 授权资源行情 | `e765faa2-f478-461c-830a-73f731259304` | PARTIAL；新授权及真实读取成功，指数估值/行情观测为空，未执行 Python、不虚构图表，不能算行情分析通过 |
| C3 Plus 模型账户分析 | `03fdc3ce-2be4-46dd-b784-edbac7463d70` | PARTIAL；该 Agent 仅有市场工具，未取得客户数据；发现 Java 最终交付仍追加原始查询表格，已据此实施第五轮修复，不计 C 通过 |
| A4 华星资源资讯 | `871543ea-d3fe-4d84-a501-b8ee8c4385f4` | PARTIAL；5 次模型、无 Python；区分华星/华是/TCL 华星，明确目标证据不足，无原始清单；真实新合同及正文/历史展示通过，未完成目标公司实质分析 |
| A5 华是亏损资源复验 | `5e0ddd8b-cbc7-46ee-bf30-b39ec1e82e92` | PARTIAL；6 次模型、无 Python，已做实体核对与事件去重，正文/历史正常；人工发现 PE 公式错误及对应利润期间推断过强，不计业务质量完全通过 |
| B5 债券资源计算 | `d3c1eb42-bd6c-4f2f-869e-b527015e0e23` | FAILED；真实 market_get_snapshot/history、冻结输入与一次 Python 已执行，代码用逻辑字段直接访问物理 Parquet 列而 KeyError，后续未完成修复，无成功报告 |
| C4 原账户 Agent 复验 | `ca825929-0dea-3b26-9a34-d649a24c789f` | FAILED；模板选择模型缺少必需 status，数据供应拒绝，无 Cline/Python。请求虽指定 Plus，既有 Agent 固定模型优先，实际为 Flash，不得算作 Plus 复验 |
| C5 既有 IDS Agent 账户复验 | `fc1b02ed-f93a-4bb2-ba0e-8cce212feaf2` | CANCELLED；真实客户模板数据、3 次已结算 Cline 模型、1 次 Python，但结果合同失败；第 4 次模型请求超时后受控取消，未形成合格客户报告 |
| B6 新版市场图文复验 | `c884075b-bcbe-46c7-b517-8e83614b2bbb` | CANCELLED；真实市场模板数据，1 次 Python 字段错误；第 3 次模型请求超时后受控取消，不能证明第五轮业务端到端通过 |

首轮客户图表已通过真实用户资产 API 下载并校验：`trades_dist.png` SHA-256 `a1a9ba2c52883e9c0aa7a6c03f6f667805a6dfd4bcddc237cbf83173e888052f`；`top_holdings.png` SHA-256 `7e2752b9561aad9a44d4602f1f780454203d6b3f99a1e6a3ad3bf89d294c5ebd`。这是实际生成证据，不能作为字体或完整报告通过证据。

真实 A/A2 事件记录了 `SKILLS_AVAILABLE` 和 `builtin:runtime-report-delivery` 的 `SKILLS_LOADED`；A 也加载了一项既有动态技能。C2 真实模型加载 `builtin:python-data-analysis` 及官方原生 `builtin:cline:plotting`，虽任务失败，技能读取本身已被真实验证。不能据此宣称 analyzer/artifact-management 全部在真实任务中被选择。完整加载记录见每例 JSON。

C5 也实际加载 runtime-report-delivery、python-data-analysis、原生 cline:plotting 及三项已有动态技能；任务未成功，不能把加载成功视为业务报告成功。C5 使用已有 ids_data_audit 的 qwen3.8-flash 和相同客户模板工具，没有改 Agent、全局模型或权限配置。

起初 admin 的显式 DATA_RESOURCE 授权没有 financial_market/financial_news；数据库中的相关授权属于另一用户。用户随后明确批准，仅通过现有接口增加这两项 USER/ALLOW 授权，记录见 [授权证据](cline-report-skills-evidence/approved-resource-grants.json)。不改变权限代码或工具边界。B4 真实验证资源发现与读取，但由于观测为空，尚不能证明新资源读取→不可变输入→Python 正向链路，不能用单元回归替代。

真实最小链路证据已取得：B3 真实 MCP 数据、Python PNG、Runtime 报告图文展示及历史重开。图表 SHA-256 `184375fec059ef957b5c5bcfaeffe453275ba4f2554525e979dd5d7888ebffb8`，49,163 字节，中文已人工检查。浏览器 HTTP 200 加载 1200×600 PNG，无页面脚本错误。首次脚本缺少滚动，未触发懒加载而误判；修正脚本后通过，没有更改图表渲染组件。[实际 PNG](cline-report-skills-evidence/B3-python-chart.png)、[页面](cline-report-skills-evidence/B3-runtime-report.png)、[历史重开](cline-report-skills-evidence/B3-history-reopened.png)。此例仍有元素来源绑定警示及个股缺失，不宣称全部 B 场景通过。D 正文展示见 [截图](cline-report-skills-evidence/D-runtime-report.png)。

第五轮部署后再次通过正常历史入口读取 B3，结构化图文内容及图表均成功恢复，图片为 1200×600，首次展示及第二次重开都 loaded=true、无页面错误；当前截图与 B3.json 为该轮实测。文字 D 的 20 个 Markdown 标题、A5 的 8 个标题均出现在实际 DOM 文本中。B3 结构化图文与并行 Markdown 章节不同，Markdown 7 个标题中仅 3 个出现在图文 DOM，不宣称该页逐字完整显示全部 Markdown；它仍是展示完整性方面需要继续核验的差异。A4、A5 的 QUESTION 事件确认新前端合同要求综合分析，已不含无条件展示所有返回行的旧句。A5 的[页面](cline-report-skills-evidence/A5-runtime-report.png)与[历史](cline-report-skills-evidence/A5-history-reopened.png)是展示证据，不代表其中所有财务表述正确。

`sample_market_latest_movers` 等是已有维护查询的标识，源码 `chatchat-runtime-market/.../FinancialAnalysisQuerySamples.java` 实际 SELECT 已采集的 market_quote_daily、交易所等业务表，并非本次验收生成模拟行。本次没有写入行情或客户样例、替换 MCP 返回值、注入报告或手工代替 Cline 执行 Python。

## 性能实测

数字来自实际 Cline 模型回执；不含 Java 工具规划模型，不把不同问题/数据量的运行当作同条件速度对比。

| 案例 | Cline 模型调用 | 模型耗时 ms | 输入 token | 输出 token | Python 次数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 改造前华是历史任务 d6e12490 | 9 | 283655 | 230349 | 16715 | 3 |
| A | 10 | 299830 | 271850 | 17776 | 4 |
| A2 | 10 | 371695 | 266002 | 22257 | 5 |
| B 权限不足 | 7 | 91007 | 144517 | 5004 | 0 |
| C 首轮 | 12 | 500881 | 435343 | 58562 | 5 |
| D 定性 | 3 | 75837 | 46643 | 4161 | 0 |
| A3 | 12 | 414568 | 331516 | 24781 | 6 |
| B3 | 8 | 184914 | 238766 | 10635 | 2 |
| C2 | 10 | 371859 | 272842 | 47489 | 4 |
| A4 | 5 | 120494 | 99980 | 7037 | 0 |
| A5 | 6 | 207618 | 166631 | 12187 | 0 |
| B4 | 11 | 167188 | 321679 | 9198 | 0 |
| B5 | 10 | 203659 | 287543 | 11426 | 1 |
| C3 | 6 | 185313 | 134269 | 11014 | 2 |
| C5 已结算回执 | 3 | 311434 | 73479 | 17879 | 1 |
| B6 已结算回执 | 2 | 24353 | 34433 | 1372 | 1 |

历史基线接口 latencyMs 为 350651。当前失败重试较多，不能宣称性能优化已达标。Skills 继续按需读取，无第二套报告模型；`tool_result_review` 保留原有治理职责，Cline 结果仍从已归档产物交付。

B3 Runtime 阶段实测：problem_identification 6 ms、tool_discovery 85967 ms、plan_generation 2486 ms、plan_evaluation 10 ms、step_execution 233609 ms，其中 primaryCodingDurationMs=215780。该次 javaContentAnalysisModelCallCount=0，javaPostCodingExplorationSkipped/javaAnalysisRefinementSkipped 均为 true；证明该次没有 Java 额外正文分析模型，不代表所有任务都没有前置规划或工具治理。阶段原始数值由白名单导出至 B3.json。

C5/B6 表格只统计已归档结算回执。第 4/3 次模型请求超时，provider usage 未完成核对，不能把这些数字当作完整成本或总耗时。真实 MODEL_FAILED、取消请求及浏览器取消状态见 [超时与取消](cline-report-skills-evidence/E-model-timeout-cancellations.json)、C5.json、B6.json。22:01 读取时父任务 CANCELLED，但 Coding 子任务仍 RUNNING/CANCEL_REQUESTED、meteringComplete=false、保留一次模型预留，属于未完成的外部模型状态/用量核对；未擅自清理账本或将未知提供方状态标记为成功。

## 自动化回归与局限

- Java 原受影响五类共 120 项：119 通过，1 项既有 Docker 相关跳过；结构反馈相关两类 32 项通过。
- Worker 本地及服务器离线只读无网容器均 182/182 通过，包含输入冻结、资源授权、缺失图表、加载失败、取消/恢复和预算；属于自动化回归，不是真实业务证据。
- 既有报告 UI 回归 32 项通过；前端合同修复相关 ChatAssistantView/displayContractRegression 55 项通过；生产前端构建通过。
- 图文 receipt 传播相关 AnalysisCoverageCoordinator 24 项通过；第四轮完整 Maven package 成功。真实未认证产物下载两条入口 HTTP 401，缺失产物 HTTP 404，取消任务在 API 重启后仍为 CANCELLED，见 [E 访问与取消](cline-report-skills-evidence/E-access-and-cancellation.json)。
- 第五轮 AgentAnswerFinalizerEvidenceAnswerTest 49 项通过，完整 Maven package 成功；覆盖 Cline 拥有报告生成权时抑制 Java 追加原始表格，并保留证据附件。该回归不能替代部署后真实报告验证。
- B6 的模型超时使新版普通工具报告没有完成，因此 Finalizer 的新修复仍缺一次成功的新版业务端到端证明；已部署及回归通过不能替代该证据。
- 当前真实验证包含 MCP、Python 图表、文字报告 UI 与历史恢复、主动取消。额外错误注入场景没有全部在真实环境执行，不能标记全部 E 项通过。
- `target/cline-report-*.log` 保留各轮构建/部署/执行日志。源码审查、方案及部署回滚分别见 `docs/audits`、`docs/design`、`docs/deployment` 对应文件。
