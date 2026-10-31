SELECT YEAR(d.d_date) AS sale_year, MONTH(d.d_date) AS sale_month,
       i.i_category AS category, COUNT(*) AS row_count,
       SUM(s.ss_quantity) AS total_quantity, SUM(s.ss_net_paid) AS total_net_paid,
       SUM(s.ss_net_profit) AS total_net_profit
FROM tpcds.store_sales s
JOIN tpcds.date_dim d ON d.d_date_sk = s.ss_sold_date_sk
JOIN tpcds.item i ON i.i_item_sk = s.ss_item_sk
WHERE s.ss_item_sk = 1
  AND d.d_date BETWEEN '1999-01-01' AND '1999-12-31'
  AND s.ss_quantity >= 0
GROUP BY YEAR(d.d_date), MONTH(d.d_date), i.i_category
ORDER BY sale_year, sale_month, category;
