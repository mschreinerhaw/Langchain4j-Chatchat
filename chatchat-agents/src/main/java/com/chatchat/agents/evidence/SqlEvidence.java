package com.chatchat.agents.evidence;

import java.util.List;

public record SqlEvidence(
    String rawSql,
    String normalizedSql,
    SqlType sqlType,
    List<String> tableNames,
    List<String> columns,
    boolean complete
) {

    public SqlEvidence {
        rawSql = rawSql == null ? "" : rawSql;
        normalizedSql = normalizedSql == null ? "" : normalizedSql;
        sqlType = sqlType == null ? SqlType.UNKNOWN : sqlType;
        tableNames = tableNames == null ? List.of() : List.copyOf(tableNames);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
