package com.chatchat.agents.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvidenceAnswer(
    String answer,
    List<Map<String, Object>> citations,
    String confidence,
    List<String> missingInfo
) {

    public EvidenceAnswer {
        answer = answer == null ? "" : answer;
        citations = citations == null
            ? List.of()
            : citations.stream()
                .map(item -> item == null ? Map.<String, Object>of() : new LinkedHashMap<>(item))
                .toList();
        confidence = confidence == null || confidence.isBlank() ? "medium" : confidence;
        missingInfo = missingInfo == null ? List.of() : List.copyOf(missingInfo);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("answer", answer);
        values.put("citations", citations);
        values.put("confidence", confidence);
        values.put("missingInfo", missingInfo);
        return values;
    }
}
