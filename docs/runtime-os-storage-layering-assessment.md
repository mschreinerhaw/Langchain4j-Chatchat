# Runtime OS 存储分层现状与实施边界

审查日期：2026-10-21。基线提交：`87c354fbd0478afcda498052ff42560e2494eadd`；基线 Tag：`baseline/runtime-os-silo-workspace-20261021`；实施分支：`feature/runtime-os-silo-workspace`。开始时工作区干净，不覆盖主分支或既有 Tag。

最终原则：**Silo 保存事实文件，PostgreSQL 管理业务关系，OpenSearch 建立可检索知识，Cline 负责智能分析，Runtime OS 负责控制与协调。Python Runtime 负责隔离执行和计算结果校验。**

## 实际调用链

| 路径 | 已核实实现 | 分层现状与处理决定 |
| --- | --- | --- |
| 用户文件 | `UserAssetController → UserAssetService → AssetBlobStore → SiloAssetBlobStore` | 已使用 PostgreSQL workspace/file 表；对象精确 version、SHA-256、大小和配额受服务端约束。继续扩展，不另造文件系统。 |
| 身份边界 | `ObjectAccessService` 从认证请求取得 tenant/user；API permission filter 校验已有 capability；任务读取额外校验 task/run | 请求不能指定任意对象 key 或凭据。先保持私有，团队共享须接入现有资源授权与撤权体系。 |
| 分析提交 | `AssetAnalysisController → CodingAnalysisService.executePrimaryAnalysis` | 选中文件按哈希封装为快照数据；完整字节仅经受控文件交给 Sandbox，不进入模型 Prompt。Cline 生成代码，Java 不替代业务计算。 |
| 执行/恢复 | `CodingInputFilePublisher` 发布 manifest/parts；`CodingInputSnapshotService.requireAuthorized` 验权并物化；`CodingCodeArtifactStore` 提交代码；Python bridge 执行；`CodingEvidenceCollector` 校验结果 | 已归档输入、代码、日志、产物和 context，缺失共享缓存可从 Silo 恢复。沿用原预算、fencing、隔离与停止证明协议。 |
| 旧文档上传 | `SearchController → SearchService.upload`：`saveOriginalFile` 写本地文件，Tika `DocumentTextExtractor` 提取文本，再写 `RocksDbSearchStore`，同步检索索引及 Knowledge IR | 此路径尚非统一事实层；不能把新增用户文件入口视作旧库已经迁移。历史库迁移需可恢复清单、哈希对账、原下载兼容及回退，不能直接删除 RocksDB/原目录。 |
| 全文索引 | `OpenSearchDocumentIndexService.indexLatest` 使用既有 `TextChunker`、Tokenizer、KeywordExtractor，按 fileId 写 chunk，bulk 检查逐项失败；search 接收 allowedDocumentIds | 复用该适配器，新增文件通过稳定 asset ID 投影。OpenSearch 只给候选；PG 再核对归属、AVAILABLE、当前摘要哈希和索引状态，之后才允许返回片段。 |
| 向量检索 | `OpenSearchEmbeddingClient` 和 `OpenSearchSemanticIndexSchema` 存在；实际 `OpenSearchDocumentIndexService.search` 只进入 lexical/compactLexicalSearch；全项目无该 embedding 客户端的业务调用 | 不能将类存在当作向量能力已上线。本轮不新增第二套 embedding/chunk/RAG；后续在此适配器内恢复语义查询并验证模型维度、版本隔离、授权与降级。 |
| Knowledge IR | `KnowledgeDocumentIngestionService → KnowledgeIRIndexPort → JpaKnowledgeIRIndex`；search 从 JPA 候选集中做内存词匹配 | 这是仍在调用的知识路径，非可直接删除的废弃 RAG。最终须改为既有 OpenSearch 知识投影，先保持兼容并做调用方及旧数据迁移，禁止未经验证直接移除。 |
| 上下文编排 | `DefaultKnowledgeRuntimeService` 合成 skill、执行检索、编译限长上下文并复核 skill scope | 属于 Runtime 协调职责，应保留。上下文文档保存在 Silo，检索只是衍生投影，模型结论不得自动升级为已验证事实。 |
| 对话上传 | `ChatAssistantView.js.uploadChatDocument` 调用现有文档上传/分片流程，具有分类、状态、索引流程 | 与个人文件库尚未统一；迁移需保留原文档分类与授权，不能简单替换前端 URL。 |
| 数据库迁移 | 用户资产使用 `database/migration/postgresql/V20261021_01__runtime_user_assets.sql` 的显式增量迁移；测试对应 classpath SQL；旧业务有 JPA repository | 沿用显式迁移，新表 owner 跟随现有 runtime 表的应用角色；不依赖启动时自动建表。 |

