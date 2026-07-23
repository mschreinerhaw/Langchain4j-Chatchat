# Market 数据资产设计

> 模块：`chatchat-runtime-market`  
> 部署方式：作为普通 JAR 随 `chatchat-mcp-server` 一起发布  
> 数据原则：数据库保存结构化事实，OpenSearch 保存资产目录和存储指针

## 1. 架构边界

`chatchat-runtime-market` 是 MCP Server 的内部领域能力库，不是独立数据服务。

它没有：

- 独立 Spring Boot 主类或端口；
- 独立 MySQL/H2连接配置；
- 独立 OpenSearch连接配置；
- 独立鉴权配置、启动脚本或发行包。

运行时统一复用 MCP Server 的 `DataSource`、OpenSearch客户端、内部凭据、权限和工具发布配置。

`chatchat-runtime-news` 继续独立部署，负责交易所/API采集和调度。采集结果被识别为市场观测后，不进入新闻索引，而是通过签名内部请求发送给 MCP Server。

```text
外部交易所/API
       │
       ▼
chatchat-runtime-news
  采集、调度、解析
       │ HTTPS + HMAC
       ▼
POST /internal/v1/market/observations
       │
       ▼
chatchat-mcp-server
  ├── Market Ingestion
  ├── MCP统一DataSource
  ├── MCP统一OpenSearch
  └── 对外web_search
```

HMAC用于身份认证、请求完整性和防重放；载荷机密性由HTTPS/TLS提供。

## 2. 双层存储原则

市场数据采用双层存储，但两层保存的内容不同：

| 存储 | 保存内容 | 定位 |
| --- | --- | --- |
| MCP数据库 | 行情、估值、融资融券、分红送配、ETF规模、市场统计等结构化事实 | 权威数据源 |
| OpenSearch `financial-data-asset` | 数据集名称、业务描述、字段字典、数据库/表位置和读取工具 | Agent数据资产目录 |

禁止默认把每日行情明细、融资融券明细等高频事实完整复制进OpenSearch。OpenSearch负责回答“有什么数据、数据有什么业务含义、数据存在哪里”；事实查询由数据库完成。

## 3. 采集与路由

News Runtime 的 `McpMarketIngestionClient` 检查采集结果元数据。出现以下任一字段时，将结果视为市场观测：

- `dataset`
- `datasetCode`
- `quoteCode`
- `indexCode`

命中后执行以下流程：

1. 不调用新闻规范化和新闻OpenSearch写入。
2. 将来源、标题、正文、发布时间、分类、标签和完整元数据序列化为 `MarketObservation`。
3. 使用内部账号、时间戳、一次性nonce和HMAC-SHA256签名。
4. 请求 MCP Server 的 `POST /internal/v1/market/observations`。
5. MCP鉴权通过后调用 `FinancialDataIngestionService`。

如果MCP写入失败，本次市场观测不得退化为新闻文档，以免结构化数据污染新闻索引。

## 4. 数据集识别

内置数据集如下：

| 业务数据 | 数据集编码/物理表 |
| --- | --- |
| 证券和指数行情 | `market_quote_daily` |
| 证券估值 | `stock_valuation_daily` |
| 指数估值 | `index_valuation_daily` |
| 融资融券 | `margin_trade_daily` |
| 分红送配 | `stock_dividend_event` |
| ETF规模 | `etf_scale_daily` |
| 市场统计 | `market_statistics_daily` |
| 每日债券成交情况 | `bond_market_daily` |
| 中债国债收益率曲线 | `bond_yield_curve_daily` |
| 中债结算统计 | `bond_settlement_daily` |
| 中债统计概览 | `bond_market_overview_monthly` |
| 中债柜台行情 | `bond_counter_quote_daily` |
| 中债担保品信息 | `bond_collateral_monthly` |

### 必须运行的采集源

以下预置源是金融数据资产链路的一部分，默认启用；不能仅启用 `sse_home`，否则数据库中只会出现上交所行情和市场统计：

