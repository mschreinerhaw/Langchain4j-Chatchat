package com.chatchat.mcpserver.datacapability.execution;

import com.chatchat.mcpserver.datacapability.definition.*;
import java.util.List;
import java.util.Map;

public interface CapabilityAdapter {
    CapabilityType type();
    void validate(CapabilityDefinition definition);
    QueryResult execute(CapabilityDefinition definition, Map<String, Object> parameters) throws Exception;

    record QueryResult(List<Map<String, Object>> rows, boolean truncated, Map<String, Object> metadata) {
        public QueryResult(List<Map<String, Object>> rows) { this(rows, false, Map.of()); }
    }
}
