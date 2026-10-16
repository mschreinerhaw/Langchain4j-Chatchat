# Runtime OS AI Coding 数据分析第二阶段实施报告（2026-10-16）

## 结论与范围

本轮沿用第一阶段的 `CodingTaskContract`、`CodingAgentSPI`、`ClineCodingAdapter` 与同一个 Node Worker。Java Analysis Loop 继续控制取数授权和预算，Python Sandbox 执行计算，Cline 只接收任务、Schema、摘要和受控文件引用。新增大输入分片路径与结果分页读取；原有小输入路径保持默认。**选定门禁 G1/G2/G3 已通过，但仓库后端全量测试未通过，真实模型验收缺少有效凭证；第二阶段不能声明整体完成。**

## 容量实现

- 原限制位于 `CodingAnalysisProperties`：默认 `maxInputRows=100000`、`maxInputBytes=16 MiB`，由 `CodingAnalysisService` 传给 `CodingInputSnapshotService.capture`。超限会显式拒绝。
- 部署方显式提高任一输入限制时，改用 `captureChunked`：`DatasetHandle.scan(1000)` 流式读取，每片不超过 4 MiB，最多 64 片、100 万行、256 MiB；单行超过 4 MiB 或实际行数变化均拒绝。每片有 SHA-256、行数和文件 ID，末尾上传不可变 `manifest.json`，证据记录绑定 task、数据源、决策、工作区、完整数据哈希与分片列表。Java 不持有整份输入。
- MCP `PythonDataFileService` 对 Coding 内容寻址文件实行不可变写入；同一内容可幂等重传，冲突拒绝。每片上传最多尝试 3 次，取消检查覆盖分页扫描及每次上传。重启后重新扫描源数据，已上传的片按同一 ID 幂等复用；**当前尚未做到跳过已扫描页的精确游标恢复**。
- `CodingDataSupplyService` 和 Python 执行桥把 manifest 及所有片的 ID/哈希一起授权、挂载；Node 指示 Coding Agent 在 Python 中按 manifest 顺序读取并校验。模型上下文不包含完整原始数据。多数据源入口使用惰性 `PagedDatasetHandle`，不先合并成 Java 全量列表。
- 已归档结构化结果可用 `openResult`、`readResultPage`、`registeredResult` 按页读取，下载时限制声明大小、校验全文件 SHA-256 与行数，登记完整数据集及证据血缘。当前 `collect` 的首次结果归档仍使用内存解析，并受默认 4 MiB/1 万行结果契约限制；大输出端到端扩容尚未完成，必须显式拒绝，不能以分页接口已存在误称无限容量。
- 单机授权上限为 256 MiB/100 万输入行及既有 Python Sandbox 资源预算；更大输入需经已有授权计算服务处理，本轮没有新增绕过资源上限的路径。

## 旧测试兼容性与恢复缺陷

`ModelNativeAnalysisHarnessTest` 初始 30 项中有 25 项失败、2 项错误。旧用例以 `chat(String)`、无计划的纯文本及过期 v2 动作/发布语义为假设；测试夹具现适配结构化模型传输、有效分析计划和现行协议，30 项全部通过，未删除测试。测试过程中发现真实恢复缺陷：先恢复最终分析计划会改变第一轮 Prompt 指纹，使已存输出检查点不可达。`HarnessPlanState` 现从第一轮输出检查点重放计划，恢复用例再次通过。该变更需继续接受全量回归审查。

## 本机容量基准

| 数据与环境 | 首次构造 | 相同输入重入 | 说明 |
|---|---:|---:|---|
| 18,000 行、18,888,890 字节、5 片；定向测试，H2 分页句柄、模拟 MCP 上传 | 2289 ms | 236 ms | 超过旧 16 MiB 上限；首次含序列化、SHA、上传重试和 JDBC 证据登记。 |
| 同样数据；完整门禁中的已预热测试进程 | 144 ms | 112 ms | 受 JVM/JDBC 预热影响，仅供同机回归比较。 |

该基准由 `CodingChunkedSnapshotTest` 输出 `CAPACITY_BENCHMARK`。重入仍重新扫描源数据；以上并非远端网络或 256 MiB 压测。同测试验证瞬时上传失败后的重试、同一 manifest 的幂等恢复、取消和仅返回结构信息。`PythonDataFileServiceTest` 验证不可变文件写入与冲突拒绝。

## 验收与门禁