## 本轮索引集成设计（实施前记录）

新增 PostgreSQL 衍生索引作业状态，和文件 AVAILABLE / DELETED 变化在同一事务登记；状态包含待处理、成功、失败、跳过与删除。调度器有界处理，失败记录安全错误类别并退避重试，重启后继续；按文件作业行加锁，避免多实例重复发布和删除乱序。读对象使用原 owner 的受控接口及精确版本校验。

只索引用户原始文档及分析完成的结构化历史摘要，不默认索引全部代码、执行日志、原始交互事件。历史摘要保留原 task、run、文件 ID、哈希，标为需要核对来源的模型结论。复用已有 Tika 解析器和 OpenSearch 分块接口，Parquet 原文件可分析但不是 Tika 支持的全文格式，状态应明确跳过，不能伪报解析成功。

复用当前 OpenSearch 文档索引；不调用旧 SearchService.createOrUpdate，因为它会额外写 RocksDB 并触发 JPA Knowledge IR。索引重建从 PG 当前可访问文件清单及 Silo 精确版本恢复，不从 OpenSearch 反向创建事实文件或权限。旧库整体 rebuild 会清空共享索引，因此资产提供显式重建/重试入口，运维重建必须同时触发资产重建；后续统一重建编排应消除该双步骤。

查询必须使用非空的 PG 允许文件 ID 集；空集直接返回，不调用旧接口的“空集表示不限制”分支。返回前再次校验 PG 当前归属/状态及内容哈希，避免删除、撤权或陈旧索引暴露片段。所有 API 沿用当前认证和 capability 边界，不向 Cline/Python 发 S3 凭据。

## 测试基线和未关闭事项

- 本次修改前定向 Java 35 项通过：`UserAssetServiceTest`、`AssetAnalysisControllerTest`、`UserAssetControllerHttpTest`、`RuntimePythonAssetArchiverTest`、`CodingInputFilePublisherTest`、`CodingEvidenceCollectorTest`，Maven reactor BUILD SUCCESS；真实 SILO 集成另行运行，不能算入本次基线。
- Node：`coding-agent-node/npm test`，44 项通过。
- Python：`python -m unittest discover -s chatchat-mcp-server/src/test/python -v`，5 项中 Parquet 子场景因缺少 `pyarrow` 报错，其余通过。这是本地依赖缺失，尚不声称 Parquet 本机验证完成。
- 后续将相同 Python 源码与测试挂载至服务器已有 `chatchat-python-runtime:3.11-v1.1`，以 `--network none --read-only --tmpfs /tmp` 运行，5 项全部通过，包含 Parquet 的整数/Decimal/JSON 精度和篡改拒绝；未修改宿主机 Python，也未绕过本机安装源的证书验证。
- 既有完整 Java 回归在前次审查存在断言失败及 Windows RocksDB native 崩溃；本轮未重跑全量，不能将定向测试等同于全仓通过。上一轮 36 项 Java/18 项前端和真实服务器验证见 `runtime-user-assets-silo-20261021.md`，不冒充本轮证据。
- 既有独立文件分析没有 AgentRuntime parent；原任务监控接口据 parent 查任务，因此取消/恢复入口尚需补齐。上一轮余额不足的 provider 调用仍保留未核销 reservation，没有可证明的停止回执，不得篡改账本使其“通过”。
- 对象写成功而 PG 提交失败时可能残留未登记 version；STAGED 可重试，但孤儿版本补偿尚不完整。对象缺失的可修复生命周期、审计、团队授权、对话入口迁移及历史库迁移必须分别验收。

这些事项是任务清单的后续验收项；本报告不表示全部七阶段已完成或生产发布已通过。

## 新增投影的使用与运维

配置 `chatchat.assets.enabled=true` 及 `chatchat.assets.knowledge.enabled=true` 后启用；`chatchat.assets.knowledge.poll-ms` 默认 5000。网络/解析工作使用单独有界工作线程，避免阻塞 Runtime 共享调度器的超时与恢复任务。每批最多处理 10 个作业，失败退避 30 秒起、最多一小时。数据库迁移按 `_01`、`_02` 顺序执行，不改旧表的历史内容。

