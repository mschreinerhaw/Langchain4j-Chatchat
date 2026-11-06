# Runtime OS 数据探索与报告交付整改验证

更新：2026-11-07（Asia/Shanghai）。基线 main `2db9b59df`，分支 `fix/runtime-data-exploration-delivery-20261106`。先形成[审计及根因矩阵](../audits/runtime-data-exploration-delivery-20261106.md)，各修复单独提交。未修改共享模型配置、Agent 授权、预算或 SDK 版本。

已取得真实联机证据，但模型稳定交付与性能验收仍未全部通过。数据可达、工具可用、进程成功、报告归档及业务分析充分分别判定。

## 回归结果

| 范围 | 结果 |
|---|---|
| Node 全量 | 255 通过、0 失败/跳过；最终 Worker 容器原生修复路径 38 通过 |
| Java API 定向 | 240 项，239 通过、1 既有环境跳过 |
| Java 其他定向 | Coverage 30 项（29 通过、1 既有跳过），Market 21、MCP 6、News 9 全通过 |
| Java 定向合计 | 306 项、304 通过、2 既有跳过，不是整个项目全量 |
| 前端全量 | 144 项、11 文件通过，生产构建通过 |
| 实际日文件 Hash 回归 | Evidence/Knowledge/Supply 68 项，67 通过、1 既有跳过；大源 Hash 成功，大证据记录仍受 2 MiB 限制 |
| 历史真实 28,866 字符补丁 | 原第三段分为 15,000/14,026；版本、旧 Hash、原子失败、恢复与精确结果均通过 |

API 类计数：Analysis 58、Knowledge 36、Internet 8、Supply 13、Report 29、Feedback 10、Callback 21、AssetController 8、Workspace 23、Bridge 13、Storage 2、EvidenceStore 19。同类重跑不累加。跳过项为既有 Python/sqlglot 或引用环境条件，不计通过。

Node：coding-agent-node 中 `node --experimental-strip-types --test --test-concurrency=2 test/*.test.js`。前端：web-app 中 `npm.cmd run test:exploration` 和 `npm.cmd run build`。Java：`mvn.cmd -o -pl chatchat-api -am test` 加指定测试类，以及 `-Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dfrontend.skip=true -Dfrontend.install.skip=true -Dfile.encoding=UTF-8`。实际日文件回归增加 `-Dcoding.daySnapshotFixture=.../market-day-private.json`。

[真实补丁脱敏证明](evidence/runtime-data-remediation-20261106/real-patch-regression.json)：SDK 输出 SHA 与预期均为 `0b0901cbfc188fa3bcbc463cc9c3ee53ec724ccec97190cf9d6bf08e70eb1242`。命令 `node scripts/verify-runtime-failure-repair.mjs PRIVATE_DIR OUTPUT.json`。保留每段 16,000 字符限制，不等同于模型业务报告验收。

原始失败及重跑日志保留于本机忽略目录 target/runtime-data-remediation 和服务器 release。早期测试编译、Node TS 导入、并发截止断言失败均保留，未放宽原断言。Worker 首次 spec reporter 被验证器误判，保留日志后以 TAP 重跑通过。

## 真实问题和数据回退

原问题：“分析华是科技最新的股票价格行情走势”，Agent financial-news-agent，原任务 `analysis-coding-2b06d38d8a4b73634e8eed98cafd0ebd9c67d6eb514d7016687f5bbb1584043b`。原 Parquet 26 行、63,662 字节，SHA `935f004477cf762f0c7436bf82d92e4b5635ae87a4d2c54703a3b79bd14764f6`；manifest/schema/part 存在且 Hash 正确。Python 4,698ms、exit=0，仅写诊断而未交付 rows.jsonl。真实修复补丁第三段 28,866 字符被拒绝。另一次 300 秒模型回调错误独立记录。

已确认并修复的根因：外层 authorizationAgentId 未被资源装配识别；首批空数据在核对其他授权资源前提前终止；文件分析入口缺少 Agent 授权身份；报告父 Agent 切换子 Coding taskId 触发错误 403；拒绝复制源材料后过早终止原生修复；无效报告导致不可变归档反复读取；前端丢失停止与归档证据；存储容量保护返回笼统 500。

证券查询支持名称、别名、证券类型与 SH/SZ/BJ 市场映射。精确未命中后 Cline 可继续授权探索。日快照固定 dataset/source/day，最多 10,000 行/8 MiB，超限拒绝；普通查询上限仍为 200。每次调用实时核对原 Agent/用户授权交集，未增加权限。