| 项目 | 结果 | 证据与边界 |
|---|---|---|
| G1 Python Sandbox | 通过 | 真实 Docker/Python、JDBC；见 `target/coding-gates/20261016-143613/gates.json`。 |
| G2 归档与证据 | 通过 | 真实 Docker 运行及归档校验，结果分页和数据集登记；同上。 |
| G3 Coding Agent 主链路 | 通过（模型替身） | 真实 Cline SDK/Node Worker、Python、SQLGlot 与 JDBC；模型和授权模板数据源为确定性替身。7 个金融故障场景及恢复/取消用例，见 `CodingAnalysisDockerGateTest`。 |
| 选定 Java 门禁 | 通过 | 39 类、366 项，失败/错误/跳过均为 0。 |
| Node SDK | 通过 | 22 项。 |
| SQL Studio 前端 | 通过 | 10 个测试文件、61 项；后端 `SqlStudioIntegrationTest` 30 项。 |
| Python Studio 前端 | 通过 | 2 个测试文件、10 项；后端 Python Coding 相关测试已包含在 Java 门禁。 |
| 前端生产构建 | 通过 | Vite 构建成功；存在既有 chunk-size 提示。 |
| 仓库后端全量测试 | **失败** | 最终门禁执行 610 类、3451 项、142 失败、64 错误、0 跳过：common 267 项全过；agents 1673 项/101 失败/43 错误；tools 19 项全过；mcp-server 949 项/12 失败；api 543 项/29 失败/21 错误。编排、工具参数、架构约束、发布接口等需另行排查；不能以选定门禁替代全量套件。 |
| 真实模型 | **环境阻塞** | 见下文。 |

最终门禁日志与矩阵在 `target/coding-gates/20261016-145630/`；`gates.json` 显示 G1/G2/G3、Node、前端通过，`fullBackend=FAILED`，脚本退出码为 1。全量后端首轮日志在 `target/coding-gates/full-backend-20261016.log`；继续执行其余模块的全量日志在 `target/coding-gates/full-backend-continued-20261016.log`。最终 206 条失败/错误用例及所属模块列于 `docs/runtime-os-ai-coding-phase2-failures-20261016.csv`，没有删除或跳过它们。门禁脚本现默认继续运行后端全量测试并将其失败写入 `fullBackend`；`-SkipFullSuite` 仅供快速选定回归，不构成完整放行。

## 金融分析指标：模型替身与真实模型分列

确定性模型替身的 G3 7 个场景（正常、字段类型、交易日缺失、重复、精度、收益对账、组合故障）全部完成任务。独立 Java 验算在正常场景通过（1/1），在 6 个故障场景均检出预置故障（6/6），正常场景未误报（0/1）；各场景消费 4 次模型调用、2 次代码执行、3 次工具调用。G3 测试类 10 项总耗时约 28.2 秒，非单任务时延；替身 Token 计数不代表真实供应商消耗。多数据集、动态补数、代码技术修复、会话恢复和取消由同一 G3 套件覆盖。

真实模型：dev 配置中的默认模型名称为 `deepseek-v4-pro`，OpenAI Compatible 连接指向配置的 DeepSeek 端点，凭证从 `CHATCHAT_OPENAI_API_KEY` 读取。当前进程没有该变量，也没有本地模型网关监听；最小未授权 HTTP 探测返回 401，仅证明端点可达，不证明模型可调用。Node Worker 的 `runtime-model.js` 已通过 Java `ClineRuntimeModelBridge` 调用现有 `ModelResourceRegistry`/`ConfigurableChatModelFactory`，无须新增供应商或把凭证传给 Node。**真实模型任务完成率、数值正确率、检出率、误报率、时延和 Token 消耗均未测得**；模型替身数据不得替代这些指标。

## 未完成项

1. 配置现有模型凭证并确认网关支持的模型名后，先跑最小模型调用，再跑真实 Cline SDK 金融分析用例并记录六类指标；不要把密钥写入代码或日志。
2. 排查后端全量套件的剩余失败，确定其与当前代码的关系；本轮门禁不能视作仓库全量绿色。
3. 如需大结果输出，改造 `CodingEvidenceCollector.collect` 的归档、Schema 检查与业务验算为流式处理，并对更大样本做真实 Docker/网络/磁盘容量测试。
4. 如需断点从源页继续而不是幂等重扫，增加持久游标和已提交片的完整性恢复协议。
