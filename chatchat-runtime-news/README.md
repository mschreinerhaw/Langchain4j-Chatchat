# chatchat-runtime-news

详细文档：

- [设计文档](doc/design.md)
- [内部接口与工具契约](doc/contract.md)
- [金融数据采集源与落库映射](../chatchat-runtime-market/doc/design.md#必须运行的采集源)

独立部署的资讯采集与检索运行时。MCP 服务保留配置页面和工具注册，但不再加载本模块的 Bean、JPA Entity 或爬虫依赖。

行情、指数估值、融资融券、权益、ETF规模和市场汇总对应的八个预置来源默认启用，并通过 MCP 内部接口写入统一数据资产层。

## 启动

```bash
mvn -pl chatchat-runtime-news -am package
java -jar chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar
```

数据库 Profile 可直接切换：

```bash
# 默认 H2 文件数据库
java -jar chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar --spring.profiles.active=h2

# MySQL（先修改 application-mysql.yml 并执行初始化 SQL）
java -jar chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar --spring.profiles.active=mysql
```

生产发行包会在 `package` 阶段同时生成：

- `target/chatchat-runtime-news-1.0.0-SNAPSHOT-release.zip`（Windows/通用）
- `target/chatchat-runtime-news-1.0.0-SNAPSHOT-release.tar.gz`（Linux，保留脚本执行权限）

发行包包含 `bin/config/data/doc/lib/logs/run`、MySQL/H2 初始化脚本及外置生产配置。解压后填写
`config/application.yml` 和所选的 `config/application-h2.yml` 或 `config/application-mysql.yml`，再运行 `bin/start.sh` 或 `bin/start.bat`。生产 YAML 使用明确配置值，不使用环境变量占位符；
`config/env.properties` 只保留 JVM 启动参数。

默认端口为 `8091`，未指定 Profile 时使用 H2。生产环境至少配置：

- 在对应数据库 Profile 文件中填写数据库地址、用户名和密码。
- 在 `application.yml` 中填写 OpenSearch、内部账户和可选向量服务配置。
- MCP 与 News Runtime 两边使用相同的内部账户；生产环境应通过 TLS 传输内部凭证。
- MCP 侧 News Runtime 地址指向实际部署地址，例如 `https://news-runtime:8091`。

内部接口统一位于 `/internal/v1/news/**`，只接受内部 Basic 账户。News Runtime 不提供管理页面，也不对 Agent 直接开放底层采集参数。

数据库初始化脚本：`database/init/mysql/chatchat-runtime-news.sql` 或 `database/init/h2/chatchat-runtime-news.sql`。

从原 MCP/API 数据库迁移时，将 `news_source`、`news_source_rule`、`news_collect_record`、
`news_analysis_task` 四张表导入 News Runtime 的独立数据库，再启动两个服务。新版本的 MCP/API 初始化脚本不再包含这些表。

## 采集日志保留

`news_collect_record` 采集日志默认只保留 7 天。应用启动后会清理一次，之后默认每天 `00:30` 分批清理；可通过
`chatchat.runtime.news.collect-log` 下的 `enabled`、`retention-days`、`cleanup-batch-size`、
`max-batches-per-run` 和 `cleanup-cron` 调整。清理不会删除 OpenSearch 中的资讯正文或附件分片；由于该表也保存 URL
去重状态，日志过期后再次发现同一旧链接时允许重新采集。

## 公告附件与向量检索

News Runtime 会在公告详情页及公告列表中识别 `PDF`、`DOC`、`DOCX`、`XLS`、`XLSX`、`CSV` 附件。附件下载和解析使用独立受限队列，
解析后的文本按重叠窗口切片，再进入原有 OpenSearch Bulk 队列。每个分片保留 `parentDocumentId`、原公告地址、附件地址、
文件名和分片序号，方便模型引用原始证据。MCP 的资讯源配置页面可设置附件链接选择器和额外允许域名。

默认附件上限为单文件 25 MiB、每篇公告 20 个附件、每个附件 500 个分片；可通过
`CHATCHAT_NEWS_ATTACHMENT_*` 环境变量调整。附件下载会校验初始地址和重定向后的域名，不允许任意外部地址。
带文本层的 PDF、Word 和 Excel 可直接解析；没有文件扩展名的显式附件链接也会根据响应类型识别。纯扫描 PDF 需要另行部署 OCR 能力后再扩展。

向量化默认关闭。启用 OpenAI-compatible Embedding 服务：

```bash
CHATCHAT_NEWS_EMBEDDING_ENABLED=true
CHATCHAT_NEWS_EMBEDDING_ENDPOINT=https://embedding.example.com/v1/embeddings
CHATCHAT_NEWS_EMBEDDING_API_KEY=replace-me
CHATCHAT_NEWS_EMBEDDING_MODEL=text-embedding-v4
CHATCHAT_NEWS_EMBEDDING_DIMENSION=1024
```

开启后，每日索引会创建 `knn_vector` 字段，资讯正文与附件分片都写入向量。查询同时执行 BM25 与 kNN，使用 RRF
融合两个候选集；Embedding 服务或 kNN 不可用时自动退回 BM25。修改模型维度后必须使用新的索引名称或等待旧每日索引过期，
不能在原有 `knn_vector` 字段上直接改变维度。

## 新闻证据字段

每条入库资讯强制校验非空标题和绝对 HTTP(S) 来源地址，并同时在元数据中固化 `evidenceTitle`、`evidenceUrl`。
公开 MCP 只暴露 `web_search`。其中新闻结果返回统一的 `evidence` 对象，至少包含原新闻标题、原始 URL、
来源名称和发布时间；附件分片还包含附件文件名与附件 URL。工具响应顶层同时返回去重后的 `reference_urls`，模型生成总结时
应保留这些地址作为可核验引用。

本模块内部的 `web_search(query=...)` 只检索新闻索引。公开 MCP Server 的同名工具会进程内聚合 Market 能力的 `financial-data-asset` 目录；传入 `dataset` 时由 MCP 内部 Market 能力读取结构化观测数据。



结论：当前实际上没有从交易所网站持续爬取数据。

我检查了本地运行库 `chatchat-runtime-news/data/chatchat-news.mv.db`：

- 共配置了 24 个交易所相关来源。
- 全部为 `enabled = false`。
- 所有来源采集记录均为 0。
- `last_cursor`、最后采集时间均为空。
- 本机 8091 端口也未启动资讯运行时。

也就是说，下面这些是“已经实现、可启用”的采集能力，不是当前正在运行的任务。

### 已实现的金融数据

| 来源 | 可采集内容 |
|---|---|
| 上交所 | 上证指数、科创综指、上证50、科创50、上证180、沪深300等指数行情；市场数据总貌、主板和科创板统计 |
| 深交所 | 深证成指、创业板指、深证100、创业板50的最新价、涨跌、涨跌幅、开高低、成交量和成交额 |
| 中证指数 | 沪深300、上证指数、科创综指、上证50、科创50的收盘点位、涨跌幅、成交额，以及历史收盘和滚动市盈率序列 |
| 中国债券信息网 | 统计概览、完整国债收益率曲线、柜台关键期限最优报价、结算情况、担保品业务数据，以及研究分析文章和附件 |
| 上交所融资融券 | 最近30个交易日融资融券汇总；最新交易日个股融资融券明细，最多100条 |
| 深交所融资融券 | 最新交易日交易总量；前5页、约100只证券的个券明细 |
| 公司权益数据 | 上交所现金分红、送股转增；深交所分红、送股、配股、除净日、股权登记日、配股价等 |
| ETF规模 | 沪深交易所ETF代码、简称、类型、规模日期以及基金总份额/当前规模，单位为万份 |
| 沪深港市场汇总 | 香港主板/创业板、上海A/B股、深圳A/B股的上市公司数、上市证券数、总市值、流通市值、平均市盈率、成交股数和成交金额 |
| 上交所每日快照 | 市场总貌、每日债券分类成交情况，以及股票、指数、基金、债券行情报表；同日同证券覆盖写 |
| 深交所每日快照 | 市场总貌、股票/基金/债券每日概况，以及股票、基金、债券、回购、期权、指数行情；游标分页并同日覆盖写 |
| 香港交易所 | 官方新闻稿、监管公告、市场通讯，以及披露易当日最新100条上市公司公告 |
| 上交所公告 | 上市公司、上市退市、一般公告、盘中停牌、融资融券、基金、债券、期权和发行上市动态 |
| 深交所披露 | 基金及ETF公告、融资融券业务公告、竞价交易公开信息及营业部明细、IPO/再融资/重大资产重组审核披露 |
| 交易所资讯 | 上交所和深交所首页要闻、热点动态、近期上市信息、公告正文与PDF附件 |

因此准确表述是：系统已经覆盖行情、估值、融资融券、分红送配、ETF规模、市场统计和公告披露.

中证指数来源明确对应 `https://www.csindex.com.cn/zh-CN/downloads/index-information#/`。由于该页面依赖 JavaScript，`CSINDEX_HOME` 采集器请求页面使用的结构化接口，将五个主要指数及其历史收盘、滚动市盈率序列写入 MCP 管理的 `index_valuation_daily`，不写入新闻索引。

中国债券信息网来源对应 `https://www.chinabond.com.cn/`。它是官方披露来源，来源治理固定为 `legalRisk=false`；系统仍保留来源归属、内部研究用途和不构成投资建议等使用说明，但这些说明不等同于法律风险标签。`CHINABOND_HOME` 采集器使用首页实际调用的公开结构化接口，不从截图识别数字：统计概览写入 `bond_market_overview_monthly`，完整收益率曲线写入 `bond_yield_curve_daily`，柜台行情写入 `bond_counter_quote_daily`，结算统计写入 `bond_settlement_daily`，担保品信息写入 `bond_collateral_monthly`。研究分析文章及附件仍进入资讯索引，以便 `web_search` 将市场事实与研究材料一起召回。事实明细只存数据库，`financial-data-asset` 只保存业务描述、字段字典和存储定位。
