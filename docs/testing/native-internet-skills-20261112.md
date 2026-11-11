# Native Cline 联网检索与 Skills 集成验收（2026-11-12）

本次实现将已有 Runtime Internet 服务和授权 Skills 接入 Cline Standalone 4.1.26，保留官方 Agent Loop。真实运行完成了 Parquet 读取、联网搜索、公开 CSV 下载、原生 Skills 及引用资源读取、Python 计算、图文报告与 Silo 交付。首次报告经独立审查发现无依据的单位与数据来源推断，未作为业务验收通过；反馈由 Cline 在新任务中修订，重新核验通过。所有失败均保留。

这是候选环境功能验收，不是 `1.0.0` 正式发布，不替代此前要求的全场景五轮稳定性或原生 CLI 对照测试。主业务 18100 的 API 与 Coding Node 默认配置未替换。

## 代码与调用链

基线：`29f51d18b77ed012ffffd21670b2c13a841c4e94`；分支：`feature/runtime-cline-cli-native-20261107`。本报告和功能代码的最终 Git SHA 以包含本文件的提交为准，镜像由该基线加本次工作树源码远程构建，不能将基线 SHA 误认为完整镜像源码版本。

```
用户 / 已发布 Agent → Java 授权与能力快照 → 签名任务 → Native Node
  → 官方 Cline Skills / MCP 工具自主选择
  → 任务 Broker → Java /internal/runtime/native-coding/mcp
  → 既有 CodingInternetAccess → MCP → News InternetSearchService
  → PublicWebHttp / RobotsPolicy → 公网来源
  → 原始字节校验、Silo CONTEXT 归档 → /workspace/downloads/<sha>.<ext>
  → 原生 Shell / Python → 交付清单 → Java 验证 / Silo 登记
```

* `NativeTaskCapabilities` 只冻结现有模型、Agent、Skills 和 Internet 授权，不生成计划、不执行搜索。通用任务 API 可指定已有已发布 `agentId`，仍调用现有实时访问控制与发布解析。
* `NativeRuntimeMcpController` 是内部无状态 MCP 适配入口，校验 HMAC、任务绑定、租户/用户/run、过期时间、终态、取消与期限；只开放已授予的三个工具。调用前后复核实时 Internet 授权。
* `web_search`、`web_fetch` 复用既有引擎配置和服务；新增同一服务内的 `web_download`，不建设另一套搜索 Agent。旧 SDK 工具列表仍为原有两项，新 Native 能力列表单独扩展，避免破坏旧路径协商。
* 下载使用既有公网地址校验、DNS/IP 与重定向验证、robots、配额和取消机制。禁止内网、loopback、云元数据，禁止 HTTPS 降级，最多三次重定向；没有增加搜索或模型请求次数上限。
* 下载类型允许 PDF、CSV、JSON/LDJSON、TXT、HTML/XHTML；当前沿用 1 MiB 单响应资源上限。大文件未在本次扩容，超限明确失败。原始字节经 SHA-256 校验存 Silo；Broker 再校验并落本地，仅把路径和元数据返回模型，避免 base64 进入上下文。
* 本地下载目录在 Linux 使用目录 FD 固定 inode 和 `O_NOFOLLOW`，阻止 Cline 可写目录的符号链接/重命名绕过。只由固定回调地址访问 Java，不接受模型提供任意内部代理 URL。
* 任务恢复时继续使用原生会话、工作区和已冻结资源。修复 Java 关闭过程中 HTTP 中断被包装为一般异常后误触发 Native 停止的问题；关闭只释放控制层，重启后通过原有任务恢复协调。

## Skills 审查与兼容

`coding-agent-node/src/native-skills.js` 属于原有 SDK 路径；当前 Standalone worker 由 `cline-agent-server` 启动，不运行该 SDK Worker。官方 Core/Loop 没有改动。官方固定源码为 `a459f1d04dea030833b558be645011ab739c463b`（Cline 4.1.26），镜像原有完整性校验继续执行。

新的 `native-capability-files.mjs` 复用原有 `skill-distribution.js` 的镜像目录，提供 15 个镜像 Skills，并叠加本任务已授权 Agent Skills。每个包以来源、ID 哈希和名称命名到 `.agents/skills/`，保留原始前置元数据及资源相对路径，仅命名空间化原生发现名称。记录原始/落地 SHA、版本、路径与字节数。只加载名称和描述，正文仍由 Cline 原生 `skills` 工具按需读取；引用资源经原生文件工具读取。脚本保留可执行权限，资源冻结而工作区代码可写。

