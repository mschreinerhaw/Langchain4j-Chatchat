package com.chatchat.agents.runtime.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AnswerTrace(
    String answer,
    String contractVersion,
    List<Map<String, Object>> citations,
    String confidence,
    List<String> missingInfo
) {

    public AnswerTrace {
        citations = citations == null
            ? List.of()
            : citations.stream()
                .map(item -> item == null ? Map.<String, Object>of() : new LinkedHashMap<>(item))
                .toList();
        missingInfo = missingInfo == null ? List.of() : List.copyOf(missingInfo);
    }
}
