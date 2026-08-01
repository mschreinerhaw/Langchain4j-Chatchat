package com.chatchat.runtime.market.storage;

import java.util.List;
import java.util.Map;

/** Read-lane abstraction that allows financial analytics to use an isolated connection pool. */
@FunctionalInterface
public interface FinancialReadOperations {
    List<Map<String, Object>> queryForList(String sql, Object... arguments);

    default List<Map<String, Object>> queryForList(String sql,
                                                   Map<String, Object> parameters,
                                                   int maxRows,
                                                   int timeoutSeconds) {
        throw new UnsupportedOperationException("Named financial queries are not supported by this read lane");
    }
}
