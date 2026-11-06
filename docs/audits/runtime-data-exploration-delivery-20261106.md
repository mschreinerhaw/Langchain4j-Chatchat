# Runtime OS 数据供给、探索与交付审计

审查基线：main `2db9b59df`，2026-11-06（Asia/Shanghai）。整改分支 `fix/runtime-data-exploration-delivery-20261106`。先记录源码事实，再进行独立修改。未变更共享模型、预算、权限或 SDK 版本。

## 根因矩阵

| 编号 | 证据等级 | 源码事实 / 影响 | 最小整改及验收 |
|---|---|---|---|
| DATA-01 | 已确认源码缺陷；线上影响待验证 | `CodingAnalysisService.run` 只在 `textResult && resourceAgent` 时添加资源工具；`CodingKnowledgeService.call` 又以 `permitsTextResult` 拒绝非互联网资源；Node `knowledgeTools` 同样要求 TEXT_OR_CODE。已有数据文件的分析任务即使获得资源授权，也无法继续自主探索。 | 将授权资源探索与报告提交方式解耦；只复用现有资源授权交集、v3 计算快照标记、原生 Custom Tools。验证文件分析、文本分析、撤权与无授权任务。 |
| DATA-02 | 已确认硬限制；不是“数据不存在”的证据 | MCP `ResourceExplorationReadController.readObservations` 固定查询 200 行，返回最多 50 行，offset 最大 150；底层 `FinancialDataStore` 默认 maxQueryLimit=200。当前快照是有界窗口，不能标为全交易日行情。 | 保留当前限制，明确截断与后续授权探索；全日快照需要独立受限读取契约、完整性证明和文件交付，不能提高普通查询上限冒充完成。 |
| DATA-03 | 已确认能力未接到 Cline | `FinancialDataStore` 已有 security_master、名称/全称查询、resolveEntityFilters；Cline market 工具只暴露目录搜索和精确 filters。 | 复用现有证券映射能力，保留市场和证券类型，不能仅以六位代码作为唯一身份；未命中返回明确范围与探索选项。 |
| SEARCH-01 | 已有能力；降级诊断缺口已确认 | `CodingInternetAccess.snapshot` 按模型、Agent/用户、Provider 配置交集加载；配置不可用和不授权都返回空 Map。`web_search/web_fetch` 已走 SDK Custom Tools、Java 网关和 MCP，保留证据。 | 复用现有探测接口；区分配置、授权、响应和查询有效性；不新增搜索循环，不自动启用共享配置。 |
| REPORT-01 | 历史确认症状；当前生成原因待验证 | 历史 `docs/testing/cline-native-skills-acceptance.md` 及 report-artifacts-r2 记录文本图片未归档；最新已有 `VisualReportArtifacts`、图表拒绝反馈和原生修复。Python 退出成功不能证明报告完整。 | 在原 results/manifest/停止证明路径验证缺报告、缺图、引用和归档；不扫描宿主任意文件。 |
| PATCH-01 | 已确认诊断缺口 | `prepareProgram` 支持 1..32 个 edits，每段 old/new 16000 字符；超长仅返回 Invalid bounded repair edit，无段号、长度或锚点定位。 | 保留限制和原生编辑，提供结构化位置/长度/锚点反馈，验证原子失败、版本、Hash 和内容。历史 28866 字符原件尚未找到，不以合成样本冒充。 |
| PERF-01 | 待验证风险 | 已有 coding-progress、runtime-model 和 execution clocks。历史性能记录模型不同、预算/输入不完全可比，不能证明 Flash/Plus 优劣。 | 相同快照/预算/授权/验收的多轮配对；缺失阶段保持 UNKNOWN，失败与未知 Token 单列。 |

## 已复核的链路与治理

1. Runtime 预授权模板：`PreauthorizedStructuredDataOperator` → `CodingDataSupplyService.request`；参数限定在 grant，校验 data.complete、requiredFields，再 captureForTask。该服务存在不等于已向所有 Cline 合约开放 data_request。
2. 文件：`CodingInputSnapshotService.captureConfigured/captureForTask` → 既有 CSV/Parquet 发布器/manifest → describe、input_read；SHA-256、字段、行数和真实文件路径由服务端生成。
3. 资源：`CodingKnowledgeService` 每次验证资源授权，`CodingResourceReader` 委托 MCP；行情/网页读结果可由 captureResource 冻结为新快照。
4. Python：`bindInputs` 固定每次 operation 的输入版本，`authorizes` 比较精确 fileId→Hash 清单；新数据不会静默改写旧执行。
5. 结果：既有停止证明、results 清单、Hash/大小、行 schema、VisualReportArtifacts、evidence bundle；没有实际归档证据不得宣称报告完成。
6. 元数据：SQL 工作空间已有 workspace_metadata 原生工具及 PostgreSQL/OpenSearch/JDBC 治理路径；这不证明数据分析任务已获得这些工具。不可将工作空间 SQL 验证权限直接变成查询执行权限。

## 历史证据和联机边界

- 复核 `docs/testing/cline-precode-e2e-acceptance.md`：最终账户任务 FAILED，367.2 秒、9 次模型、252376 Tokens、4 次 Python，最终 28866 字符补丁问题需取得原始工具 payload 后复现；既有不同模型任务不得作为本次性能对照。
- 复核 `docs/audits/cline-native-skills-remediation-matrix-20261106.md`：原生 SDK loop、Skills、PrepareTurn、Compaction、Checkpoint 已复用，不重建。
- 2026-11-06 从本机连接指定部署服务器 SSH 超时，尚无本次线上只读日志、原补丁、服务/搜索配置或模型运行证据。凭据不进入仓库。此限制不阻止本地回归，也不构成线上通过。

## 实施记录

各修复独立提交并在 `docs/testing/runtime-data-exploration-delivery-20261106.md` 记录命令、版本、通过/失败/未验收。未完成项明确保留，禁止将工具可见、Python 成功、报告交付和业务分析充分混为一项成功。
