# chatchat-runtime-news 设计文档

> 文档状态：与当前实现同步  
> 模块：`chatchat-runtime-news`  
> 默认端口：`8091`

## 1. 设计目标

`chatchat-runtime-news` 是面向模型分析与总结的资讯采集、规范化和检索服务，不是通用网页爬虫。

## 金融数据资产闭环

交易所行情、估值、融资融券、分红送配、ETF 规模和市场统计同时进入结构化数据资产层：

1. 采集结果中的 `dataset` 或 `datasetCode` 选择稳定的数据集；未知 API 数据集必须提供安全的英文 `datasetCode`。
2. `data_schema_registry` 记录源字段、物理字段、推断类型、业务说明和 Schema 版本。新增字段经过标识符清洗后以 `ALTER TABLE ADD COLUMN` 演进，完整原始对象同时保存在 `payload_json`。
3. 每张业务表包含 `collected_date`、`observation_date`、来源和幂等 `record_key`。MySQL 默认按 `TO_DAYS(collected_date)` 做 32 路 Hash 分区；H2 开发环境建立 `(collected_date, observation_date)` 复合索引。
4. `market_asset_catalog` 由 MCP Server 内嵌的 Market 能力保存业务描述、关键词、字段说明、更新频率以及数据库/表位置。OpenSearch 的 `financial-data-asset` 只保存这些轻量目录文档，不复制高频观测数据。
5. Agent 只调用公开的 `web_search`：使用 `query` 同时发现资讯和金融数据资产，使用资产结果中的 `dataset` 再次调用同一工具，按注册字段、日期和最多 200 行的上限读取。工具不接受 SQL、表名或排序表达式。

MCP Server 调用 News Runtime；News Runtime 发现市场观测时回传 MCP 的内部 Market 入口。内部请求使用带时间戳、一次性 nonce 和五分钟有效窗的 HMAC-SHA256 签名。金融数据库不向模型或 MCP 客户端直接开放。

声明式 `API` 来源可配置 `datasetCode`、`datasetName`、`businessDescription` 将返回对象交给自动 Schema 管线；敏感请求头放入 `encryptedHeaders`，值使用 `ENC(base64Iv:base64CipherText)`，运行时解密后才发送。

核心目标：

- 采集要闻、行情快照、公告、快讯和监管披露等有分析价值的内容。
- 每条资讯必须保留标题、来源名称、原始 URL 和发布时间，保证证据可回溯。
- 自动读取 PDF、Word、Excel、CSV 附件，切片后与正文统一索引。
- 通过按日索引、7 天保留策略、受限队列和 Bulk 写入控制数据规模与系统压力。
- 可选启用向量化，通过 BM25 与 kNN 混合检索提高召回精度。
- 独立部署，通过内部账户与 MCP Server 通信；配置入口仍归属 MCP 管理页面。

## 2. 架构边界

```text
MCP 管理页面
    │ 资讯源配置、规则、立即采集
    ▼
MCP Server / NewsRuntimeClient
    │ Basic 内部账户 + JSON/HTTP
    ▼
chatchat-runtime-news:8091
    ├─ Source/Schedule：来源配置与 Cron 调度
    ├─ Collectors：RSS、API、WebMagic、交易所首页、声明式结构化快讯
    ├─ Normalize：正文清洗、时间解析、去重与证据字段
    ├─ Attachment：受限下载、Tika 解析、重叠切片
    ├─ Bulk Queue：有界队列、批量写入、重试与退避
    └─ OpenSearch：日索引、BM25、可选 kNN、7 天清理
```

职责约束：

- WebMagic 只存在于本模块的采集实现中，不向 Agent 暴露线程数、深度、请求头等底层参数。
- MCP Server 负责工具定义、Schema、启停、发布和管理页面；News Runtime 只负责执行。
- Agent 侧只发布查询工具，不发布来源增删改、选择器修改或底层爬虫控制能力。
- OpenSearch 写入不得在采集线程中逐条同步执行，必须经过有界 Bulk 队列。

## 3. 采集模型

支持的来源类型：

