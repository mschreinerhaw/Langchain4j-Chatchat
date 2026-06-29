package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QueryPlan(
    String schemaVersion,
    String question,
    String strategy,
    List<PlanNode> steps,
    JoinGraph joinGraph,
    Map<String, Object> costModel,
    Map<String, Object> diagnostics
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("question", question);
        value.put("strategy", strategy);
        value.put("steps", steps == null ? List.of() : steps.stream().map(PlanNode::toDiagnostic).toList());
        value.put("joinGraph", joinGraph == null ? null : joinGraph.toDiagnostic());
        value.put("costModel", costModel == null ? Map.of() : costModel);
        value.put("diagnostics", diagnostics == null ? Map.of() : diagnostics);
        return value;
    }
}
