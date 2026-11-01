SELECT
    d.d_year                                            AS sales_year,
    COUNT(*)                                            AS sales_rows,
    SUM(ss.ss_quantity)                                 AS total_quantity,
    SUM(ss.ss_net_paid)                                 AS total_net_paid,
    SUM(ss.ss_net_profit)                               AS total_net_profit,
    MIN(d.d_date)                                       AS earliest_date,
    MAX(d.d_date)                                       AS latest_date
FROM store_sales  ss
INNER JOIN date_dim d
        ON d.d_date_sk = ss.ss_sold_date_sk
WHERE ss.ss_item_sk = 1          
GROUP BY d.d_year
ORDER BY sales_year ASC
LIMIT 10