| 类型 | 用途 |
| --- | --- |
| `RSS` | RSS/Atom 资讯流 |
| `API` | JSON API 资讯源 |
| `EXCHANGE_HOME` | 上交所、深交所首页要闻与行情快照 |
| `SZSE_HOME` / `HKEX_HOME` / `CSINDEX_HOME` | 深交所、香港交易所和中证指数首页专用结构化采集 |
| `CHINABOND_HOME` | 中国债券信息网首页专用采集：完整国债收益率曲线与结算统计进入MCP事实库，研究文章及附件进入资讯索引 |
| `EXCHANGE_MARKET_DATA` | 沪深交易所融资融券、公司分红送配，以及ETF基金规模数据；大结果集支持按日期和页码断点续采 |
| `NEWS_HOME` | 巨潮资讯等首页关键内容 |
| `CLS_TELEGRAPH` | 财联社电报快讯 |
| `EASTMONEY_724` | 东方财富全球财经资讯 7×24 小时直播快讯 |
| `STRUCTURED_FLASH` | 通过模板描述请求、分页、JSON 字段映射和法律声明的结构化快讯；前两项作为旧配置兼容类型保留 |
| `STCN_DISCLOSURES` | 证券时报信息披露 12 个板块当日第一页快照及公告附件全文 |
| `CNINFO_ANNOUNCEMENTS` | 巨潮资讯结构化公告与 PDF 原文采集 |
| `WEB_LIST` | 列表页发现详情页后采集 |
| `WEB_SINGLE_PAGE` | 固定资讯页面采集 |

网页来源通过 CSS 选择器提取标题、正文、作者和发布时间，通过 Java 正则表达式过滤允许进入的详情 URL。MCP 后台提供内置 URL 正则模板，用户选择模板后仍可编辑为站点专用规则。保存时会编译校验正则。

当配置选择器命中 0 条时，Runtime 会检查未渲染模板表达式、Vue/Next.js、Webpack、空应用根节点和 API 驱动空壳等特征。检测为动态页面时，本次执行明确失败并建议切换官方 JSON API 或专用采集器，不再把 HTTP 200 的空壳页面记录为成功采集。财联社电报、东方财富 7×24 快讯、证券时报快讯、金十数据市场快讯和金十数据重要事件使用 `STRUCTURED_FLASH` 模板，巨潮公告继续使用专用结构化接口采集器。

### 3.1 声明式结构化快讯模板

`STRUCTURED_FLASH` 将站点差异限制在来源的 `configuration` 中：

- `request`：接口地址、固定/占位查询参数、请求头和签名适配器名称。
- `response`：成功状态、错误消息、资讯数组、下一页游标或复合分页状态的 JSON 点路径；分页状态可来自响应根节点或最后一条资讯。
- `mapping`：ID、游标、标题、正文、摘要、作者、发布时间、详情 URL、动态标签、跳过条件和元数据映射。
- `compliance`：固定分类、标签、法律风险标记和法律声明。
- 通用控制项：`itemLimit`、`maxPagesPerRun`、`initialBackfillHours`、`initialCursor`/`initialState`、`snapshotMode`、`sleepMillis` 和 `timeoutMillis`。

请求参数支持 `${cursor}`、`${pageSize}`、`${timestamp}` 以及 `${state.<名称>}` 占位符；详情 URL 支持 `${id}` 和资讯字段占位符。`request.omitBlankQueryParameters` 可让首次请求省略空分页参数。模板不能执行脚本。需要签名时只能引用 Spring 中已注册的白名单 `StructuredFlashRequestSigner`，当前支持 `NONE`、`CLS_WEB` 和 `STCN_WEB`。

采集器将最新 `id:cursor` 持久化为断点。默认将数字游标小于或等于已保存游标的条目视为边界，因此即使边界条目从上游列表消失，也不会无休止翻页。任一条目被拒绝或达到最大页数但未触达边界时，本次游标不会推进。

对于一次返回完整编辑快照且顺序不按时间排列的接口，可启用 `snapshotMode`。该模式每次完整扫描单页并依靠内容存储层去重，不使用游标或时间边界提前终止，因此不会漏掉排在旧条目之后的新内容。

证券时报信息披露使用 `STCN_DISCLOSURES`：固定读取沪市主板、深市主板、科创板、创业板、北交所、新三板、沪股通、深股通、基金、债券、港股和监管的第一页，每页最多 20 条，并仅保留任务执行当天（`Asia/Shanghai`）发布的公告。同一公告命中多个板块时合并板块标签，公告 URL 交给附件管线继续提取原文。

调度器周期扫描已启用来源，根据每个来源保存的六段 Spring Cron 决定是否执行。单次采集数量受 `max-items-per-run` 限制，采集线程池和等待队列都有固定上限。

## 4. 数据处理链路