| 预置源 | 采集内容 | `datasetCode` | 业务表 |
| --- | --- | --- | --- |
| `sse_home` | 上交所主要指数行情、数据总貌、主板和科创板统计 | `market_quote_daily` / `market_statistics_daily` | `market_quote_daily` / `market_statistics_daily` |
| `szse_home` | 深证成指、创业板指、深证100、创业板50行情 | `market_quote_daily` | `market_quote_daily` |
| `csindex_home` | 中证指数收盘、涨跌幅、成交额、历史收盘和滚动市盈率 | `index_valuation_daily` | `index_valuation_daily` |
| `chinabond_home` | 统计概览、完整收益率曲线、柜台行情、结算情况、担保品信息 | `bond_market_overview_monthly` / `bond_yield_curve_daily` / `bond_counter_quote_daily` / `bond_settlement_daily` / `bond_collateral_monthly` | 同名业务表 |
| `sse_daily_snapshot` | 上交所市场总貌、每日债券分类成交、股票/指数/基金/债券行情报表 | `market_statistics_daily` / `bond_market_daily` / `market_quote_daily` | 同名业务表 |
| `szse_daily_snapshot` | 深交所市场总貌、股票/基金/债券每日概况及股票/基金/债券/回购/期权/指数行情 | `market_statistics_daily` / `bond_market_daily` / `market_quote_daily` | 同名业务表 |
| `sse_market_data` | 上交所融资融券汇总和个券明细、现金分红、送股转增 | `margin_trade_daily` / `stock_dividend_event` | `margin_trade_daily` / `stock_dividend_event` |
| `szse_market_data` | 深交所融资融券总量和个券明细、分红送股配股 | `margin_trade_daily` / `stock_dividend_event` | `margin_trade_daily` / `stock_dividend_event` |
| `sse_etf_scale` | 上交所ETF类型及总份额（万份） | `etf_scale_daily` | `etf_scale_daily` |
| `szse_etf_scale` | 深交所ETF当前规模（万份） | `etf_scale_daily` | `etf_scale_daily` |
| `three_market_overview` | 香港、上海、深圳分板块市场汇总 | `market_statistics_daily` | `market_statistics_daily` |

采集器必须直接写入稳定的英文 `datasetCode`，MCP 只把中文 `dataset` 当作展示和兼容信息，不以中文文本猜测作为主路由。沪深港汇总中的 `segments` 会拆成主板、创业板、A股、B股等独立记录，便于按板块查询。

#### 中证指数“指数信息下载”页面

`csindex_home` 对应中证指数官方页面：

```text
https://www.csindex.com.cn/zh-CN/downloads/index-information#/
```

该页面是 JavaScript 应用，采集器不把页面空壳 HTML 当作数据，而是请求页面使用的官方结构化接口：

```text
最新指数及历史收盘：/csindex-home/homePage/indexMainAll
滚动市盈率历史：/csindex-home/perf/indexCsiDsPe
```

默认采集沪深300（000300）、上证指数（000001）、科创综指（000680）、上证50（000016）和科创50（000688）。每个指数作为一条 `index_valuation_daily` 观测写入，字段包括交易日期、收盘点位、涨跌幅、成交额、历史收盘序列和滚动市盈率序列。历史序列保存在事实表的 `close_history` 与 `pe_ttm_history` 字段中，OpenSearch只保存该数据集的业务描述和表位置。

预置版本升级时，MCP 会把旧版且处于禁用状态的上述来源一次性迁移为启用。发布新版本后需要重启 News Runtime 和 MCP Server；MCP 启动完成后同步预置配置，之后按调度周期采集并自动建表。用户在新版配置同步完成后主动停用的来源不会被后续启动反复强制开启。

#### 中国债券信息网首页

`chinabond_home` 对应 `https://www.chinabond.com.cn/`，采用网站公开的结构化接口采集，而不是对页面截图做OCR：

该来源属于官方披露平台，预置配置和采集器默认均使用 `legalRisk=false`。来源归属、仅限内部研究和“不构成投资建议”等文字作为使用说明保留，不生成“法律风险”标签，也不把使用说明误判成风险状态。

来源配置、采集请求和管理 API 统一使用 UTF-8。预置版本升级时必须替换中债来源的名称、业务说明和配置，随后按稳定业务键覆盖采集五张事实表，避免历史错误编码继续残留在 `payload_json`。

```text
曲线名称和业务日期：https://www.chinabond.com.cn/ccdcdata/yhj_data_xml_CN.xml
实时结算统计：https://www.chinabond.com.cn/ccdcdata/getRealtimeShtjfromtbl_CN.json
完整收益率曲线：https://yield.chinabond.com.cn/cbweb-mn/yc/inityc
统计概览：https://www.chinabond.com.cn/ccdcdata/QueryZSGLForIndex_CN.json
统计概览月度流量：https://www.chinabond.com.cn/ccdcdata/QueryIndexPageZSGLDataByMonth_CN.json
柜台行情：https://www.chinabond.com.cn/ccdcdata/getIndexZybjInfo.json
担保品信息：https://www.chinabond.com.cn/ccdcdata/queryIndexPageCounterData2_CN.json
研究分析：首页“研究分析”栏目及其详情、附件
```

