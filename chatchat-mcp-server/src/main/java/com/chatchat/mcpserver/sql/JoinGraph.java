package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record JoinGraph(
    String schemaVersion,
    List<JoinEdge> edges,
    Map<String, List<JoinEdge>> adjacency
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("edges", edges == null ? List.of() : edges.stream().map(JoinEdge::toDiagnostic).toList());
        value.put("adjacency", adjacency == null ? Map.of() : adjacency.entrySet().stream()
            .collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue().stream().map(JoinEdge::toDiagnostic).toList()),
                LinkedHashMap::putAll));
        return value;
    }
}
