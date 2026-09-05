package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.insight.SafeNumericExpression;
import com.chatchat.agents.protocol.ModelProtocolJson;
import java.math.BigDecimal;
import java.util.*;

/** New arithmetic over runtime-produced values; computation is distinct from semantic admission. */
final class SupplementaryFormulaExecutor {
    Map<String, Object> execute(Map<String, Object> context, Map<String, Object> request) {
        Map<String, Map<String, Object>> available = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Object inputs = context.get("runtimeAnalysisInputs");
        Object calculations = inputs instanceof Map<?, ?> map ? map.get("verifiedCalculations") : null;
        if (calculations instanceof List<?> list) for (Object result : list) {
            if (!(result instanceof Map<?, ?> map) || !"executed".equals(map.get("status"))) continue;
            if (map.get("findings") instanceof List<?> findings) for (Object item : findings) {
                if (item instanceof com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine.Finding f) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("id", f.id()); normalized.put("value", f.value());
                    normalized.put("unit", f.unit()); normalized.put("evidenceRefs", f.evidenceRefs());
                    item = normalized;
                }
                if (!(item instanceof Map<?, ?> finding) || !(finding.get("id") instanceof String id)) continue;
                @SuppressWarnings("unchecked") var typed = (Map<String, Object>) finding;
                if (available.putIfAbsent(id, typed) != null) ambiguous.add(id);
            }
        }
        if (!(request.get("inputs") instanceof Map<?, ?> bindings) || bindings.isEmpty() || bindings.size() > 16)
            throw new IllegalArgumentException("Formula requires 1..16 runtime calculation references");
        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        Map<String, Object> lineage = new LinkedHashMap<>();
        for (var binding : bindings.entrySet()) {
            String name = String.valueOf(binding.getKey());
            String id = String.valueOf(binding.getValue());
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,31}") || ambiguous.contains(id) || !available.containsKey(id))
                throw new IllegalArgumentException("Unknown or ambiguous formula input");
            var finding = available.get(id);
            variables.put(name, new BigDecimal(String.valueOf(finding.get("value"))));
            lineage.put(name, finding);
        }
        String expression = String.valueOf(request.get("expression"));
        BigDecimal value = SafeNumericExpression.evaluate(expression, variables);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", "CALCULATE"); result.put("expression", expression); result.put("value", value);
        result.put("calculationId", ModelProtocolJson.sha256Hex(Map.of("expression", expression, "inputs", lineage)));
        result.put("inputLineage", lineage);
        result.put("status", "COMPUTED_REQUIRES_SEMANTIC_REVIEW");
        result.put("conclusionEligible", false);
        result.put("limitation", "Runtime verified arithmetic only. Unit, grain, population and business meaning require semantic admission; model-proposed formulas are not authorized business metrics.");
        return result;
    }
}