`CodingSkillReader` 为 Native Silo 任务复用现有 Skill 授权和资源版本钉住机制；原 SDK 的文本过滤行为保持。Native 接收脚本/二进制资源，设每文件 2 MiB、快照 6 MiB、Node 每包 4 MiB 的资源预算，未授权包不分发。更新的三个自有 Skills 区分 Native Shell 与条件式旧 Sandbox 用法，未覆盖第三方原包。

真实 Agent 验收包为 `scripts/fixtures/native-runtime-skills/dataset-quality-review/`，只含方法与引用，不包含最终分析脚本和答案。通过现有导入、审核、发布与 Agent 绑定 API 授权。原包缺少许可和涉及敏感执行的两次发布被正确阻止，未放宽审核。真实 Native 记录证明 `skills` 加载该包并通过 `read_files` 读取 `references/quality.md`。镜像内置 Artifact Management、Evidence Based Answering 也有实际加载正文回执，见 `all-native-skill-calls.json`。

## 实际运行与独立验算

原始记录：服务器 `/opt/runtime-os/cnw/acceptance/`、`/opt/runtime-os/cnw/evidence/`；原始模型/工具回执和成果另存 Silo。Git 中保存精选、不含凭据的证据，见 [证据目录](evidence/native-internet-skills-20261112)。成功的工具回执与 Java 完成登记并不自动等于业务通过。

| 运行 | 真实结果 | 判定与原因 |
| --- | --- | --- |
| joint-r1 | omni 复杂任务，1238.58 秒，超时停止 | 失败；反复遇到原生编辑工具长度错误并过度展开。下载成功不能代替最终业务完成 |
| joint-r2 | omni 简化任务，约 887 秒，交付已登记 | 独立数值验证失败；均值截断；旧监控统一 100 条文件列表被日志占满，遮住早期输入 |
| joint-r3 | plus 简化任务，约 241 秒，交付已登记 | 独立精度验证失败；不丢弃失败记录，后续任务显式声明精度契约 |
| joint-r4 | plus，约 817 秒 | 未通过联合验收；捕获能力时 News 控制面未就绪，安全拒绝联网授权。新增可用性说明及测试前就绪检查，未绕过权限 |
| repair-r1 | plus，115.74 秒 | 通过；真实 TypeError、修改源码、重执行，四个动态 Python 模块、JSON/Markdown/PNG 与 Silo 哈希 |
| joint-final-r1 | plus，289.06 秒 | 数值、真实下载、Skills、Python、Silo 哈希通过；独立语义审核发现报告推断不成立，报告业务验收拒绝 |
| report-review-r1 | plus，403.15 秒 | 通过；Cline 根据审查意见和显式授权 Silo 输入自主修订，重新检索/抓取资料，区分合成数据、公开资料和未知语义，数值及成果哈希复核通过 |
| recovery-final-r4 | plus，最终 Java r3 与最终 Node；25 秒真实前台等待期间重启 Java | 通过；原 Python 继续、同 Worker/Session/容器、无重复启动、Skills 哈希不变、真实越权/过期拒绝、Silo 交付与 47.5 合计验算全部通过 |
| recovery-final-r1 | API 替换后立即启动检查 | 测试启动失败；API 尚未就绪，连接被重置，无业务成功声明 |
| recovery-final-r3 | 同 Worker、Session、容器恢复并交付，数值正确 | 严格单次启动检查失败；90 秒超过原生 Shell 命令等待阈值，Cline 自主改用后台重跑，标记被覆盖。不是 Java 重复创建 Worker，保留失败；后续改为超时以内的 25 秒恢复检查 |
| recovery-final-r2 | 已启动真实 Python | 探针失败；受限容器 root 无权读取另一个 UID 的 `/proc/<pid>/exe`，改为同执行 UID 检查。原任务与记录保留 |

联合任务的本地输入明确标记为 SYNTHETIC：3 行、amount 合计 362、均值 `362 / 3`；不能作为真实客户业务数据或真实年份趋势。真实公网 CSV 为 144 行、Passengers 合计 40363。公开镜像的字段单位缺少权威元数据，修订报告保留该限制，不把外部约定或数值重合当作来源证明。

公开 CSV：`https://raw.githubusercontent.com/jbrownlee/Datasets/refs/heads/master/airline-passengers.csv`

SHA-256：`8cb51be753a718d9be5d76d7e238cc224792754676adc21faa59e954b0201621`。

