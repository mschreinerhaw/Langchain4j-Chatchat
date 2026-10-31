SELECT COUNT(*) AS excluded_rows,
       SUM(s.ss_net_paid) AS excluded_net_paid,
       SUM(s.ss_net_profit) AS excluded_net_profit
FROM tpcds.store_sales s
JOIN tpcds.date_dim d ON d.d_date_sk = s.ss_sold_date_sk
WHERE s.ss_item_sk = 1
  AND d.d_date BETWEEN '1999-01-01' AND '1999-12-31'
  AND s.ss_quantity IS NULL;
