# Runtime OS / Cline 整改完成度审查报告

审查基线：`38c6894b8`，2026-11-07。此为新任务的先行审查提交，后续真实测试结果追加，不覆盖历史失败。PASS=本列已有通过证据；PARTIAL=实现或证据存在缺口；FAIL=明确失败；UNKNOWN=尚无足够证据。总状态按业务要求判断，不取各列最高状态。

## 完成度矩阵（补测前）

| 项目 | 代码实现 | 单元测试 | 集成测试 | 真实模型测试 | 最终状态 |
|---|---|---|---|---|---|
| 数据供给完整性 | PASS | PASS | PASS | PARTIAL | PARTIAL |
| 行情自主探索 | PASS | PASS | PASS | PARTIAL | PARTIAL |
| 全量行情回退 | PASS | PASS | PASS | PARTIAL | PARTIAL |
| Search API 探测 | PASS | PASS | PASS | PASS | PASS |
| Cline 自主搜索 | PASS | PASS | PASS | PARTIAL | PARTIAL |
| 完整报告生成 | PASS | PASS | PASS | PARTIAL | PARTIAL |
| 报告归档 | PASS | PASS | PASS | PASS | PASS |
| 超长补丁修复 | PASS | PASS | PASS | UNKNOWN | PASS（离线编辑验收） |
| 上下文优化 | PARTIAL | PASS | PASS | UNKNOWN | PARTIAL |
| 模型耗时优化 | PARTIAL | PASS | PARTIAL | FAIL | PARTIAL |

超长补丁要求为历史样本离线回归，未把离线 Editor 验证说成模型自主分段成功。数据链路已在一个原生整日任务证明，但缺少“精确未命中后”完整业务链；因此前两轮证据不能使此行直接 PASS。

## 代码、提交、测试和实际证据

路径以仓库根为准。既有完整证据目录：`docs/testing/evidence/runtime-data-remediation-20261106/`；[上一轮验收](../testing/runtime-data-exploration-delivery-20261106.md)保留所有历史结果。

| 项目 | 代码文件/方法 | 关键提交 | 测试用例/结果 | 实际证据及缺口 |
|---|---|---|---|---|
| 数据供给 | `CodingAnalysisService`、`AnalysisCoverageCoordinator.analyze`、`CodingKnowledgeService.resourceCall`、`CodingDataSupplyService` | `7a4583a82`、`b494e13e4` | CodingAnalysisServiceTest 58，Knowledge 36，Supply 13（1跳过），Coverage 30（1跳过） | R7 day 文件 2943 行及 Python 使用已证明；PG/OpenSearch 的空首批与精确未命中业务补测待做 |
| 行情匹配 | `chatchat-runtime-market/.../FinancialDataStore.resolveSecurities/findSecurityCodes/query`；`coding-agent-node/src/knowledge-tools.js` | `git log -- FinancialDataStore.java`及原审计 | FinancialDataStoreTest：`resolvesOfficialSecurityNamesWithSqlLike`、`supportsGovernedLikeFilterForUnindexedQuoteNames`等共21通过 | R7 原生 resolve；B 场景尚无明确零行工具回执→日文件→报告闭环 |
| 全日文件 | `FinancialDataStore.daySnapshot`；`CodingKnowledgeService`日导出分支；`JdbcRuntimeEvidenceStore.contentHash` | `b494e13e4` | 大源Hash、单证据2MiB拒绝、locator归档；实际fixture 68项67过1跳过 | `r7-autonomous-day-proof.json`、`r7-day-file-verification.json`通过；B 仍待补 |
| Search 探测 | `CodingInternetAccess.probe/require`、`InternetSearchProbeController` | 既有Search整改提交 | `controlledProbeUsesOnePublicQueryWithoutLanguageAndNeverEnablesConfiguration`；Internet 8通过 | `r6-search-probes.json`；Flash/Plus关闭，Omni服务OK；实际服务故障状态仅有单测 |
| 自主搜索 | SDK `web_search/web_fetch`、`CodingKnowledgeService`外部来源校验 | 原生工具整改提交 | `providerOutageDoesNotPreventIndependentInternalAnalysis`、权限撤回/配置变更等通过 | R6实际搜索官方来源，但BUDGET_EXCEEDED，无最终报告；C/D待闭环 |
| 报告修复 | `runner.js.readArtifact`、`CodingExecutionFeedbackService`、`ClineModelCallbackController` | `6dfed1c5c`、`3446602b8` | Callback21、Feedback10、Node全量255通过 | R5仅1/4完整，R7文本报告归档通过但单位/百分位措辞冲突；首轮完整生成不稳定 |
| 归档/页面 | `VisualReportArtifacts`、`ExplorationWorkspaceController.finish`、`ExplorationAgentBridge` | `59cd83094`、`dead6d61a` | Report29、Workspace23、Bridge13、前端144通过 | `r5-report-archive-proof.json`四图；`r7-browser-proof.json`四阶段、0execute；新八阶段/失败详情待审 |
| 补丁 | `coding-agent-node/src/code-repair.js`、`sdk-adapter.js` | 历史补丁整改提交 | `exact code repair preserves unrelated bytes and rejects stale or ambiguous anchors`；候选容器38通过 | `real-patch-regression.json`真实28866字样本分15000/14026，输出SHA精确一致；本轮再回放 |
| 上下文 | `runner.js.readArtifact`、`completion-context.js.technicalMemory/budgetGuidance` | `3446602b8` | 不可变拒绝报告重复两次只读取一次；新Python清缓存 | 未重建上下文压缩器；重复源码/无进展调用需新日志统计，UNKNOWN不记0 |
| 性能 | `runner.js`预算和deadline；`verify-runtime-data-scenarios.py collect` | `ec4e82069`、`2b88bcb6b` | 模型预算/截止/未知usage定向通过 | R5各2轮，3/4失败，一次usage未知；不符合各3轮，尚无最终Worker前后对照；纯阶段UNKNOWN |

