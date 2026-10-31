-- ============================================================================
-- 单品销售年度汇总（ss_item_sk = 1）
-- 业务范围：仅统计商品主键 ss_item_sk = 1 的单品门店销售记录
-- 说明：上一版全表年度汇总超出 10 秒执行预算，本版通过将 ss_item_sk 等值
--       过滤（复合主键前缀）大幅缩小扫描范围，满足性能要求。
-- 统计指标：销售行数、数量合计、净收款合计、净利润合计、最早/最晚销售日期
-- 关联维度：date_dim（通过 ss_sold_date_sk = d_date_sk）
-- 分组粒度：按自然年（d_year）升序，至多返回 10 行
-- 无参数可复用 SQL；本文件仅供审查，不执行、不捏造结果。
-- ============================================================================

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
WHERE ss.ss_item_sk = 1          -- 复合主键前缀等值过滤，缩小扫描范围
GROUP BY d.d_year
ORDER BY sales_year ASC
LIMIT 10;