所有入口位于既有 `/api/v1/data-science/user-assets/knowledge`，继续校验已有 capability 和当前认证用户：

- `GET /`：本用户最近 100 个索引状态。
- `GET /{fileId}/status`、`POST /{fileId}/retry`：查询和重试指定文件。重试不能复活已删除文件。
- `GET /search?q=...&limit=10&historyOnly=false`：只搜索本人当前 AVAILABLE 且索引 hash 一致的文件。结果包含稳定 fileId、源 hash、task/run、摘要和证据类型；历史模型结论明确标识需验证。空授权清单不发起 OpenSearch 查询。
- `POST /rebuild`：从本人 PG 清单重新安排投影，再读取 Silo 精确版本，适用于共享索引重建后的恢复。

目前查询最大支持 1024 个已索引文件，超过会明确报错，不会默默漏掉更早文件。界面可搜索原文/历史，查看索引状态、重试失败或重建，并显式选入本次分析。原始 Parquet 保留正常分析能力，其全文索引状态为 SKIPPED；解析失败则标识 PARSE_FAILED，源文件仍可下载。

服务器验收发现现有 pinyin/token 展开会让一个较长标识超过查询预算。修复位于原 OpenSearch 适配器：仅对明确的预估子句超限使用标准分析器的原词查询回退，继续执行同一查询预算与同一权限过滤；不扩大预算、不截断授权清单、不把失败当作空结果。

