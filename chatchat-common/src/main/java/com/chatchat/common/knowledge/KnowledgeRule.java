package com.chatchat.common.knowledge;

import java.util.List;

/** Normalized rule carried by Knowledge IR. */
public record KnowledgeRule(String name, String expression, List<String> requiredInputs) {
    public KnowledgeRule {
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
    }
}
