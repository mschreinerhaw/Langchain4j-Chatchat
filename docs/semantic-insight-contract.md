# Deterministic semantic insight contracts

Formula contracts live in `runtime_semantic_insight_contract`. MCP metadata and results are
descriptive evidence only; the adapter strips `deterministicInsights` so tools cannot install
executable formulas.

Agent Runtime is fail-closed. Resolution requires `semanticInsightRequested=true` or explicit
`semanticInsightContractIds`, plus a published enabled row in the current tenant. Every configured
`agent_id`, `tool_name`, `dataset_key`, and `task_type` binding must match. `EXPLICIT_ONLY` is the
only supported activation mode. Expired, malformed, cross-tenant, and mismatched rows are skipped.

Create a new `(tenant_id, contract_key, contract_version)` row for each revision. Values for tenant,
contract id, version, and publication status come from columns and override JSON values.

The JSON is business-neutral: it maps source fields to semantics and defines generic operations.

```json
{
  "datasetAlias": "positions",
  "fields": [
    {"field": "ZQDM", "semantic": "entity", "label": "security code"},
    {"field": "ZXSZ", "semantic": "market_value", "unit": "CNY", "aggregation": "SUM"},
    {"field": "DRYK", "semantic": "daily_profit", "unit": "CNY", "aggregation": "SUM"}
  ],
  "recipes": [
    {"id": "market-value-total", "operator": "SUM", "metric": "market_value"},
    {"id": "top-profit", "operator": "CONTRIBUTION", "groupBy": "entity", "valueMetric": "daily_profit", "topN": 3},
    {"id": "top3-concentration", "operator": "CONCENTRATION", "metric": "market_value", "topN": 3},
    {"id": "profit-value-outlier", "operator": "OUTLIER_RATIO", "numerator": "daily_profit", "denominator": "market_value", "absoluteThreshold": 0.2}
  ]
}
```

Supported operators: `SUM`, `TOP_N`, `CONTRIBUTION`, `CONCENTRATION`, `RECONCILIATION`,
`OUTLIER_RATIO`, `TAG_MATCH`, `BUNDLE_RECONCILIATION`, and `BUNDLE_RATIO`. Cross-dataset formulas
refer only to declared aliases and aggregates. Do not bind these contracts to general chat,
document summarization, code generation, or other Agents without deterministic structured analysis.