Silo 版本仍固定为用户指定的 `RELEASE.2026-10-18T00-00-00Z` 和已验证 digest，不升级 SDK 或切换 latest。2026-10-21 核查 [官方发布列表](https://github.com/pgsty/silo/releases)，该版本标记为 Latest；[项目网站](https://silo.pgsty.com/) 说明提供社区维护、容器和 CVE 修复。这是当日来源核查，不是长期维护保证；后续安全升级应经过版本差异、备份恢复和同一套实际 S3 验证。

## 本轮完成与部署证据

- 定向 Java 共 **50 项通过，0 失败/错误/跳过**：API 43 项（含真实固定版 Silo）和 OpenSearch 适配器 7 项。Node 44 项、前端 18 项通过；前端构建完成。Python 本机依赖问题保留在基线，服务器隔离镜像的相同 5 项测试全部通过。[测试记录](runtime-storage-layering-20261021-evidence/local-validation.json)。
- 已部署到授权服务器的现有隔离验证环境，API 镜像 `runtime-os-api:user-assets-006e684cbb95`，Jar SHA-256 `006e684cbb95caebea9085fd97c389e9daaa765d71a5f264fa9a1ffc8ba8a26d`。[部署记录及回退备份](runtime-storage-layering-20261021-evidence/deployment.json)。主分支未改动。
- 实际 PostgreSQL/Silo/OpenSearch 验收 12 项全部通过：原文及历史可检索、源 hash 对齐、空授权不查库、同租户跨用户隔离、匿名拒绝、越权状态与重试拒绝、重建精确原文、解析失败及重试、删除后的检索阻断。临时用户、角色与文件已清理。[服务器验收](runtime-storage-layering-20261021-evidence/knowledge-acceptance.json)。
- 实际浏览器验证通过：上传、原字节下载、模型选择、索引可检索、全文搜索、显式选入分析和删除；没有浏览器异常或资产接口错误，未触发新模型调用。[浏览器验收](runtime-storage-layering-20261021-evidence/browser-acceptance.json)。
- 验证结束时 PostgreSQL 索引状态为 2 条 INDEXED、10 条 DELETED，没有失败或待处理积压。物理 Cline 活动 session 和临时 Python 容器均为 0；上一轮 provider 失败的 1 条核销待办依旧如实保留。[任务控制快照](runtime-storage-layering-20261021-evidence/task-control-summary.json)。

本轮落地的是统一资产的全文/历史投影闭环。旧文档库存储迁移、对话上传归一、团队共享、实际向量查询恢复、Knowledge IR 检索迁移、独立分析的取消入口、对象孤儿补偿与完整审计仍未完成；不能用上述验收替代这些项目的验收。

## 对话粘贴与长文本上传（2026-10-21 后续改造）

已移除对话输入工具栏的“联网搜索”“上传文件”“上传图片”三个入口。输入框支持直接粘贴文件和拖入文件，显示保存状态、失败原因、重试和移除附件；普通文本粘贴保留浏览器的正常编辑行为，中文输入法确认不会触发误发送。

长文本阈值为 **8,000 个 Unicode 字符**。超过阈值时，发送操作把完整原文按 UTF-8 保存为 `.md` 文件，再通过稳定文件 ID 进入原 Cline/Python 分析链；保留换行、首尾空白和全部内容，不截断原文件。上传失败会保留输入与待重试文件，未保存成功不会提交分析。每次最多 8 个附件、原始附件合计 16 MiB，单张图片最多 10 MiB。

个人资产接口增加 `/api/v1/interactions/user-assets` 别名，沿用现有 `workspace:chat:interact` 权限及同一个服务端 owner 校验。原数据车间地址保持兼容。聊天用户无需获得整个数据车间的权限即可保存自己的附件，不能借此读取他人文件或调用数据车间其他接口。未增加全局角色授权或新 S3 客户端。

粘贴图片先写 Silo，再由已鉴权的精确版本字节进入现有 `ImageUnderstandingService` 多模态/OCR 路径。新增字节入口不另写本地原图，也不产生旧图片库的无归属记录；派生解析归档到个人空间。解析来源、置信度、原图 ID 与 hash 随派生 Markdown 交给 Cline，并标明模型输出需要核对原图。支持 PNG/JPEG/WebP/GIF 签名检查，读取图像尺寸时不解码整张图片，限制像素规模。

文件分析的真实报告显示在当前对话，并保存文件引用和历史。修复了会话保存回调回放 RUNNING 时覆盖终态的问题：文件任务结束后输入框恢复可用，成功仅清理当前活动草稿的附件，后台结束不清理新会话草稿。已完成的原生文件任务不作为父 AgentRuntime 任务轮询。

验证记录：

- Java 定向 **61 项通过，0 失败/错误/跳过**；前端相关 **62 项通过**，前端构建完成。[本地验证](chat-pasted-assets-20261021-evidence/local-validation.json)。
- 服务器聊天权限验收 7 项通过：仅聊天权限可上传和原字节下载；没有获得数据车间权限；越权下载、文件分析和图片分析在模型调用前拒绝；匿名拒绝。临时用户、角色和文件均已清理。[权限验证](chat-pasted-assets-20261021-evidence/permission-acceptance.json)。
- 真实浏览器完成 CSV/图片粘贴、图片原字节下载、9,354 字输入自动生成 Markdown、Markdown 全字节比对、真实模型及 Python 分析、终态输入框恢复和历史恢复，结果为 **42.75 CNY**。目标请求只携带简短说明及文件引用，不包含完整长文本。[浏览器验证](chat-pasted-assets-20261021-evidence/browser-acceptance.json)。
- 最终任务 `analysis-coding-53aa54011ddf4c4ecc0928a99fb6090fa1b15bfc4b92ed6e455f4eb18c8f8d8d` 的 14 个归档对象逐一通过大小/hash 校验；实际 Python 源码读取 CSV 并使用 Decimal，代码中没有硬编码 `42.75`。未进入分析的两张测试图片已清理。[归档验证](chat-pasted-assets-20261021-evidence/native-archive-acceptance.json)。
- 最终镜像 `runtime-os-api:user-assets-9fec2ad8ab03`，Jar SHA-256 `9fec2ad8ab031c22a0c9222d16e19e6f4c53727fc51ea66a39c92728900def2a`。最终前端组包与 Java 测试包的 1,131 个非前端归档项字节完全一致。[组包核对](chat-pasted-assets-20261021-evidence/package.json)、[部署及回退备份](chat-pasted-assets-20261021-evidence/deployment.json)。

真实模型验收使用 CSV 与自动生成的 Markdown；图片语义解析通过服务/HTTP 授权测试，实际浏览器验证图片粘贴和字节保存，未逐一进行所有图片格式的付费模型验收。结束时活动 Node session 和临时 Python 容器均为 0，本次两个实际文件分析任务均完成；早先 provider 失败的核销待办仍保留，不更改其账本状态。[任务快照](chat-pasted-assets-20261021-evidence/task-control-summary.json)。前文清单中的对话文档原文件入口已接入本次统一资产路径；旧知识库迁移、团队共享等其他待办不因此被视为完成。

### ??????? Agent API ???2026-10-21?

?????????????????????????????? Agent API ?????? Agent ????? multipart ????????? `attachmentIds` ?????????????????????????????????Cline?Python ??????????????????/??/?????????????????????? [Agent ??????](agent-analysis-attachments.md)???????????????????????????????????????????