收益率接口一次返回完整期限点，News Runtime 在单个加密请求中提交 `curvePoints`，MCP 的 `FinancialDataIngestionService` 再展开为 `bond_yield_curve_daily` 的独立记录。稳定业务键是“曲线标识 + 曲线业务日期 + 期限年数”，字段包含曲线名称、期限年数、收益率和发布时间。

结算接口的一行数组固定解释为“类别编码、面值、资金额、笔数、本金额”，展开写入 `bond_settlement_daily`。类别包括现券交易、回购交易、其中质押式回购、买断式回购、远期交易和合计；稳定业务键是“统计日期 + 类别编码”。页面中的 `---` 按空值存储，不能误写成零。相同业务键再次采集执行覆盖写。

研究文章不进入上述结构化事实表，而是继续走新闻正文和附件管线。OpenSearch 的 `financial-data-asset` 仅索引五个事实数据集的业务说明与数据库定位；文章正文进入资讯日索引。外部仍只通过 `web_search` 统一发现并读取两类材料。

统计概览以“统计月份 + 指标编码”覆盖写，包含债券托管量、银行间投资者数量，以及发行、现券、回购、借贷、远期和兑付规模。柜台行情以“报价日期 + 债券代码”覆盖写，保存剩余期限、最优买卖收益率与报价机构。担保品信息以“统计月份 + 产品编码”覆盖写，包含业务总览和财政专户、外币回购、债券保证金、存款授信质押、跨境担保品五类产品。

### 每日快照与覆盖写

`sse_daily_snapshot` 和 `szse_daily_snapshot` 使用交易所页面背后的官方结构化接口，不解析页面展示文字。行情记录的稳定业务键由“交易所 + 品种 + 交易日期 + 证券代码”组成；市场总貌和债券汇总由“交易所 + 数据集 + 交易日期 + 分类”组成。同一交易日重复采集会执行 upsert，覆盖价格、成交量、成交额等日内变化；不同交易日使用不同业务键，因此保留历史快照。

上交所单次运行可完成全部分类。深交所证券行情数量较大，使用 `last_cursor` 按品种和页码分批采集，默认每次25页并在请求之间节流，后续调度从断点继续。每日交易日期变化时游标自动从第一页重新开始。数据库事实记录更新后只同步资产目录到 `financial-data-asset`；行情明细本身不复制进 OpenSearch。

外部API可以明确提供安全的英文 `datasetCode`。未知但合法的数据集编码会生成 `fd_<datasetCode>` 业务表，同时使用 `datasetName` 和 `businessDescription` 建立业务目录。

数据集编码必须经过标识符清洗，客户端不能直接指定任意SQL、数据库名、表名或排序表达式。

## 5. 数据库写入与Schema演进

`FinancialDataIngestionService` 负责数据集识别和批量对象展开；`FinancialDataStore` 负责物理存储。

单条观测的处理顺序：

```text
MarketObservation
  -> 识别FinancialDatasetDefinition
  -> 计算observation_date和collected_date
  -> 规范化API字段名
  -> 推断字段类型
  -> 检查/创建业务表
  -> 新字段ALTER TABLE ADD COLUMN
  -> 更新market_asset_catalog
  -> 写入或幂等更新事实记录
  -> 触发资产目录索引更新
```

每张业务表包含以下治理字段：

- `collected_date`：采集日期，也是默认分区键；
- `observation_date`：业务观测/交易日期；
- `collected_at`：实际写入时间；
- `source_id`、`source_code`、`source_url`：数据来源；
- `record_key`：幂等键；
- `payload_json`：完整原始对象，用于审计和Schema恢复。

API字段会被转换为安全的snake_case列名。类型按布尔、数值、日期、JSON和字符串推断。新增字段写入 `data_schema_registry`，记录：

- 数据集编码和物理表；
- 来源字段和物理字段；
- 字段类型；
- 业务说明；
- Schema版本。

MySQL业务表默认按 `TO_DAYS(collected_date)` 建立Hash分区，分区数默认32，并建立 `(collected_date, observation_date)` 和 `observation_date` 索引。H2开发环境不做物理分区，但保留等价日期索引。

### 5.1 热数据与周快照保留策略

每日全量快照不能无限累积。默认采用两层存储：

| 层级 | 物理位置 | 粒度 | 默认保留期 | 用途 |
| --- | --- | --- | --- | --- |
| 热明细 | `<table_name>` | 每日/每次采集覆盖后的完整观测 | 7天 | 最近行情、短周期变化和采集核验 |
| 周快照 | `<table_name>_weekly_snapshot` | 每个自然周最后一个已有交易日的完整截面 | 1825天（5年） | 中长期趋势、周度对比和历史分析 |

