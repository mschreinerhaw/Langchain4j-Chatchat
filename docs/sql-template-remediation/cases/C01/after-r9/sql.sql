SELECT
    w_country,                                              
    w_state,                                                
    COUNT(*)                AS warehouse_count,             
    COUNT(DISTINCT w_city)  AS city_count,                  
    MIN(w_warehouse_sk)     AS min_warehouse_sk,            
    MAX(w_warehouse_sk)     AS max_warehouse_sk,            
    (SELECT MIN(w_warehouse_sk) FROM warehouse)
                            AS global_min_warehouse_sk      
FROM warehouse
GROUP BY
    w_country,
    w_state
ORDER BY
    w_country,
    w_state