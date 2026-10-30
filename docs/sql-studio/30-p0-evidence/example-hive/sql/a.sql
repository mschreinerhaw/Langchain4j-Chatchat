-- sql/a.sql
-- Order details: order identifier, customer identifier, order amount
-- Source: APP.ORDERS (Oracle)
-- Hive dialect
SELECT
    o.ID           AS order_id,
    o.CUSTOMER_ID  AS customer_id,
    o.AMOUNT       AS order_amount
FROM APP.ORDERS o
;
