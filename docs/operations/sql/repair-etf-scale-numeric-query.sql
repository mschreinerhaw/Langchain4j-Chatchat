-- Governed query configuration, not a Runtime business rule.
-- Replace sqlTemplate and ANALYZE.sqlContent together through the administration API.
-- Validate grouped decimals; unknown or malformed values remain NULL.
SELECT metrics.*,
       COUNT(*) OVER () AS latest_fund_count,
       SUM(CASE WHEN previous_scale_10k_units IS NOT NULL THEN 1 ELSE 0 END) OVER () AS comparable_fund_count,
       SUM(current_scale_10k_units) OVER () AS total_current_scale_10k_units,
       SUM(previous_scale_10k_units) OVER () AS total_previous_scale_10k_units,
       SUM(scale_change_10k_units) OVER () AS total_scale_change_10k_units
FROM (
    SELECT current_rows.observation_date,
           previous_rows.observation_date AS previous_observation_date,
           current_rows.fund_code,
           (CASE WHEN REGEXP_LIKE(TRIM(CAST(current_rows.fund_scale10_k_units AS VARCHAR)), '^[+-]?([0-9]{1,26}|[0-9]{1,3}(,[0-9]{3}){1,7})([.][0-9]{1,4})?$') THEN CAST(REGEXP_REPLACE(TRIM(CAST(current_rows.fund_scale10_k_units AS VARCHAR)), ',', '') AS DECIMAL(30,4)) ELSE NULL END) AS current_scale_10k_units,
           (CASE WHEN REGEXP_LIKE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), '^[+-]?([0-9]{1,26}|[0-9]{1,3}(,[0-9]{3}){1,7})([.][0-9]{1,4})?$') THEN CAST(REGEXP_REPLACE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), ',', '') AS DECIMAL(30,4)) ELSE NULL END) AS previous_scale_10k_units,
           (CASE WHEN REGEXP_LIKE(TRIM(CAST(current_rows.fund_scale10_k_units AS VARCHAR)), '^[+-]?([0-9]{1,26}|[0-9]{1,3}(,[0-9]{3}){1,7})([.][0-9]{1,4})?$') THEN CAST(REGEXP_REPLACE(TRIM(CAST(current_rows.fund_scale10_k_units AS VARCHAR)), ',', '') AS DECIMAL(30,4)) ELSE NULL END) - (CASE WHEN REGEXP_LIKE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), '^[+-]?([0-9]{1,26}|[0-9]{1,3}(,[0-9]{3}){1,7})([.][0-9]{1,4})?$') THEN CAST(REGEXP_REPLACE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), ',', '') AS DECIMAL(30,4)) ELSE NULL END) AS scale_change_10k_units,
           CASE WHEN previous_rows.fund_scale10_k_units IS NULL OR (CASE WHEN REGEXP_LIKE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), '^[+-]?([0-9]{1,26}|[0-9]{1,3}(,[0-9]{3}){1,7})([.][0-9]{1,4})?$') THEN CAST(REGEXP_REPLACE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), ',', '') AS DECIMAL(30,4)) ELSE NULL END) = 0 THEN NULL
                ELSE ROUND(((CASE WHEN REGEXP_LIKE(TRIM(CAST(current_rows.fund_scale10_k_units AS VARCHAR)), '^[+-]?([0-9]{1,26}|[0-9]{1,3}(,[0-9]{3}){1,7})([.][0-9]{1,4})?$') THEN CAST(REGEXP_REPLACE(TRIM(CAST(current_rows.fund_scale10_k_units AS VARCHAR)), ',', '') AS DECIMAL(30,4)) ELSE NULL END) - (CASE WHEN REGEXP_LIKE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), '^[+-]?([0-9]{1,26}|[0-9]{1,3}(,[0-9]{3}){1,7})([.][0-9]{1,4})?$') THEN CAST(REGEXP_REPLACE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), ',', '') AS DECIMAL(30,4)) ELSE NULL END)) * 100 / (CASE WHEN REGEXP_LIKE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), '^[+-]?([0-9]{1,26}|[0-9]{1,3}(,[0-9]{3}){1,7})([.][0-9]{1,4})?$') THEN CAST(REGEXP_REPLACE(TRIM(CAST(previous_rows.fund_scale10_k_units AS VARCHAR)), ',', '') AS DECIMAL(30,4)) ELSE NULL END), 4) END AS scale_change_pct,
           current_rows.source_code
    FROM (
        SELECT latest_source.*
        FROM (
            SELECT source_rows.*,
                   ROW_NUMBER() OVER (PARTITION BY observation_date, source_code, source_url ORDER BY collected_at DESC, id DESC) AS observation_rank
            FROM etf_scale_daily source_rows
            WHERE observation_date = (SELECT MAX(observation_date) FROM etf_scale_daily)
        ) latest_source
        WHERE latest_source.observation_rank = 1
    ) current_rows
    LEFT JOIN (
        SELECT previous_source.*
        FROM (
            SELECT source_rows.*,
                   ROW_NUMBER() OVER (PARTITION BY observation_date, source_code, source_url ORDER BY collected_at DESC, id DESC) AS observation_rank
            FROM etf_scale_daily source_rows
            WHERE observation_date = (SELECT MAX(observation_date) FROM etf_scale_daily WHERE observation_date < (SELECT MAX(observation_date) FROM etf_scale_daily))
        ) previous_source
        WHERE previous_source.observation_rank = 1
    ) previous_rows ON previous_rows.fund_code = current_rows.fund_code
                   AND previous_rows.source_code = current_rows.source_code
) metrics
ORDER BY ABS(COALESCE(scale_change_10k_units, 0)) DESC, current_scale_10k_units DESC
LIMIT 100;
