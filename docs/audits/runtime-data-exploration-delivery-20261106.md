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

## 服务器恢复后的真实场景复核

原问题为“分析华是科技最新的股票价格行情走势”，Agent `financial-news-agent`，模型 `qwen3.8-flash`。以下结论来自原 journal、模型回执、父 Run 和实际文件核对，而不是合成重现：

- DATA-01 的另一已确认入口缺陷：外层传递 `authorizationAgentId`，资源装配仅识别 `knowledgeAgentId`。Agent 已配置四类资源，但该 Coding 合约没有任何资源探索工具。修复同时支持两个服务端身份字段，仍由原 Agent/用户资源交集决定每个工具是否可用。
- 原 Parquet 26 行、63,662 字节，manifest/schema/part 均存在且 Hash 一致。原 Sandbox 实际运行 4,698ms、exit=0，但未写 rows.jsonl；原代码只写 validation/diagnostics.json。不能将本场景定性为文件未交付或数据完全缺失。
- 输入含两个逻辑查询结果，各自交付相同的 8 个交易日行情，16 条行情记录并不代表 16 个不同日期。独立授权行情读取目前能取得目标代码 301218 的 16 个交易日，最新已收集日期为 2026-11-02；不能由此宣称 2026-11-06 实时行情。
- 原修复第三段 oldText=344、newText=28,866 字符，已通过原生 SDK 分成 15,000/14,026 两段的离线精确回归。该回归验证编辑、Hash、版本和原子失败，不等于模型报告已通过业务验收。
- Flash 和 Plus 目前均 `internetSearchEnabled=false`；Omni Flash 已开启。腾讯 WSA 已配置，最近状态为 PROVIDER_LANGUAGE_UNSUPPORTED。不能改写共享配置来制造 Flash 联网通过；必须分别验证关闭时降级及既有已授权模型的搜索路径。
- 日快照只证明单个存储数据源/数据集/交易日的有界完整读取，不证明采集端已覆盖整个交易所。普通交互查询上限仍为 200，日文件上限 10,000 行/8 MiB，超限拒绝、不截断；没有原生 Parquet 合约的任务不能调用此导出。

### 补充复核（2026-11-07）

- **REPORT-03，完整快照首轮真实触发**：Flash 第一轮已计算并生成 rows.jsonl 与三张图，但结果含输入上下文原文，`SOURCE_COPY_REJECTED` 正确拒绝，`rejectSourceCopy` 随即把整个委托提交为 FAILED，反馈变成 TASK_STOPPING，Cline 无法在当前授权/预算内删除复制内容并修复报告。对既有 `analysis-reviewed-path:v1` 且有 artifact_read 的任务保留拒绝，只返回布尔校验及技术修复建议，交回原生受限修复；非复核委托维持原父任务重规划行为。不返回被拒报告，不提高修复次数、不续预算、不改变原样复制检测。Callback/Feedback 31 项通过。首轮结果和旧版本已启动的第二轮取消证据保留，不当最终修复验收或性能改善。
- **DATA-05，已确认入口缺口**：文件分析 API 原 Request 无 Agent 授权身份，纵使底层允许计算任务使用资源工具，该入口也只能交付文件。新增可选 agentId，复用探索工作区的发布/用户访问校验，在读文件和模型解析前拒绝越权；传递既有资源授权属性，仍由每次工具调用核对实时交集。未选择 Agent 的行为不变；任务模型与 Agent 授权身份分别传递，不修改共享 Agent 模型。8 项控制器测试通过，包含未授权前置拒绝及选择模型/资源身份的独立传递。
- **DATA-04，已确认源码缺陷**：`AnalysisCoverageCoordinator.analyze` 对首批空数据直接抛出 DATA_SUPPLY_*，没有核对 Cline 是否仍有其他已授权资源。新增既有 AnalysisCodingPort 的授权预检，只读 Agent/用户/模型授权；有资源时交付 DATA_SUPPLY_STATUS 作为状态材料后继续原 Cline 路径，没有资源时保留拒绝。状态行不冒充业务数据，不增加预算、不在 Java 选择查询。
- **REPORT-02，真实复测确认**：Plus 合约技术 COMPLETED，但报告 requirementStatus=INPUT_REQUIRED、visualReport=NO_DATA，原前端仍显示“已完成”。保留执行终态和报告原件，单独显示“需补充数据”，不能用归档成功冒充分析完整。
- **验证材料缺口，不能当生产缺陷**：首个固定文件对照仅上传了原 Parquet 分片，漏掉原 manifest、schema 和输入上下文，模型只见 p0/c0/p1/c1。保留该批作为不完整输入的负向样本；停止尚未完成的同批测试，完整材料对照需同时冻结全部文件和现有 MCP 生产者字段声明，再对 Flash/Plus 使用相同内容。没有把这一批的安全 INPUT_REQUIRED 报告计作有效行情分析或性能改善。
- **已修复的发布回归**：首次候选 MCP 镜像漏带原 Docker CLI，首批任务在 Python 环境探测阶段失败，尚未进入 Cline。已从原容器复制精确二进制（Docker 26.1.3），校验 Hash 和离线 docker --version 后只替换 MCP；Python 3.11.18/环境版本7/原镜像摘要恢复。失败批次独立保留，发布脚本增加依赖校验，未提高环境权限。