`.../`类名定位以 `rg --files` 为准，报告补齐最终提交号；不把归档技术成功当作全部业务质量通过。

## 本轮问题清单与执行顺序

1. **B UNKNOWN**：沿用实际 source 38 / 2026-11-02 / 华是科技301218。使用真实精确查询对标识变体产生空结果，不删除记录、不改变生产路由。必须实际记录零行、同任务后续原生探索、同一已验证日快照、Python过滤和报告归档；人工给定日文件不能冒充自主发现。
2. **C PARTIAL / D UNKNOWN**：原生搜索调用已经发生，但没有完整受限交付；分别用既有允许/禁止联网模型，验证请求级可用性，不切共享开关。权限关闭与供应商故障须分别分类。
3. **报告 PARTIAL**：Cline原生修复能完成，但仍有价格单位与百分位措辞问题；不得改写原始报告来制造通过。补测核对报告所有关键数值、图表与限制。
4. **性能 PARTIAL**：至少各三次，同快照/权限/预算，明确最终运行版本。未知usage与不可拆分阶段保留UNKNOWN；失败更快不算优化。
5. **页面 PARTIAL**：四状态有真实浏览器证明，需核对提交/发现/交付/分析/执行/报告生成/成功/失败及具体文件错误。
6. **资源约束**：服务器 /opt 余量约3.3GiB，根分区约22GiB。材料放/var/tmp；发布前检查，无需修改存储阈值。未证实缺陷不先做生产修改。

先本审查提交，后最小修复及离线回归，再做真实任务。各轮intent在IO前持久化；未知响应只轮询、不重放，不提高预算。新任务书要求的六份交付文档分别记录，核心场景未通过不宣布整体完成。

## 最终完成度矩阵

**总验收不通过。** 首行矩阵保留的是补测前基线，不是最终结果。先行矩阵提交为 `e72ad1aa5`；本轮实际定位后再独立修改，最终已部署源码 `e2a97cfacd99d44d528b988cf126f0e96bcec155`，应用SHA `acc584fa86aa9ee79064bef55e2dd571a56e40072ca02c105d91feecacf0fa41`。三轮模型对照在中间版本C63完成；最终版本随后补只读进度和停止收尾，不冒充做过最终版本六轮性能重测。