* 首次联合完成：task `native-3b532c6b264d8ae65c71d5686b35b8992ffc55e4d7a6bc5b539a241c3f12c203`；run `coding-c5bf12ddfa14ea9c16bed9fab49e4dc6196e8676f8022189d7c3082accca51de`。
* 修订后业务通过：task `native-5b41cd28c08312b42988366b2f5c57d19a1b0bb82761ce35891bfa4578bb8b85`；run `coding-b14625526a706914f29e76ef9bbb6f8babe50e1941f521513a4f7f6c087b930d`。
* TypeError 自主修复：task `native-8bdba1c17e96827ae59214a4107390b3482def0ecbf35e7abc8db03f277cc7fe`。

分析代码均由真实 Cline 模型生成，验收脚本仅提交输入、读取产物并独立计算，不写最终分析脚本。不强制固定源码文件名；本次部分任务选择 `analysis.py` 是模型自主命名。

恢复通过 task：`native-75ac9e9a3c968e7acc8e4e6b52be100bb72e92539f422ffe180675d49d19989d`。检查脚本明确只重启候选 `monitor-api`，未重启 Native Node、主 API、Silo 或共享基础服务。该通过结论针对 25 秒前台命令；90 秒版本的原生工具超时失败保留。

## 回归、安全和监控

* Java Maven 相关回归：191 项，190 通过、0 失败/错误、1 个既有跳过，最终 `BUILD SUCCESS`。包含 Native、Skill、Internet、旧 SDK 分派、分析、知识、市场、News、URL 安全与监控测试。
* 最终 Node 镜像内 Linux 回归：99 项全部通过。两个依赖既往专项归档的测试文件 `p331-evidence`、`stage2-evidence` 不在此次测试集合；初始未过滤运行的缺档失败记录仍留服务器，不能称所有历史验收已通过。
* 真实运行中显式操作员探针验证 loopback、云元数据、私网 URL 均返回错误；未知内部工具拒绝。这些是操作员安全检查，不冒充模型自主调用。符号链接、错误哈希、非 JSON-RPC 成功响应等边界有 Node 回归。
* Java 监控只读浏览器验收通过：匿名/无效 Token 拒绝、正常登录、跨 run 拒绝、默认折叠、真实时间线、真实异常修复、动态源码与 PNG 预览，无页面错误。浏览器脚本修正异步等待方式，不放松现有 CSP。
* 最终联合任务 25 个真实模型请求，input 458840、output 12710；Java 与实际 Provider 回执一一对应、request ID 唯一。能力观察事件不重复计为模型调用。历史 SSE 与 Last-Event-ID 续传通过。
* 修复监控产物查询被大量日志挤掉输入的问题：按 CODE/ARTIFACT/CONTEXT/DATASET 分别读取，保留访问校验，大日志依旧按需读取。

候选监控：`http://192.168.195.221:18353/monitor/`，使用现有内部账号登录。主 18100 与原监控 18351 保持现有入口。

## 构建、部署和回滚

所有服务由上传源码在服务器 Docker 构建，不依赖本地手工安装的运行服务。候选使用独立 Compose、工作区和数据库，沿用原租户与账号，没有新建租户。只在候选数据库启用 plus 的联网策略（revision 7）；主数据库 plus 策略仍关闭（revision 6），共享模型 endpoint/key 不变。不得把候选授权当作生产已开启。

| 服务 | 镜像标签 | 本地镜像 ID |
| --- | --- | --- |
| Native Node/Executor | runtime-os-coding-node:integration-internet-20261112-r1 | sha256:a10367a21aea93d8e36415b107cfc1221c30340c7e0e9490ac5d03a812b6af89 |
| 候选 Java 最终版 | runtime-os-api:integration-native-internet-20261112-r3 | sha256:14296db0214a6959533cdb5f86e4f891ba7d8401533459634d71383f53c4bbf8 |
| 共享 MCP | runtime-os-mcp:integration-native-internet-20261111-r1 | sha256:f66af9a994619f76f5e9bdb601aee57d35d727a17dd1fe45902983ad753f8be9 |
| 共享 News | runtime-os-news:integration-native-internet-20261111-r3 | sha256:6c482b7d2ddfa040815c2486999994725e3e2c50d464e9fd03e40e8ce9f6684f |

