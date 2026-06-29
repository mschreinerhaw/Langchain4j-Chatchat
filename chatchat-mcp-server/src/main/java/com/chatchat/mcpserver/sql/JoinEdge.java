package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.Map;

public record JoinEdge(
    String leftTable,
    String leftColumn,
    String rightTable,
    String rightColumn,
    String joinKey,
    double confidence,
    String reason
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("leftTable", leftTable);
        value.put("leftColumn", leftColumn);
        value.put("rightTable", rightTable);
        value.put("rightColumn", rightColumn);
        value.put("joinKey", joinKey);
        value.put("confidence", confidence);
        value.put("reason", reason);
        return value;
    }
}