| 整改项目 | 代码实现 | 单元测试 | 集成测试 | 真实模型/实际验证 | 最终状态 |
|---|---|---|---|---|---|
| 数据供给完整性 | PASS | PASS | PASS | PASS（本样本） | PARTIAL：其他真实源路径未逐一覆盖 |
| 行情自主探索 | PASS | PASS | PASS | PASS：B2零行后自主继续 | PASS（本证券/市场/日期） |
| 当日全量回退 | PASS | PASS | PASS | PASS：2943行、Hash、Python及历史报告 | 探索计算PASS；当前交付PARTIAL，不代表交易所采集完整 |
| Search API探测 | PASS | PASS | PASS | 可用服务/配置关闭PASS；真实宕机UNKNOWN | PARTIAL（故障在线覆盖不全） |
| Cline自主搜索 | PASS | PASS | PASS | 工具自主调用PASS，C3完整链FAIL | PARTIAL |
| 完整报告生成 | PARTIAL | PASS（契约） | PASS（契约） | B2文本PASS，C及六对照稳定交付FAIL | FAIL |
| 报告归档 | PARTIAL | PASS | PARTIAL | B2初次读档PASS，目录刷新后403；D3最终复读PASS | PARTIAL；B当前不可交付，本轮完整E为FAIL |
| 超长补丁 | PASS | PASS | PASS（实际SDK） | 历史离线PASS，新真实分段提示可见PASS；模型自主修复未成功 | PASS（离线验收范围） |
| 上下文优化 | PARTIAL | PASS | PASS（既有缓存） | 无可靠收益对照；重复读取/重写仍发生 | PARTIAL |
| 模型耗时优化 | PARTIAL | PASS（计量/预算） | 输入权限预算可比性PASS | 各三轮，完整报告均0/3 | FAIL |
| 页面状态 | PASS | PASS（149项） | PASS（真实浏览器） | 四事实、部分/失败、具体路径PASS；八态逐时在线全覆盖未做 | PARTIAL（覆盖范围） |
| 停止与对账 | PASS（有界收尾） | PASS（组合86项） | 原预算恢复及真实主动停止PASS | 新版本主动停止1431ms终态；相同到期迟到时序仅离线复现 | PARTIAL（精确在线分支尚未重复） |

## 根因矩阵与最小修改

最终只读复查又发现来源时效边界：B2的19项来源中18项规范化Hash一致，唯一变化为证券目录候选的 `items[0].source_refreshed_at`，从2026-11-05T21:23:25Z更新到2026-11-06T21:23:35Z，其余候选字段相同。`KnowledgeDisclosureGuard.checkRun`重新校验整个生产任务，`CodingKnowledgeService.validateResourceReference`严格比较来源Hash，导致当前报告下载403/SOURCE_CHANGED。历史探索、Python、报告及首次归档校验成功仍成立，但不能据此认定当前可交付；B端到端最终为PARTIAL。尚未证明这是应忽略的非版本时间戳，不删除时间字段、不绕过鉴权或改写旧证据。D3最终复读200且Hash一致。[最终复读与来源比对](../testing/evidence/runtime-cline-acceptance-20261107/final-report-readback-proof.json)。

| 编号 | 已确认缺陷/现象 | 具体证据 | 修改、用例与结果 | 剩余边界 |
|---|---|---|---|---|
| DATA-08 | 原生与外层Parquet同用cN物理名，未明确约束映射范围和原始manifest绑定 | 真实D正确Hash仍trade_date KeyError | `CodingInputFilePublisher.nativeSourceManifest` / `CodingInputLayout.describe`，`89f11de98`、`832fdcf56`；78项契约回归（含真实2943行重叠列名fixture）PASS，六任务当前挂载绑定均PASS | 有正确映射不保证生成代码使用正确 |
| SEARCH-STATUS | 空internet快照没有解释未配置/关闭/未授权原因 | D2有报告却未说明Search关闭 | `CodingInternetAccess.availability`、`CodingRuntimeContext.validate`、`CodingAnalysisService`，`6a0f026ea`、`c2b09b8e7`；`localAnalysisReceivesNegativeSearchEvidenceWithoutAnInternetToolGrant`等通过，D3真实报告明确MODEL_INTERNET_DISABLED | 状态元数据不是实际服务探测，也不授予工具 |
| CITATION-REPAIR | 拒绝报告的反馈缺URL到精确证据ID映射 | C2有计算输出仍反复无效引用直到预算耗尽 | `ClineModelCallbackController.readArtifact/citationRepairSources`，`9ec3a9d76`；`prioritizesAlreadyReferencedUrlsWithinTheBoundWithoutReturningBodiesOrCredentials`及撤权测试通过，组合100项PASS | C3在语法修复阶段失败，未证明该改动使模型闭环 |
| UI-EVENTS | 文件任务有durable日志但没有analysisEvents；历史终态和页面缓存不补查 | B2有261事件，旧页面只能显示响应待核对 | `CodingAnalysisService.progressEvents`、`ExplorationWorkspaceController.status/finish`、页面`pollStatus`，`d14b694a7`、`8aecb2635`、`8f99b99be`；当前任务只读投影、终态报告不变、无SUBMIT权限回归通过 | 只保留最近256事件，压缩/缺记录明确UNKNOWN |
| UI-FAILURE | 阶段报告覆盖当前执行阶段；终态总括消息盖住缺文件反馈 | 实际失败/阶段产物页面 | `explorationLifecycleStatus`，`c63e70532`、`833e02bf0`、`38303f4f5`；真实失败页35事件、精确rows.jsonl路径，0execute/0页面错误 | 未把所有生命周期阶段都在线逐帧覆盖 |
| STOP-RACE | 截止后只立即恢复一次，Worker稍后停止造成控制面留在RUNNING | Flash2截止20:47:10Z，Worker20:47:11Z停止，原节点恢复后才终结 | `CodingAnalysisService.reconcileStopping/cancelPrimary/run`，`e2a97cfac`；`ownedStopWaitsForALateProofWithoutGrantingSubmissionOrReplayingWork`、到期旧预算延迟证明等86项通过；实际恢复契约/预算/操作不变，新增模型/代码0；新版本真实主动停止PASS | 超过有限等待仍必须保留需对账；具体新截止时序在线补测未做 |
| MODEL-OUTPUT | 生成代码错误、诊断零退出无报告、修复提议无效/超长 | 六任务0完整报告；Plus3只有两个未关联图表草稿；C3初始源码本身SyntaxError且提交/执行SHA一致 | 保留原SDK编辑、严格输出/引用/归档契约，不代写报告、不放宽验收、不扩预算；真实24182字符拒绝含分段、锚点、Hash、版本等完整信息 | 稳定业务交付FAIL；不能凭这些失败推断SDK丢上下文或Runtime传坏源码 |