```text
发现候选项
  → 域名与 URL 规则校验
  → 提取标题/正文/作者/时间/附件链接
  → 规范化与最小正文长度校验
  → contentHash 去重
  → 生成 NewsDocument
  → 提交有界 Bulk 队列
  → OpenSearch 日索引
```

`NewsDocument` 是进入模型检索层的标准对象。网页导航、广告和原始 HTML 不属于该模型。正文和附件分片都必须带有可验证的来源信息。

## 5. 附件处理

自动支持：`PDF`、`DOC`、`DOCX`、`XLS`、`XLSX`、`CSV`。

识别依据包括 URL 扩展名、HTTP `Content-Type` 和 `Content-Disposition` 文件名，因此显式附件选择器发现的无扩展名下载地址也可处理。

附件链路独立于网页采集线程：

1. 校验初始 URL 和重定向后 URL 的允许域名。
2. 在独立有界线程池中下载，限制文件大小和单篇附件数量。
3. 使用 Apache Tika 自动检测并提取文本。
4. 按重叠窗口切片，限制最大字符数和最大分片数。
5. 分片进入与资讯正文相同的 Bulk 队列。

附件分片使用 `documentKind=attachment_chunk`，并保留 `parentDocumentId`、`parentTitle`、`parentUrl`、`attachmentUrl`、`attachmentFileName`、`chunkIndex` 和 `chunkCount`。

带文本层的 PDF 可直接解析；纯扫描 PDF 当前不执行 OCR。

## 6. 队列与背压

系统包含三个受限执行边界：

| 边界 | 默认容量 | 行为 |
| --- | ---: | --- |
| 采集任务队列 | 20 | 防止调度任务无限堆积 |
| 附件处理队列 | 500 | 满载时拒绝新增附件任务并记录告警 |
| OpenSearch Bulk 队列 | 5000 | 批量写入；满载时等待有限时间后返回失败 |

Bulk 默认每批 300 条或每 2 秒刷新一次，失败最多重试 2 次并退避。任何生产调整都应保持队列有界，不能改成无限队列。

## 7. 索引与检索

物理索引命名：

```text
{index-name}-yyyy.MM.dd
```

默认基础名称为 `runtime-news`，时区为 `Asia/Shanghai`。每天创建新索引，保留最近 7 天；清理任务默认每天 `00:15` 执行。

未启用向量化时使用关键词检索。启用 Embedding 后：

- 正文和附件分片写入 `knn_vector` 字段。
- 查询同时执行 BM25 和 kNN。
- 使用 RRF 融合关键词排名与向量排名。
- Embedding 失败不能破坏来源 URL、标题和正文的普通索引能力。

## 8. 安全设计

- `/internal/**` 使用内部账户 Basic Authentication。
- 账户密文可通过公共加密属性机制解密；HTTP 头中仍是 Basic 凭据，生产必须使用 TLS 或可信内网。
- News Runtime 不应直接暴露到公网，也不提供浏览器管理页面。
- 下载附件只允许资讯源域名及显式配置的额外域名，重定向后再次校验。
- MCP 页面中内置来源默认关闭，管理员确认选择器和站点条款后再启用。

## 9. 部署与目录

生产发布包目录：

```text
bin/     启停与状态脚本
config/  外置配置和初始化 SQL
data/    H2 数据或运行数据
doc/     设计与接口契约
lib/     chatchat-runtime-news.jar
logs/    标准输出和错误日志
run/     PID 文件
```

一键本地脚本 `run-chat-api-mcp.bat` 会同时构建本模块，并按 News Runtime → MCP Server → Chat API 的顺序启动。
News Runtime 默认启用 `h2` Profile；通过 `--spring.profiles.active=mysql` 切换到 MySQL。数据库专属参数分别位于
`application-h2.yml` 和 `application-mysql.yml`，公共采集、OpenSearch 与内部账户参数保留在 `application.yml`。

## 10. 可替换性

采集引擎通过 `NewsCollector` 按来源类型选择实现。WebMagic 是 `WEB_LIST`/`WEB_SINGLE_PAGE` 的当前执行引擎，不是系统契约。未来替换为自研引擎或 StormCrawler 时，应保持以下边界稳定：

- `NewsSource` 输入模型；
- `RawNewsItem` 采集输出；
- `NewsDocument` 标准索引模型；
- 有界附件和 Bulk 队列；
- 本文配套的内部 HTTP 与工具契约。