MCP Server每周日02:30（`Asia/Shanghai`）执行 `FinancialDataRetentionScheduler`：

```text
查找 observation_date < 当前日期-7天 的日明细
  -> 按自然周分组，选择该周最后一个已有观测日
  -> 幂等写入 <table_name>_weekly_snapshot
  -> 确认该数据集所有待归档周写入成功
  -> 删除超过7天的热明细
  -> 删除超过周快照保留期的归档行
  -> 重建/同步 financial-data-asset 目录投影
```

归档表保留原事实表全部字段，并增加：

- `snapshot_week`：该自然周的周日，用作周快照定位键；
- `archived_at`：归档执行时间。

归档写入按 `snapshot_week` 先删除后重写，因此重复调度不会导致膨胀。只有归档流程成功走完后才清理该数据集的热明细；单个数据集失败不会阻止其他数据集处理，失败数据集也不会删除旧明细。周快照表建立 `(snapshot_week, observation_date)` 索引。

配置位于 MCP Server 的 `chatchat.mcp.market.retention`，这是行为配置，不包含独立数据库或OpenSearch连接：

```yaml
chatchat:
  mcp:
    market:
      retention:
        enabled: true
        hot-days: 7
        weekly-archive-days: 1825
        cron: "0 30 2 * * SUN"
        zone-id: Asia/Shanghai
```

这里的“周快照”是同一MCP数据库内的分析归档，用于控制事实表体积，不等同于数据库灾备。生产环境仍应由数据库平台执行独立的全库备份、异地备份和恢复演练。

## 6. 数据库资产目录

`market_asset_catalog` 保存每个数据集的权威目录信息：

- `dataset_code`
- `asset_name`
- `business_description`
- `business_tags_json`
- `database_name`
- `table_name`
- `archive_table_name`
- `hot_retention_days`
- `archive_retention_days`
- `history_granularity`
- `update_frequency`
- `source_names_json`
- `last_observation_date`
- `last_collected_at`

该表和事实业务表都位于MCP Server当前配置的数据源中。相关初始化DDL属于：

- `database/init/h2/chatchat-mcp-server.sql`
- `database/init/mysql/chatchat-mcp-server.sql`

业务观测表由运行时按数据集和实际API字段动态创建，不要求在初始化脚本中枚举所有物理表。

## 7. OpenSearch资产目录

事实数据成功写入后，`FinancialAssetCatalogService` 调用 MCP 提供的 `MarketAssetCatalogIndex` SPI。MCP实现 `McpMarketAssetCatalogIndex`，复用中央 `OpenSearchMcpSearchService` 的连接和凭据。

默认索引名称：

```text
financial-data-asset
```

目录文档示例：

```json
{
  "dataset_code": "margin_trade_daily",
  "title": "融资融券每日数据",
  "description": "记录每日融资余额、融资买入额和融券余额变化",
  "database_name": "live_runtime_mcp",
  "table_name": "margin_trade_daily",
  "storage_location": "live_runtime_mcp.margin_trade_daily",
  "archive_table_name": "margin_trade_daily_weekly_snapshot",
  "archive_storage_location": "live_runtime_mcp.margin_trade_daily_weekly_snapshot",
  "hot_retention_days": 7,
  "archive_retention_days": 1825,
  "history_granularity": "DAILY_7D_WEEKLY",
  "fields": [
    {
      "field_name": "security_code",
      "field_type": "STRING",
      "business_description": "证券代码"
    }
  ],
  "read_tool": "web_search"
}
```

数据库是权威数据源。OpenSearch未启用或检索异常时，资产发现退回查询 `market_asset_catalog`；索引写入失败不会删除已经落库的事实数据。MCP启动后，目录同步器会根据数据库中已有目录重新构建或补齐索引文档。

## 8. Agent读取流程

外部只发布一个 `web_search`。

### 8.1 资产发现

```json
{
  "query": "贵州茅台融资余额",
  "num_results": 10
}
```

MCP并行获取：

- News Runtime 的新闻检索结果；
- `financial-data-asset` 的市场数据资产结果。
- 与查询意图匹配的数据集中的实际金融观测行。

金融资产结果返回 `resultType=financial_data_asset`、`dataset`、业务说明、`storageLocation`、`archiveStorageLocation` 和保留策略。MCP同时根据查询中的市场类型、指数名称和证券代码自动选择相关数据集，通过 `FinancialDataStore.query()` 读取事实数据，并以 `resultType=financial_data` 返回。数据库连接信息和SQL不会暴露给Agent。

