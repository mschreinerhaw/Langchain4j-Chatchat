SELECT
    dd.d_date                                                       AS snapshot_date,
    
    COUNT(*)                                                        AS sku_record_count,
    
    SUM(CASE WHEN inv.inv_quantity_on_hand > 0 THEN 1 ELSE 0 END)  AS nonzero_sku_count,
    
    SUM(CASE WHEN inv.inv_quantity_on_hand = 0 THEN 1 ELSE 0 END)  AS zero_sku_count,
    
    SUM(inv.inv_quantity_on_hand)                                   AS total_on_hand
FROM inventory inv
INNER JOIN date_dim dd
    ON inv.inv_date_sk = dd.d_date_sk
INNER JOIN item i
    ON inv.inv_item_sk = i.i_item_sk
WHERE inv.inv_warehouse_sk = ?
  AND dd.d_date >= ?
  AND dd.d_date <= ?
  AND inv.inv_quantity_on_hand >= ?
  AND (? IS NULL OR i.i_category = ?)
GROUP BY dd.d_date
ORDER BY dd.d_date ASC
LIMIT 100