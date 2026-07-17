# chatchat-runtime-news 设计文档

> 文档状态：与当前实现同步  
> 模块：`chatchat-runtime-news`  
> 默认端口：`8091`

## 1. 设计目标

`chatchat-runtime-news` 是面向模型分析与总结的资讯采集、规范化和检索服务，不是通用网页爬虫。

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
    ├─ Collectors：RSS、API、WebMagic、交易所首页、财联社快讯
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
| `NEWS_HOME` | 巨潮资讯等首页关键内容 |
| `CLS_TELEGRAPH` | 财联社电报快讯 |
| `WEB_LIST` | 列表页发现详情页后采集 |
| `WEB_SINGLE_PAGE` | 固定资讯页面采集 |

网页来源通过 CSS 选择器提取标题、正文、作者和发布时间，通过 Java 正则表达式过滤允许进入的详情 URL。MCP 后台提供内置 URL 正则模板，用户选择模板后仍可编辑为站点专用规则。保存时会编译校验正则。

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
