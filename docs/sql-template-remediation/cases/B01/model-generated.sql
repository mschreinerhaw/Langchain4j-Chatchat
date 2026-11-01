-- ============================================================
-- warehouse_geography.sql
-- 仓库地理分布维表查询（无参数、只读、全量）
-- 口径：按国家(w_country)、省州(w_state)分组，
--       统计每组仓库数量、覆盖城市数量，
--       并返回该组最小/最大仓库主键(w_warehouse_sk)，
--       便于后续选择仓库开展库存分析。
-- 保留空值分组（不对 NULL 做过滤），按国家、省州升序排列。
-- 不固定任何商品或仓库，不添加人为业务过滤条件。
-- 数据来源：tpcds.warehouse（基于已注册元数据）
-- ============================================================

SELECT
    w_country,                                              -- 国家
    w_state,                                                -- 省州
    COUNT(*)                AS warehouse_count,             -- 该分组仓库数量
    COUNT(DISTINCT w_city)  AS city_count,                  -- 该分组覆盖城市数量
    MIN(w_warehouse_sk)     AS min_warehouse_sk,            -- 最小仓库主键
    MAX(w_warehouse_sk)     AS max_warehouse_sk             -- 最大仓库主键
FROM warehouse
GROUP BY
    w_country,
    w_state
ORDER BY
    w_country,
    w_state;
