# 数据能力中心

原“数据库查询”已调整为面向 Agent 发现的数据能力中心。查询模板仍负责执行只读 SQL，但发现、分类和发布使用独立的能力元数据。

## 金融能力分类

默认分类由 `mcp_data_query_category` 管理，可通过管理接口维护，不在 Agent Runtime 中编写业务路由分支：

- `market_data`：市场行情
- `product_analysis`：产品分析
- `customer_analysis`：客户分析
- `trading_analysis`：交易分析
- `risk_management`：风险管理
- `data_validation`：数据核验
- `regulatory_reporting`：监管报送
- `data_asset_exploration`：数据资产探索

查询能力通过 `category_id` 关联分类，并保存 `capability_category`、`domain`、`business_scope` 和 `index_tags_json`。分类名称、说明和关键词均为可维护数据。

## 混合检索

数据库查询能力索引使用 BM25 与 256 维本地特征向量 KNN 混合召回。向量字段采用 OpenSearch `knn_vector`、HNSW、余弦相似度和 Lucene 引擎。

以下字段同时参与能力语义文本和检索：

- 显示名称
- 工具描述
- 功能实现步骤
- 业务范围
- 索引标签
- 查询流程工作台的步骤名称
- 查询流程工作台的步骤说明

分类和领域作为精确过滤条件，避免不同金融场景互相污染。OpenSearch 不可用时保留 Lucene 文本检索降级能力。

## 发布行为

新增或编辑查询能力时会同步分类元数据和 MCP Tool metadata，并重建数据库查询能力索引。完整重建用于保证分类、文本字段和 HNSW 向量版本一致；不改变现有 SQL 工作流或 Agent Runtime 执行设计。

每个启用的查询能力直接发布为独立的专项 MCP 工具。工具名称、输入参数、
分类、领域、业务范围、实现步骤和工作流步骤均来自查询能力自身。
停用或删除能力后，对应 MCP 工具会在发布刷新时移除。

专项工具统一采用“业务分类 + 具体能力”的命名规则：

```text
market_data_bond_yield_curve_latest
product_analysis_fund_nav_performance
customer_analysis_customer_asset_profile
trading_analysis_margin_trade_latest
risk_management_concentration_alert
regulatory_reporting_indicator_summary
data_asset_exploration_table_field_overview
```

MCP 显示名称同步增加中文业务分类前缀，例如
`【市场行情】最新中债国债收益率曲线`。分类编码和中文名称来自
`mcp_data_query_category`，Agent Runtime 不维护金融分类硬编码。

所有模型可见的 MCP 用途描述统一使用中文，包括业务领域、能力分类、
分类用途、适用范围、实现步骤、适用场景和不适用场景。协议字段名、
分类编码和 MCP 工具标识继续使用稳定英文编码，避免破坏调用契约。

系统不再发布统一的数据库查询模板检索入口。Agent 通过 MCP Tool Discovery
直接选择 `market_data`、`data_validation`、`risk_management` 等分类下的
专项工具，并直接调用其参数契约，不再执行“检索模板 → SQL 网关执行”的
两阶段流程。