一次统一搜索的顶层结果包含：

- `financialDatasetCount`：本次实际读取的数据集数量；
- `financialObservationCount`：本次实际返回的金融观测行数；
- `financialData`：按数据集组织的紧凑事实结果。

因此Agent不能在已返回 `financialObservationCount > 0` 时把结果判断成“只有资产元数据”，也不需要为了获得事实数据重复调用同一个工具。

### 8.2 事实读取

需要精确控制数据集、证券代码、日期范围或历史层级时，可以显式指定 `dataset` 调用同一个工具：

```json
{
  "dataset": "margin_trade_daily",
  "filters": {
    "securityCode": "600519"
  },
  "startDate": "2026-07-01",
  "endDate": "2026-07-22",
  "historyMode": "auto",
  "limit": 50
}
```

显式数据集查询不读取OpenSearch明细，而是调用 `FinancialDataStore.query()`：

1. 根据 `dataset` 查询 `market_asset_catalog`。
2. 获取受管物理表和已登记字段。
3. 只接受Schema注册字段的等值过滤，以及字符串字段的受控 `Like` 过滤。
4. 应用观测日期范围。
5. 根据 `historyMode` 选择存储层：`daily` 只查7天热明细，`weekly` 只查周快照，`auto` 根据时间范围查询一个或两个层级并合并。
6. 返回行通过 `_storage_tier=daily_hot|weekly_snapshot` 标明证据来源。
7. 按观测日期和采集时间倒序返回。
8. 默认最多50行，硬上限200行。

行情快照不要求写入全文索引。对于证券名称检索，可以使用受治理的 `Like` 后缀过滤器：

```json
{
  "dataset": "market_quote_daily",
  "filters": {
    "quoteNameLike": "包钢股份"
  },
  "limit": 20
}
```

`Like` 仅允许作用于 `data_schema_registry` 中登记为 `STRING` 的字段，底层使用参数化
`lower(column) like ?` 查询，并继续应用日期范围与最大返回行数限制。统一搜索识别到
“包钢股份（600010）”时，会分别执行 `quoteCode=600010` 和
`quoteNameLike=包钢股份`，然后按行情记录键合并去重。

返回模型前会生成 `resultView=compact_model_context` 的紧凑视图。`payload_json`、`*_history` 等可重复恢复的大字段不进入模型上下文，过长字符串也会被省略，并在 `_omitted_fields` 中标记；数据库中的原始事实数据不会因此改变。这样可避免协议响应或模型上下文被历史序列挤满后发生截断。

`auto` 是默认值。未提供开始日期时优先查询近期热明细；开始日期早于热数据边界时查询周快照，若结束日期仍覆盖最近7天，则同时读取热明细并合并。这样资产发现仍只有一份逻辑数据集，模型不需要记忆两张物理表。

## 9. 一致性和失败边界

- 数据库是事实数据和资产目录的最终权威来源。
- OpenSearch是可重建的检索投影，不是事实存储。
- 只有事实数据写入成功后才触发OpenSearch目录更新。
- OpenSearch失败时保留数据库数据，并在后续观测或启动同步时重试。
- MCP不可用时，News Runtime不能把市场观测错误写入新闻索引。
- 动态DDL与事实DML应继续保持严格的错误日志；后续若增强事务，需要注意MySQL DDL可能隐式提交，应将Schema演进和事实DML设计为可重入操作。

## 10. 代码位置

| 职责 | 代码 |
| --- | --- |
| News市场数据识别与发送 | `chatchat-runtime-news/.../collector/McpMarketIngestionClient.java` |
| News/Market入库分流 | `chatchat-runtime-news/.../collector/NewsIngestionService.java` |
| MCP内部接收入口 | `chatchat-mcp-server/.../market/MarketInternalController.java` |
| MCP内部HMAC鉴权 | `chatchat-mcp-server/.../market/MarketInternalAuthFilter.java` |
| 数据集识别与批量展开 | `chatchat-runtime-market/.../storage/FinancialDataIngestionService.java` |
| 自动建表、Schema与事实查询 | `chatchat-runtime-market/.../storage/FinancialDataStore.java` |
| 周快照归档与热数据清理 | `chatchat-runtime-market/.../storage/FinancialDataRetentionScheduler.java` |
| 数据资产目录 | `chatchat-runtime-market/.../storage/FinancialAssetCatalogService.java` |
| MCP OpenSearch适配 | `chatchat-mcp-server/.../market/McpMarketAssetCatalogIndex.java` |
| 统一web_search | `chatchat-mcp-server/.../news/RemoteNewsMcpToolProvider.java` |