真实日工具返回 2,943 行、约 3.4 MiB，但 sourceHash 误用证据数据库单条 2 MiB 校验，交付前报 Invalid Coding callback binding。修复分离规范化 Hash 和存储记录校验；原规范化字节、存储与导出上限均不变。

R7 Cline 原生调用 market_get_day_snapshot，取得 source 38、2026-11-02 的 2,943 行文件，未向模型返回 items。Parquet 1,494,236 字节，SHA `32e2b1184c69f460d67a99bbb1f34f4fed3d994116552cb45426f2c8a0ba2bc4`。Python 已实际挂载、读取、计算并执行原生编辑修复。最终任务 `analysis-coding-a319149dff965f001f604ca0fa56af98deb6af12ec3e1391c8b2a57caf043d2d` **COMPLETED**，resultAvailable=true；[完整原生工具及执行证明](evidence/runtime-data-remediation-20261106/r7-autonomous-day-proof.json)。

独立复验只读挂载这三个文件，禁网、512 MiB、1 CPU、cap-drop ALL，使用现有 Python 镜像，校验所有 Hash、行数和数值。SZSE/EQUITY 有效样本 2,943，代码唯一；301218 收盘 81.44、涨幅 19.99%，竞赛排名 12、并列 3 个，占第 12–14 位。金额为 10K-CNY，成交量来自 volume10_k_units，不是万手。这是单源单日已收集快照，不证明交易所采集完整，不是 11 月 7 日实时价。

[文件与报告独立校验](evidence/runtime-data-remediation-20261106/r7-day-file-verification.json)通过：最终 Python exit=0、executorStopped=true，报告单行 17,361 字节，SHA `92c84ef52405d25423b012dd939e4703fca70f5451d79929367cbcc47592668d`，API results 与不可变归档完全一致，最终原生 artifact_read 引用校验通过。本次是文本计算报告合约，不要求图表；四图交付另由 R5 验证。Cline 在原预算内修复了语法错误、exit=0 缺报告、补丁锚点不符、缺少行内引用和分箱重复计数；所有中间失败保留。

**人工质量备注**：该报告的行数、目标数值、排名和分箱合计与独立复算一致，但将 99.58 百分位表述成“中位水平偏上”不准确；且正文写“81.44元”，同时又声明价格货币未知，存在口径冲突。保留原件，不由验证器改写模型报告，也不认证业务表述全部正确。该轮证明授权全日文件→原生 Python→报告归档链路成立，仍有模型文字质量风险。

## Search API 与报告交付

[探测证明](evidence/runtime-data-remediation-20261106/r6-search-probes.json)：Flash/Plus 原联网关闭，返回 MODEL_INTERNET_DISABLED，未访问供应商；已有 Omni Flash 开启，腾讯 WSA 固定单次查询 OK、1 条结果。未修改共享开关、凭据、Agent 默认模型。

[原生自主搜索证明](evidence/runtime-data-remediation-20261106/r6-autonomous-search-proof.json)：Cline 实际调用 web_search/web_fetch，读取深交所上市公告及证券页面，保留来源、时间和证据。证券页面是 JS 壳；摘要出现的报价没有原文验证，未认证为权威行情。工具自主调用成立，但任务重复探索后 BUDGET_EXCEEDED，未交付最终报告，不能计作分析成功。

Python exit=0 仍须验证 rows.jsonl、图表、引用、归档。错误反馈包含精确路径、字段和原生编辑动作；每次新 Sandbox 不继承旧输出。保留全部分片及补充快照引用，不创建编辑器、搜索 Agent 或上下文压缩器。

[R5 归档独立证明](evidence/runtime-data-remediation-20261106/r5-report-archive-proof.json)校验报告 SHA/大小/单行、相同 operationId 的停止证据、章节图表引用和每张图的 Hash/数组。Flash 1 四张图通过；Plus 1 的部分报告仍保留，错误零记录结论不通过业务验收。

[R7 浏览器只读证明](evidence/runtime-data-remediation-20261106/r7-browser-proof.json)：显示“模型响应已停止、快照已就绪、Python 进程已结束、报告已归档”，0 execute 请求、0 浏览器错误。经既有网关 18100 验证；内部 18180 直连失败保留为环境记录。历史 Agent 报告从只读轮询恢复，没有重新执行。

## 相同输入、权限、预算的两轮 Flash / Plus 对照

