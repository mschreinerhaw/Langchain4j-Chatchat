# Native Cline 稳定性审查与整改记录

基线：2026-11-09 fetch 后的远端 `feature/runtime-cline-cli-native-20261107` HEAD `8a73b9897d970f366668be9cf7af80b8aa6b1c47`，本地一致、工作区干净。旧报告只作为索引，以下结论重新读取当前源码与真实 Journal。

用户确认门槛：六类各五次，共 30 次，**30/30 业务成功**，产物、独立验算、受影响回归均须全部通过。不得以旧版本、旧成功、单测或浏览器只读回放补足分母。相同根因连续失败后先停止该场景；未执行项单列，不能称完成 30 次。

## 第一阶段：源码和证据复核

| 范围 | 当前源码与真实证据 | 判断 / 后续整改 |
| --- | --- | --- |
| 输入来源 | AssetAnalysisController 从当前 owner 读取实际文件并校验服务内 Hash；封装完整原始字节分块。CodingInputSnapshotService / CodingInputFilePublisher 固化任务与原始文件，Native delivery 下载并再次校验 SHA | 原始文件、外层表封装和逻辑引用是三种表示；不能把 Base64 记录数当业务行数 |
| 输入路径 | Native 的 `inputs/{fileId}/{fileName}`、Python 的 `/data/input/{fileId}/{fileName}` 和逻辑 datasetReference 分开；J1 的路径报错说明已存在 | 继续校验描述符身份、大小和传输完整性；不能仅靠模型提示正确 |
| 行情工具 | Runtime 的 CodingResourceReader.TOOLS 和授权清单有 `market_resolve_security`、`market_get_day_snapshot`，但 knowledge-tools.js 定义表缺少两者，Native 直接复用该表；能力声明也遗漏 | **P0：声明/授权/实际工具定义不一致。** 只在 Native 适配中补齐既有已授权操作，不新增金融权限 |
| J2 RuntimeError | ExecutionFailureClassifier 未识别 RuntimeError，真实故障样本因此 UNKNOWN/STOP；Native 现在确实遵从 STOP | **P0：普通提交源码异常不能一概视为远端状态未知。** 仅对有真实停止、绑定源码和可确认来源的技术错误处理；超时、取消、权限、OOM 和未知来源继续 STOP |
| 合同文本 | 通用分析附加大量解析、Workbook、金融含义、SQL、图表、Review 等说明；CSV 特例才精简。Native 仍重复序列化 delivery.context/files 及配置 | **P0：统一精简的 Native 核心合同及按需上下文。** 保留原始用户需求与授权，保留完整结构化数据/声明，不靠截断删掉约束，不修改 SDK 默认合同 |
| 完成协议 | Native submit 要求实际工具调用、已停止执行与结果复核；Java nativeBusinessCompletion 对 businessValidationPassed=false 返回 PARTIAL | 已有真实闭环，不能重新包装为新成功。但 finish 响应只有状态和三个引用，没有显式 validationStatus；Native 接受完成也只检查引用 |
| 完成确认 | runtimeTools.verifiedReceipt 允许缺失 resultAvailable/validationStatus，因使用 `!==false`、`!=='FAILED'` | **P0：显式区分“可复核的已归档结果”和“已通过独立验算的完成结果”。** 缺失字段不默认通过；保留 PARTIAL 的真实归档能力 |
| 业务验证 | 注册的通用 verifier 是 STRUCTURAL_ONLY/INCONCLUSIVE；只有显式单 CSV count/sum verifier 独立重算数值 | **P0 / 验收缺口：** 其他场景不能因程序退出 0、结构合格而计业务成功。必须建立与真实需求对应的可信验算，否则如实记录未通过 |
| 图文产物 | 所有分析合同 requiredFiles 当前固定 rows.jsonl；用户文字中要求 PNG/Excel 不等于形成机器可核验的必需文件合同 | **P0：** 复核既有归档和任务文件约束，缺少必需产物不得认定完整交付；不开放任意路径 |
| 旧状态 | 历史未决 Native 记录及供应方终止/用量未知仍需保留；新验收只接受全新 requestId | 不重放旧请求，不把旧 SDK Checkpoint 当 Native Session |

## J1—J4 上下文实测

来自四个实际 run.json 和 task.input，不是重建的示例文本：

