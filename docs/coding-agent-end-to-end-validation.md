# Coding Agent 本轮验收记录

日期：2026-10-16。最终合并定向回归 BUILD SUCCESS；27 个 Java 测试类共 253 项，失败、错误、跳过均为 0。不是全仓库或生产验收。

## 环境与可信范围

Windows / PowerShell；Amazon Corretto OpenJDK 17.0.19；Maven 3.8.9 离线复用本地依赖；Node 25.0.0；实际 `@cline/agents@0.0.93`；Python 3.14 专用 venv 与 SQLGlot 30.23.0。H2/JDBC/事务真实运行。Node 跨进程测试启动实际 child Node 服务，Java 与 Node 经 HTTP 和签名交互；SQL 解析启动真实 Python 进程。

**模型 provider 为确定性测试替身。** SQL Studio HTTP/工程测试使用真实 Spring/鉴权/H2，但 workspace 生成器为明确标注的替身；独立跨进程测试再验证真实 SDK/Adapter/回调/证据组合。这些测试组合不是一次真实模型/浏览器/生产 Docker 的完整端到端验收。

`docker info --format '{{.ServerVersion}}'` 实际失败：`dockerDesktopLinuxEngine` named pipe 不存在。没有启动 Docker Desktop、连接生产、修改生产开关或重写历史 provider 未知状态。

## 两条链路

| 链路 | 本轮实际覆盖 | 判定 |
| --- | --- | --- |
| A：SQL Studio 自然语言→Cline→解析→候选→Diff→确认→保存 | 真实 Java A2/H2 → 独立 Node / Cline SDK → 受签名回调 → 实际 SQLGlot → 错误反馈/修复 → 两文件候选及六项证据；另以真实 SQL HTTP 工程测试验证确认前不写、归属、版本应用及重复应用拒绝；前端组件/传输与生产构建 | 已实现并经测试，限定上述可复现的本地测试条件；真实 provider/浏览器/容器联调未签收 |
| B：授权模板→Dataset→Cline Python→Sandbox→证据→Analysis | 本轮重跑合同、A2、输入、Collector、回调、可选 Harness 委托、预算/取消/恢复等定向测试；原 B/C 真实 Docker 测试源码存在，但本轮未执行 | 已有 v1 实现及本轮回归；真实全链路被当前 Docker 环境阻塞，不能判定本轮完成全部 D 阶段 |

## 结果日志

日志位于工作区根 `D:/IdeaProjects/LangChain4j-AIChat/target/`，不是过期文档所指仓库内旧 target。

| 项目 | 最终结果 | 日志 |
| --- | --- | --- |
| Java 合并定向回归 | 253 项通过：Common 99、Agents 19、Tools 2、MCP 2、API 131；0 失败/错误/跳过 | `coding-continuation-verified-regression.log` |
| Node SDK/Worker | 21 项通过，0 失败/跳过 | `coding-continuation-node-final.log` |
| SQL Studio 前端 | 61 项通过，10 文件，0 失败 | `coding-continuation-web-verified.log` |
| 前端生产构建 | 通过；保留现有大 chunk 提示 | `coding-continuation-web-build.log`；产物 `target/coding-continuation-web/` |
| 真实 Docker | 未执行；引擎不可连接 | 本记录环境诊断 |

Java 命令使用 `mvn -o -pl chatchat-api -am -Pbackend-only -Dfrontend.skip=true -Dcoding.nodeE2e=true -Dsurefire.failIfNoSpecifiedTests=false test`，同时以 `-Dcoding.sqlglotPython` 指向本地专用 venv 解释器，并通过 `-Dtest` 明确选择以下 27 类：CodingWorkspaceServiceTest、CodingWorkspaceNodeIntegrationTest、SqlGlotToolchainTest、CodingContractsTest、CodingCanonicalHashTest、CodingJsonSchemaTest、CodingBudgetMathTest、CodingTaskPersistenceTest、CodingAnalysisServiceTest、HarnessCodingIntegrationTest、ClineCodingAdapterRecoveryTest、ClineCodingAdapterDispatchTest、ClineModelCallbackControllerTest、ClineRuntimeModelBridgeTest、ClinePythonCallbackControllerTest、ClineCallbackAuthFilterTest、CodingEvidenceCollectorTest、CodingInputSnapshotServiceTest、CodingInvocationContextTest、SqlStudioIntegrationTest、SqlStudioMetadataServiceTest、SqlStudioMetadataGatewayTest、SqlStudioGenerationToolPolicyTest、SqlOutputBundleTest、LivedataSettingsConfigurationTest、PythonOperationWorkspaceTest、PythonManagedInternalAuthFilterTest。

Node 使用 `npm.cmd test`。前端使用 `npm.cmd run test:sql-studio -- --maxWorkers=1 --no-file-parallelism`，然后构建到工作区 `target/coding-continuation-web`，没有覆盖源码目录的旧静态构件，也没有声称已生成包含新前端的生产 API 安装包。`git diff --check` 通过。

早期批次不能当最终通过：前端首次在 Maven/Node 并发负载期间出现语法库冷导入 5 秒超时；保持原超时和断言、在单 worker/无文件并行且无 Maven 编译负载下重跑通过。Java 合并批次曾因 Windows 对 Node 重定向日志的临时文件锁导致 JUnit 清理错误；断言本身通过，已改用进程 pipe 并显式关闭/回收自建子进程后重新运行。首次 SQL Spring 回归还暴露 MCP/License 独立服务被宽扫描，以及 fallback component 自身条件匹配；修复了 API 扫描边界及 Livedata fallback 注册，新增自定义 provider 不被覆盖的验证。未放宽生产协议或测试安全条件。

## 分类与遗留

| 状态 | 内容 |
| --- | --- |
| 已实现并经测试 | 表中已通过的本地合同、控制、文件提案/解析/修复、证据、HTTP 归属/版本与前端范围 |
| 已实现但尚未真实联调 | 新版本真实 provider、正常浏览器入口、目标服务器及同一容器 Worker 的部署联调 |
| 部分实现 | 字段检查依赖明确交付的授权表快照；文件提案为完整内容；CPU/费用计量及大结果读取仍有既有限制 |
| 未实现 | 活跃 SDK checkpoint 继续执行、增量补数/输入版本集、完整 D3/G3、旧 Harness 全量历史协议测试迁移 |
| 被环境阻塞 | 真实 Docker 操作、Node 容器崩溃重启、真实 Python Sandbox 取消/子进程停止及上述链路 B 完整重验 |

未将 Mock 模型当真实大模型，未将 SDK 单测当 Docker 或数据库 SQL 执行。SQL 模型生成能力不授予数据库执行权，业务验证未评估时继续报告 INCONCLUSIVE/NOT_EVALUATED。
