# chatchat-runtime-market

MCP Server 内部的市场数据能力库，不是独立运行服务，也不生成可单独启动的 Spring Boot 应用。

详细设计与数据写入链路见：[Market 数据资产设计](doc/design.md)。

模块负责：

- 行情、估值、融资融券、分红送配、ETF 规模和市场统计的数据集定义。
- 根据采集结果自动创建和演进业务表。
- `market_asset_catalog`、`data_schema_registry` 与受控结构化查询。
- 7天热明细、每周快照归档、自动清理及跨存储层查询。
- 面向 MCP 的资产目录索引接口。

模块不包含：

- MySQL/H2 地址、用户名或密码。
- OpenSearch 地址、用户名或密码。
- 独立端口、内部 HTTP 服务或独立鉴权配置。
- 独立发行包和启动脚本。

## 发布与运行

`chatchat-mcp-server` 直接依赖本模块。Maven 构建时，本模块的普通 JAR 会作为依赖打入 MCP Server 的 Spring Boot JAR：

```bash
mvn -pl chatchat-mcp-server -am package
```

运行时统一使用 MCP Server 的：

- Spring `DataSource`；
- `chatchat.mcp.lucene.open-search` 连接；
- 内部账号、密钥和加密配置；
- 发布、权限与工具治理配置。

模块只保留非连接型行为参数，前缀为 `chatchat.mcp.market`，包括是否启用、目录索引名称、查询上限、分区数以及热数据/周快照保留期；不配置任何基础设施凭据。

## 数据流

```text
交易所/API采集器（News Runtime中的现有调度）
  -> HMAC签名 POST MCP /internal/v1/market/observations
  -> MCP Market能力进程内落库/Schema演进
  -> MCP统一OpenSearch连接维护 financial-data-asset
  -> 公开 web_search 进程内聚合新闻与市场资产
```

MCP 数据库初始化脚本已经包含市场目录表：

- `database/init/h2/chatchat-mcp-server.sql`
- `database/init/mysql/chatchat-mcp-server.sql`

外部仍只发布 `web_search`。`news_search`、`news_latest` 和金融专用读取工具不对外注册。
