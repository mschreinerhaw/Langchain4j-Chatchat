# 华是科技 2026-11-02 行情分析报告

## 一、业务结论

**华是科技**（证券代码：301218，推断交易所：SZSE (深圳证券交易所)）在 2026-11-02 当日已收集行情快照如下：

| 指标 | 数值 |
|------|------|
| 收盘价 (close) | 81.44 |
| 开盘价 (open) | 68.68 |
| 最高价 (high) | 81.44 |
| 最低价 (low) | 65.0 |
| 昨收价 (previous_close) | 67.87 |
| 涨跌幅 (change_pct) | 19.99% |
| 成交额 (amount) | 118831.96 10K-CNY |
| 成交量 (volume) | 1617.35 万单位 |
| 市盈率 (pe_ratio) | 0.0 |

**价格货币单位**：未知（数据源未声明货币单位，不自动推断为元）
**成交量单位**：万单位（原始字段 volume10_k_units，非万手）

## 二、同源同市场同类型样本排名

排名基准：source_id=38, instrument_type=EQUITY, trade_date=2026-11-02
样本总数：2943

| 排名维度 | 华是科技排名 | 有效样本数 | 说明 |
|----------|-------------|-----------|------|
| 涨跌幅 (change_pct) 降序 | 12/2943 | 2943 | 涨幅从高到低 |
| 成交额 (amount) 降序 | 141/2943 | 2943 | 成交额从高到低 |
| 成交量 (volume) 降序 | 1058/2943 | 2943 | 成交量从高到低 |
| 市盈率 (pe_ratio) 升序 | 542/2943 | 2943 | PE从低到高 |

## 三、证券代码与交易所核对

- 证券代码：301218
- 证券名称：华是科技
- 推断交易所：SZSE (深圳证券交易所)
- 数据源 provider：SZSE
- 数据源 source_code：szse_daily_snapshot
- instrument_type：EQUITY
- category：市场行情

## 四、深交所上市公告缺口

**本地附件不包含上市公告原文。**

外部检索状态：
- Internet Search API 状态：**UNAVAILABLE**（原因：MODEL_INTERNET_DISABLED，配置/授权层面不可用）
- 因此无法自主检索深交所上市公告原文
- 以下信息**无法从本地数据获取**，构成结构化缺口：
  - 上市日期（listing_date）：未知
  - 发行数量（issue_shares）：未知
  - 官方公告原文链接：未知

## 五、数据来源与证据

- 行情数据源：native parquet (sha256: 32e2b118...0ba2bc4, 2943 rows)
- Producer 声明：market-producer-declarations.json (sha256: eddedb8e...34e244fa)
- Schema 定义：schema.json (sha256: 34a62adb...b15d7bdd)
- observation_date: 2026-11-02
- collected_at: 2026-11-03T03:59:10.146+00:00
- trade_date: 2026-11-02
- snapshot_mode: OVERWRITE_BY_EXCHANGE_TYPE_DATE_SECURITY
- dataset: 行情, dataset_code: market_quote_daily

## 六、重要声明

1. **历史快照不是实时价**：本报告数据为 2026-11-02 当日已收集快照，不代表实时行情
2. **单源样本不代表交易所采集完整**：排名仅基于当前数据源提供的同源同类型样本
3. **价格货币未知**：数据源未声明货币单位，不自动写成「元」
4. **成交量单位**：原始字段为 volume10_k_units（万单位），不写成「万手」
5. **不可推断多日走势**：当前只有单日快照，不能推断多日走势或实时价