-- Default semantic lexicon entries for query normalization and expansion.
-- These rows are built-in defaults: user-configured lexicon entries should use
-- a higher priority, while built-in rows must not be deleted by application logic.

INSERT INTO rule_version (type, version, active, created_at, updated_at)
SELECT 'lexicon', 1, TRUE, 0, 0
WHERE NOT EXISTS (
  SELECT 1 FROM rule_version WHERE type = 'lexicon' AND version = 1
);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '营收', '营收', 'zh', 'revenue', '收入,营业收入,sales,turnover', 'metric', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '营收' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '利润', '利润', 'zh', 'profit', '净利润,earnings,net income', 'metric', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '利润' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '毛利率', '毛利率', 'zh', 'gross margin', '毛利,gross margin,gross profit margin', 'metric', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '毛利率' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '资产负债率', '资产负债率', 'zh', 'debt ratio', '负债率,leverage,debt to asset', 'metric', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '资产负债率' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '现金流', '现金流', 'zh', 'cash flow', '经营现金流,operating cash flow,free cash flow', 'metric', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '现金流' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '同比', '同比', 'zh', 'year over year', 'yoy,year-on-year,yearly growth', 'time', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '同比' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '环比', '环比', 'zh', 'period over period', 'mom,qoq,month over month,quarter over quarter', 'time', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '环比' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '资产管理规模', '资产管理规模', 'zh', 'assets under management', 'aum,管理规模', 'metric', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '资产管理规模' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '客户', '客户', 'zh', 'customer', 'client,用户,客群', 'entity', 'finance', 1, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '客户' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '订单', '订单', 'zh', 'order', 'transaction,交易,单据', 'entity', 'finance', 1, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '订单' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '指标', '指标', 'zh', 'metric', 'measure,kpi,关键指标', 'metric', 'data', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '指标' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '口径', '口径', 'zh', 'definition', '统计口径,calculation logic,caliber', 'definition', 'data', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '口径' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '字段', '字段', 'zh', 'field', 'column,列,字段名', 'schema', 'data', 1, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '字段' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '表', '表', 'zh', 'table', 'dataset,数据表,sheet', 'schema', 'data', 1, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '表' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '同步', '同步', 'zh', 'sync', 'synchronize,replicate,数据同步', 'operation', 'data', 1, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '同步' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '延迟', '延迟', 'zh', 'latency', 'delay,lag,时延', 'quality', 'data', 1, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '延迟' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '异常值', '异常值', 'zh', 'outlier', 'anomaly,abnormal value', 'quality', 'data', 1, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '异常值' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '缺失值', '缺失值', 'zh', 'missing value', 'null,空值,blank', 'quality', 'data', 1, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '缺失值' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT 'revenue', 'revenue', 'en', '营收', 'sales,turnover,收入,营业收入', 'metric', 'finance', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = 'revenue' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT 'metric', 'metric', 'en', '指标', 'measure,kpi,关键指标', 'metric', 'data', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = 'metric' AND builtin = TRUE);
