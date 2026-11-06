# Runtime OS 数据探索与报告交付整改验证

日期：2026-11-06，Asia/Shanghai。基线 main `2db9b59df`，分支 `fix/runtime-data-exploration-delivery-20261106`。先提交[审计与根因矩阵](../audits/runtime-data-exploration-delivery-20261106.md)，再分别修复。未修改共享模型、Agent 资源授权、预算或 SDK 版本。

## 本地验证

| 范围 | 结果 | 复现 |
|---|---|---|
| 真实超长补丁 | 原第三段 28,866 字符；分段 15,000/14,026 后 SDK 输出与预期 SHA-256 均为 `0b0901cbfc188fa3bcbc463cc9c3ee53ec724ccec97190cf9d6bf08e70eb1242`；原子拒绝、版本、旧 Hash、恢复通过 | `node scripts/verify-runtime-failure-repair.mjs PRIVATE_DIR OUTPUT.json`；[脱敏回执](evidence/runtime-data-remediation-20261106/real-patch-regression.json) |
| Node 原生 SDK / 授权 / 恢复 | 全量 253 项通过，0 失败/跳过；新增搜索能力描述后定向 38 项通过 | coding-agent-node 内 `node --experimental-strip-types --test --test-concurrency=2 test/*.test.js`；定向为 knowledge-tools/code-repair/sdk-reuse-regression/code-lifecycle |
| Java 授权与输入/报告 | API 152 项，151 通过、1 条既有条件测试跳过；行情存储 21、MCP 资源 6 项均通过；无失败 | reactor `-Dtest=CodingAnalysisServiceTest,CodingKnowledgeServiceTest,CodingInternetAccessTest,CodingDataSupplyServiceTest,FinancialDataStoreTest,ResourceExplorationReadControllerTest,VisualReportArtifactsTest,CodingExecutionFeedbackServiceTest` |
| 前端 | 11 文件、139 项通过；生产构建通过 | web-app 内 `npm.cmd run test:exploration` / `npm.cmd run build` |

Java 命令为 `mvn.cmd -o -pl chatchat-api -am test` 加上述 `-Dtest`，以及 `-Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dfrontend.skip=true -Dfrontend.install.skip=true -Dfile.encoding=UTF-8`。补充输入引用/文件回执后单独重跑 CodingExecutionFeedbackServiceTest。跳过项为已有 `coding.sqlglotPython` 系统属性条件测试，本机没有对应 Python；不算通过。

保留本机 `target/runtime-data-remediation/` 原始失败及重跑日志：首轮新增 H2 测试缺少 throws 声明、反馈测试 Map 泛型编译错误均修正后重跑。首次 Node 全量使用本机 Node 22.14 而项目要求 >=22.18，TS 导入失败，另有并发截止时间断言失败；明确启用类型擦除、限制测试并发后完整通过，没有放宽截止时间或测试断言。原生 SDK 的 LF/CRLF 编辑行为完整保留。

## 新增受控能力

- 文件分析与外层 Agent 分析均可继承现有授权资源；同时识别 knowledgeAgentId 和 authorizationAgentId，每次调用仍核对用户、Agent、资源、版本和执行预算。
- `market_resolve_security` 复用 official security_master，保留交易所、证券类型、名称和全称；支持 SH/SZ/BJ 前缀和后缀，不把相同数字代码当同一证券。
- `market_get_day_snapshot` 固定数据集、source_id 和交易日，复用同一 JDBC bulkhead 的单次 daily-hot 查询；最多 10,000 行/8 MiB，多取一行检查超限，超限拒绝而非截断。现有 200 行普通查询上限不变。
- 日文件只在已授权计算且原生 PARQUET 合约下可用；现有快照发布、文件 Hash、schema、行数、绑定版本、Python 挂载和归档路径继续生效。模型收到描述符，不接收全日原始行情数组。
- 单源/单日完整读取不代表整个交易所采集完整，也不证明交易日历、价格复权或货币单位。缺失声明必须继续在报告说明。
- `POST /api/v1/platform/models/search-probe` 只接受模型、Agent、可选已授权引擎 ID，使用一个固定公开查询/count=1，经过已有 Provider 配额/审计路径；不接受任意探测 URL、不更改配置。模型仍使用原生 web_search/web_fetch 自主选择查询，不新增搜索 Agent。
- Provider 能力描述明确 WSA 不支持语言过滤及 news 类型，错误反馈说明修复参数或授权替代路径。搜索摘要仍是不可信证据。
- Python exit=0 与报告归档分别返回；输出缺失反馈给出准确 results 路径及修复动作。反馈覆盖 manifest、schema、所有 Parquet 分片及补充快照引用。前端四项状态分别展示，并从当前任务已归档回执恢复。

## 联机验收

原失败问题、证券代码 301218、Agent、模型、原 Parquet 和补丁均已取得。三个原输入文件 Hash 正确，原进程成功但报告缺失；另一次有 300 秒模型回调错误，不混为编辑错误。

线上查询当前可得 16 个目标证券交易日，原冻结输入只包含 8 个不同交易日的重复查询结果。Flash/Plus 联网开关关闭，Omni Flash 开启；本次保留这些设置。联机结果、候选镜像 Hash、回滚与 Flash/Plus 对照在后续验收记录中补齐，不能以本地测试代替。

`package-runtime-data-remediation.mjs` / `prepare-runtime-data-remediation.py` 仅准备已审查类和三个 Node 适配文件，逐项核对未改 JAR 条目；部署复用 `deploy-integrated-runtime.py` 的空闲检查、数据库/配置备份、签名回调与网络限制，不另做业务执行引擎。
