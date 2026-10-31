-- tpcds monthly sales summary for a specific item
-- Database: tpcds (test env 248)
-- Placeholders (unquoted, substituted by template engine):
--   {{product_id}}   – integer, ss_item_sk value (required)
--   {{start_date}}   – date literal, inclusive start
--   {{end_date}}     – date literal, inclusive end
--   {{min_quantity}} – integer, minimum ss_quantity per row
--   {{category}}     – string literal or NULL; NULL skips the category filter

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
WHERE ss.ss_item_sk = {{product_id}}
  AND d.d_date >= {{start_date}}
  AND d.d_date <= {{end_date}}
  AND ss.ss_quantity >= {{min_quantity}}
  AND ({{category}} IS NULL OR i.i_category = {{category}})
GROUP BY d.d_year,
         d.d_moy,
         i.i_category
ORDER BY d.d_year ASC,
         d.d_moy ASC,
         i.i_category ASC
LIMIT 24;
