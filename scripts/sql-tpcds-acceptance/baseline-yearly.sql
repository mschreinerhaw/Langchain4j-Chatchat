SELECT YEAR(d.d_date) AS sales_year, COUNT(*) AS sales_rows,
       SUM(s.ss_quantity) AS total_quantity, SUM(s.ss_net_paid) AS total_net_paid,
       SUM(s.ss_net_profit) AS total_net_profit,
       MIN(d.d_date) AS earliest_date, MAX(d.d_date) AS latest_date
FROM tpcds.store_sales s
JOIN tpcds.date_dim d ON d.d_date_sk = s.ss_sold_date_sk
WHERE s.ss_item_sk = 1
GROUP BY YEAR(d.d_date)
ORDER BY sales_year;
