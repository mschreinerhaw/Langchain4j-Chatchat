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
| 数据供给 | `CodingAnalysisService`、`AnalysisCoverageCoordinator.analyze`、`CodingKnowledgeService.resource`、`CodingDataSupplyService` | `7a4583a82`、`b494e13e4` | CodingAnalysisServiceTest 58，Knowledge 36，Supply 13（1跳过），Coverage 30（1跳过） | R7 day 文件 2943 行及 Python 使用已证明；PG/OpenSearch 的空首批与精确未命中业务补测待做 |
| 行情匹配 | `chatchat-runtime-market/.../FinancialDataStore.resolveSecurities/findSecurityCodes/query`；`coding-agent-node/src/knowledge-tools.js` | `git log -- FinancialDataStore.java`及原审计 | FinancialDataStoreTest：`resolvesOfficialSecurityNamesWithSqlLike`、`supportsGovernedLikeFilterForUnindexedQuoteNames`等共21通过 | R7 原生 resolve；B 场景尚无明确零行工具回执→日文件→报告闭环 |
| 全日文件 | `FinancialDataStore.daySnapshot`；`CodingKnowledgeService`日导出分支；`JdbcRuntimeEvidenceStore.contentHash` | `b494e13e4` | 大源Hash、单证据2MiB拒绝、locator归档；实际fixture 68项67过1跳过 | `r7-autonomous-day-proof.json`、`r7-day-file-verification.json`通过；B 仍待补 |
| Search 探测 | `CodingInternetAccess.probe/require`、`PlatformModelController` | 既有Search整改提交 | `controlledProbeUsesOnePublicQueryWithoutLanguageAndNeverEnablesConfiguration`；Internet 8通过 | `r6-search-probes.json`；Flash/Plus关闭，Omni服务OK；实际服务故障状态仅有单测 |
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
