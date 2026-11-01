SELECT
    d.d_year                           AS sale_year,
    d.d_moy                            AS sale_month,
    i.i_category                       AS category,
    COUNT(*)                           AS row_count,
    SUM(ss.ss_quantity)                AS total_quantity,
    SUM(ss.ss_net_paid)                AS total_net_paid,
    SUM(ss.ss_net_profit)              AS total_net_profit
FROM tpcds.store_sales ss
INNER JOIN tpcds.date_dim d
        ON ss.ss_sold_date_sk = d.d_date_sk
INNER JOIN tpcds.item i
        ON ss.ss_item_sk = i.i_item_sk
WHERE ss.ss_item_sk = ?
  AND d.d_date >= ?
  AND d.d_date <= ?
  AND ss.ss_quantity >= ?
  AND (? IS NULL OR i.i_category = ?)
GROUP BY d.d_year,
         d.d_moy,
         i.i_category
ORDER BY d.d_year ASC,
         d.d_moy ASC,
         i.i_category ASC
LIMIT 24