已知sourceHash误用2MiB证据上限的缺陷在先前 `b494e13e4` 修复，本轮真实B2再次证明完整日文件可用。此次没有发现本样本精确零行导致Java直接终止后续探索。其他数据源/别名覆盖、真实供应商故障、纯规划/代码/报告阶段耗时仍需更多证据，不能推定通过或推定缺陷。

测试摘要含完整原始日志Hash，日志保留在忽略的target目录；部分历史运行未捕获当时HEAD，明确标UNKNOWN，相关提交仅用于实现沿革，不冒充运行时版本证明。部署源提交及195个交付文件Hash另由bundle和服务端JAR核对。[证据目录](../testing/evidence/runtime-cline-acceptance-20261107/)。

可定位源码索引（路径以本仓库为准）：

- 供给及治理：[CodingAnalysisService](../../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/analysis/CodingAnalysisService.java)、[CodingDataSupplyService](../../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/data/CodingDataSupplyService.java)、[FinancialDataStore](../../chatchat-runtime-market/src/main/java/com/chatchat/runtime/market/storage/FinancialDataStore.java)。
- 文件：[CodingInputFilePublisher](../../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/data/CodingInputFilePublisher.java)、[CodingTableCodec](../../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/data/CodingTableCodec.java)、[CodingInputLayout](../../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/cline/CodingInputLayout.java)。
- 搜索及引用：[CodingInternetAccess](../../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/knowledge/CodingInternetAccess.java)、[CodingRuntimeContext](../../chatchat-common/src/main/java/com/chatchat/common/runtime/coding/CodingRuntimeContext.java)、[InternetSearchProbeController](../../chatchat-api/src/main/java/com/chatchat/api/model/InternetSearchProbeController.java)、[ClineModelCallbackController](../../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/cline/ClineModelCallbackController.java)。
- 反馈与页面：[CodingExecutionFeedbackService](../../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/execution/CodingExecutionFeedbackService.java)、[ExplorationWorkspaceController](../../chatchat-api/src/main/java/com/chatchat/api/exploration/ExplorationWorkspaceController.java)、[explorationWorkspace](../../chatchat-api/web-app/src/js/utils/explorationWorkspace.js)、[DataExplorationView](../../chatchat-api/web-app/src/js/views/DataExplorationView.js)。
- 原生编辑、上下文及回归：[code-repair](../../coding-agent-node/src/code-repair.js)、[sdk-adapter](../../coding-agent-node/src/sdk-adapter.js)、[runner](../../coding-agent-node/src/runner.js)、[真实补丁回放](../../scripts/verify-runtime-failure-repair.mjs)。

## 六份交付与后续问题

1. 本完成度审查及根因矩阵。
2. [数据供给与行情探索端到端验证](../testing/runtime-cline-data-e2e-20261107.md)。
3. [完整报告、图表、页面和停止交付验收](../testing/runtime-cline-report-delivery-20261107.md)。
4. [真实28866字符补丁离线回归](../testing/runtime-cline-patch-regression-20261107.md)。
5. [Flash / Plus三轮质量、时间和Token对照](../testing/runtime-cline-performance-20261107.md)。
6. [未完成问题及后续整改清单](runtime-cline-open-items-20261107.md)。

成功真实样本B和所有失败样本均保留；旧四图、D的受限报告、取消回归及单测通过不能补齐A/C/E和性能失败。未修改共享模型、Token预算、数据授权、SDK版本或资源保护阈值，未新建搜索Agent、代码编辑器、压缩器或业务分析编排引擎。每项产品修复独立提交；本轮不宣布整改完成。