这些是本地内容寻址 image ID，未推镜像仓库，`RepoDigests` 为空，不虚构 registry digest。Node 标记 `1.0.0-rc.internet.20261112.1`，未标为正式 1.0.0/latest。最终 Java r3 在联网业务完成后的 API r1 基础上仅增加关闭中断处理与越权异常 403 归一化，并重新构建、回归及恢复验收。

部署位置：

* Node/Executor/候选监控：`/opt/runtime-os/cnw/compose.json`，project `runtime-coding-internet`；Node 18370、监控 18353。
* 候选 Java：`/opt/runtime-os/cn95/compose.json`，project `runtime-coding-candidate`，service `monitor-api`，回环端口 18352。
* 私有 MCP 配置需要 `authMode: native-runtime`、固定 Java 内部回调地址和三个允许工具；签名密钥与原有模型/Silo 凭据仅存在服务器私有配置中。通用入口按任务能力授权；不是给所有任务全局开放 Internet。
* 共享 MCP/News：`/opt/runtime-os-docker-stage/compose.yaml`，原 maintenance 项目。原镜像/配置保留于 `/opt/runtime-os-docker-stage/rollback/native-internet-20261111T145635Z/`。

回滚只恢复指定服务的原镜像标签并 `up -d --no-deps`；保留当前其他 Compose 变更，避免用整份旧配置覆盖未来配置。原 MCP image ID `sha256:abf832a95d436e215e3f2f699fe7c81fd68204612d6f9dd1e2d718a4ecdba56e`；原 News `sha256:a1f1e1356df06e18a515afe88a3d92d2a0d35d1b3e27a55b85163bb1e0ce27b1`。候选独立停止不影响主 Node。Silo、PostgreSQL、数据卷和回滚镜像不删除，不做全局 Docker 清理。

源码核对：远程构建目录 3,709 个 Java 源文件及 85 个 Native Node/Skills 文件与本地工作树的规范化 LF 哈希一致，无缺失或差异。新建容器导入 Python 3.11.18、pandas 2.3.3、pyarrow 26.0.0、duckdb 2.0.0、matplotlib 3.10.0、openpyxl 3.1.5 成功，见环境及源文件审计证据。

## 明确限制

1. 恢复压力测试中的 90 秒前台命令超过原生工具等待阈值，模型可能改为后台重跑；该用例不计严格单次启动通过。本次不修改官方工具超时语义。
2. omni 复杂任务超时仍是已知失败，不宣称不同模型和复杂度都稳定通过；需要后续模型/官方编辑工具兼容评估，不用额外 Java 规划器掩盖问题。
3. 当前验收以 CSV 公网下载真实跑通；PDF/JSON/TXT/HTML 由同一类型受控下载链路支持，但未逐种完成独立真实分析案例。
4. 对缺少单位和来源元数据的材料，报告必须声明缺口。人工独立审查发现的问题由 Cline 修订，不将数值正确等同于所有语义正确。
5. 单文件下载 1 MiB；大文件扩容或流式分块不在本次实现中。Skills 仍需经过现有许可/发布审核，拒绝不代表绕过后可用。
6. 候选仍保留供查看；未进行正式发布清理、正式仓库推镜像或生产切流。本次代码提交不代表此前完整正式发布门槛全部满足。


## 重现入口

本地回归使用 Java 21 / Maven，运行相关模块及依赖：

```sh
mvn -pl chatchat-api,chatchat-mcp-server,chatchat-runtime-news -am test -Dtest='*Native*Test,CodingSkillReaderTest,CodingInternetAccessTest,ClineCodingAdapterDispatchTest,CodingAnalysisServiceTest,CodingKnowledgeServiceTest,InternetSearchServiceTest,PublicWebSecurityTest,InternetProviderStoreTest,LocalMarketWorkflowTest,CodingTaskMonitorControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

真实业务脚本 `scripts/verify-native-internet-skills.py --help` 提供候选入口、凭据文件、Parquet 路径、模型、Agent 和证据输出目录参数。`--basic` 采用精简业务任务，`--review-case-directory` 引用既有任务的显式 Silo 输入启动 Cline 修订，`--existing-case-directory` 仅重新独立核验既有产物。每次使用不同输出目录保留失败。

`scripts/verify-native-java-recovery.py --help` 提供独立恢复测试入口，需要明确指定候选 Compose、project 和 node-root，测试操作包含真实候选 API 重启，不能随意指向生产项目。浏览器验收沿用 `scripts/verify-native-runtime-monitor.mjs`。凭据通过服务器私有文件或运行时环境传入，不写入报告、测试夹具或镜像。
