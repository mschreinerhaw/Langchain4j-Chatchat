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