冻结原 Parquet、manifest、schema、输入上下文、已有生产者字段声明共五文件，逐一核对四任务实际挂载 Hash。[可比性证明](evidence/runtime-data-remediation-20261106/r5-benchmark-comparability-proof.json)通过。逻辑内容 SHA `e14cba79d7df7486732491ea09e244619ded03ab7605d4931829586e70d965c4`。外层五行是五份材料，不是五条行情；原行情 16 条记录对应重复的 8 个日期。

相同上限：12 次模型、786,432 Tokens、24 次工具、12 次 Python、CPU 300,000ms、内存 512 MiB、输出 4 MiB、USD 10。每轮两模型并行，两轮顺序执行，没有并发 Agent 探索任务。

| 任务 | 状态/质量 | 总耗时 ms | 模型调用 | 已知输入/输出 Token | 产物 |
|---|---|---:|---:|---:|---|
| Flash 1 | COMPLETED，主要计算与限制核对通过 | 942577 | 8 | 246669 / 50877，下界 | 完整报告、4 图 |
| Plus 1 | FAILED，错误判断零记录 | 514319 | 12 | 351617 / 26795 | 保留 PARTIAL 报告、0 图 |
| Flash 2 | FAILED | 512219 | 12 | 404114 / 30261 | 未交付 |
| Plus 2 | FAILED | 358192 | 12 | 330195 / 18348 | 未交付 |

[完整阶段及模型回执统计](evidence/runtime-data-remediation-20261106/r5-real-scenario-results.json)。Flash 1 有一次使用量未知，不能报告精确总 Token；其余三个 usage 完整。失败任务较短不是性能提升，不能据此证明模型优劣。Flash 1 正确去重 8 日、68→81.44、累计 +19.76%，说明日期缺口、单位和快照限制；报告个别“8条”措辞与 16 原始行的口径仍有歧义，保留人工备注。

| 阶段 ms | Flash 1 | Plus 1 | Flash 2 | Plus 2 |
|---|---:|---:|---:|---:|
| 文件读取 | 3305 | 331 | 4699 | 1748 |
| Python | 5401 | 6612 | 6598 | 2219 |
| 代码提议模型回调（含思考、IO） | 396952 | 422950 | 379416 | 277896 |

纯规划、纯代码生成、纯报告生成及部分交付校验无法从已有回执拆出，记录 UNKNOWN/null，不能填 0；阶段可能重叠，不能相加。R6 缓存修复已经单测与真实容器验证，但尚无 R6/R7 上相同四任务的完整性能重测，不能宣称模型已提速。

## 发布和未通过项

最终 API 源码 `b494e13e4eb9a34654c76a29088b803a710f3e1d`，JAR SHA `84e268c00715b133017ea00cbc8b1684f4a2ba69a4fbfbe548ebe8d22087920f`；[部署证明](evidence/runtime-data-remediation-20261106/r7-deployment.json)。Worker 源码 `2b88bcb6b2713ea261a59bcf7c871bd18bb66447`，镜像 `sha256:118b02724d1236fb6a2268f9a5b07818e60669928381dca6c2145eef0b22224d`，SDK 0.0.93 不变。

[共享模型配置前后比对](evidence/runtime-data-remediation-20261106/r7-model-config-unchanged-proof.json)覆盖全部 12 个模型，完整 API 配置的规范化 Hash 一致；没有通过修改联网开关或预算制造通过结果。

复用原发布的空闲检查、数据库/配置备份、回滚和网络边界；无数据库迁移。MCP 目录 39 项健康；无关容器、对话内容和消息索引计数不变。R7 只替换 API 与其网络命名空间代理，备份在 `/var/tmp/runtime-data-remediation-20261107-r7/backup`。

私有失败样本及日志在服务器 `/opt/chatchat-releases/native-analysis-failure-20261106`、`runtime-data-remediation-20261106`、`runtime-data-remediation-20261107-r2/r4/r5` 和 `/var/tmp/runtime-data-remediation-20261107-r6/r7`。凭据、Token、完整模型消息和原始行情未入库。

首候选漏带 Docker CLI 已按原 Hash 恢复；不完整输入对照、旧版本测试批、构建磁盘满和 Silo 容量拒绝均保留。只核验迁移自有可重建 JAR/重复构建材料至根分区，保留 Hash/旧路径，没有删除业务数据、回滚镜像或降低 Silo 阈值。容量错误现返回安全 507 / ARTIFACT_STORAGE_FULL。

验收 1/2/4/5/6/7/8/10 已有对应源码、测试及真实证据。验收 3 原生日文件、计算、报告归档链路通过，业务文字质量有上述备注。验收 9 已提交可比对照，但质量稳定性不通过、纯模型分阶段耗时存在观测缺口。整体整改尚不能宣布全部验收通过。