| 尝试 | Objective 字符数 | 完整 Native 输入字符数 | 原结果 |
| --- | ---: | ---: | --- |
| J1 | 21350 | 57843 | BUDGET_EXCEEDED |
| J2 | 21339 | 36303 | BUDGET_EXCEEDED |
| J3 | 21423 | 36384 | BUDGET_EXCEEDED |
| J4 | 2235 | 17198 | COMPLETED |

J4 说明精简有效，但仍有输入与元数据重复，且不能证明通用任务稳定。逐字段字符、UTF-8 字节及 Token 估算保存于本轮 evidence；估算采用 ASCII/4 + 非 ASCII/1.5，仅为上下文比较，实际计量以 Runtime 模型回执为准。

## 数据可用性初查

通过既有 Runtime 登录及 owner 资产接口读取授权清单，原文件保存在测试机私有证据目录。已有真实行情 CSV 和原始 Parquet；两个证券盈亏流水文本并未包含目标客户号 070200046604，不能作为该账户的实际输入。

既有 `resource_exploration_acceptance_20261104` 已授权 financial_market 只读资源；金融业务 Agent 列出了绑定工具，但当前 resolvedToolNames 为空。需继续区分业务工具绑定不可用、资源访问授权、数据存储覆盖和实际空结果。MCP `market_search(query="")` 返回空，带行情/market 查询能返回目录；不据此断言行情观察为空。未修改 Agent 授权、共享模型或数据源配置。

本文件是审查与整改过程记录，**尚不是 30 次业务验收通过报告**。部署和固定版本验收开始后另附逐次证据，未完成条目不能提升为通过。

## 已确认问题与整改边界

| 问题 | 定位 | 本轮处理或剩余条件 |
| --- | --- | --- |
| Native 行情工具许可与实际定义不一致 | Runtime 授权目录包含两个新只读工具，legacy helper 的 definitions 缺失 | 在 Native runtime-tools 注册证券目录与全量日快照，不变更 SDK helper 或授权 |
| J2 显式 RuntimeError 被归为 UNKNOWN/STOP | shared classifier 保守处理，API 原先没有 Native 源码证据分类 | 仅对真实停止、提交代码 Hash 一致、脚本栈与显式 raise 行一致的 Native 操作认定程序错误；其他 STOP 不变 |
| J1/J3 泛化合同及上下文重复 | 通用目标约 21KB，包含无关工作簿/SQL/泛化说明；输入多次序列化 | 精简 Native 核心合同；完整元数据保留 runtime-inputs.json；按实际许可附加资源说明；SDK 合同保持原路径 |
| 结束/恢复缺少明确业务验证字段约束 | Native receipt 与恢复代码 | 要求显式 resultAvailable；业务 COMPLETED 还要求 PASSED 和 businessValidationPassed；INCONCLUSIVE 仅供结果审阅 |
| 必需附加文件没有逐项强制检查 | CodingEvidenceCollector 只强制主 JSONL | 既有资产入口接受显式 requiredArtifacts，绑定指纹和合同；逐项核验真实 manifest 中 Hash 已验证文件，缺失不提交证据包 |
| 目录与实际查询不一致 | MCP 搜索返回 market_quote_daily，快照读取返回 Unknown financial dataset | 实际回执已留存；不能解释为空行情，不能用本地历史行情冒充当前资源查询成功 |
| 通用独立验算不覆盖六类业务 | 通用 schema verifier 只提供 STRUCTURAL_ONLY | 只有显式单 CSV count/sum 已有可信数值复算；多文件、行情、账户的完整业务验算仍为阻断，不能修改预期或伪装 PASSED |

新鲜授权预检：证券目录返回华是科技/301218/SZSE；现有真实行情 CSV 的精确证券名/代码匹配 16 行（共 26 行），与“文本包含证券名”区分。全量日快照要求精确 source_id，不能猜测来源。当前账户资产仍未发现目标客户号。未发起任何公共搜索，未改变已有 Agent 数据授权。

历史对账：两条 Native UNKNOWN 对应 Runtime 均为 BUDGET_EXCEEDED，Runtime execution 列表均为空且 Worker 停止。其一 Native 仍保存未决 operationId，另一个模型用量仍有 reservation；保留原记录，不能仅依据空 execution 列表强制清除 UNKNOWN，也未重新执行原任务。

本轮门槛由用户明确为 **30/30 业务成功**；六类各五次，账户仍为 070200046604。完整产物、独立验算和受影响回归要求全部通过。真实预检、单测、历史成功不进入这一新固定版本分母。
