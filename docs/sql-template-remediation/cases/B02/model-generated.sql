-- =============================================================================
-- 逐日库存快照分析
-- 功能：按快照日期统计指定仓库在含边界日期期间内的库存概况
-- 统计指标：
--   sku_record_count  - SKU记录数（满足条件的库存记录条数）
--   nonzero_sku_count - 现存数量大于零的SKU数
--   zero_sku_count    - 零库存SKU数（现存数量等于零的记录条数）
--   total_on_hand     - 现存数量合计（所有满足条件的记录的数量之和，不推测计量单位）
-- 参数（占位符外不加引号，由模板引擎处理）：
--   {{warehouse_id}}  - 仓库 surrogate key（整数，必填）
--   {{start_date}}    - 起始日期（日期字符串，必填，含边界）
--   {{end_date}}      - 结束日期（日期字符串，必填，含边界）
--   {{min_on_hand}}   - 现存数量下限（整数，默认 0）
--   {{category}}      - 商品类别（可空字符串，NULL 表示不过滤类别）
-- =============================================================================

SELECT
    dd.d_date                                                       AS snapshot_date,
    /* SKU记录数：该日期下满足筛选条件的库存记录条数 */
    COUNT(*)                                                        AS sku_record_count,
    /* 现存数量大于零的SKU数 */
    SUM(CASE WHEN inv.inv_quantity_on_hand > 0 THEN 1 ELSE 0 END)  AS nonzero_sku_count,
    /* 零库存SKU数：现存数量等于零的记录条数 */
    SUM(CASE WHEN inv.inv_quantity_on_hand = 0 THEN 1 ELSE 0 END)  AS zero_sku_count,
    /* 现存数量合计：所有满足条件的库存记录的 inv_quantity_on_hand 之和（不推测计量单位） */
    SUM(inv.inv_quantity_on_hand)                                   AS total_on_hand
FROM inventory inv
INNER JOIN date_dim dd
    ON inv.inv_date_sk = dd.d_date_sk
INNER JOIN item i
    ON inv.inv_item_sk = i.i_item_sk
WHERE inv.inv_warehouse_sk = {{warehouse_id}}
  AND dd.d_date >= {{start_date}}
  AND dd.d_date <= {{end_date}}
  AND inv.inv_quantity_on_hand >= {{min_on_hand}}
  AND ({{category}} IS NULL OR i.i_category = {{category}})
GROUP BY dd.d_date
ORDER BY dd.d_date ASC
LIMIT 100;
