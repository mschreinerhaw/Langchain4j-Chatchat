package com.chatchat.common.knowledge;

import java.util.List;

/** Runtime-neutral intermediate representation produced from raw or normalized knowledge. */
public record KnowledgeIR(
    String knowledgeId,
    String domain,
    KnowledgeType type,
    String title,
    String semanticDescription,
    List<KnowledgeRule> rules,
    List<String> constraints,
    List<String> applicableIntents,
    List<String> requiredInputs,
    String compactPromptRepresentation,
    KnowledgeSourceReference source,
    double relevance
) {
    public KnowledgeIR {
        if (knowledgeId == null || knowledgeId.isBlank()) throw new IllegalArgumentException("knowledgeId is required");
        knowledgeId = knowledgeId.trim();
        domain = domain == null || domain.isBlank() ? "general" : domain.trim();
        if (type == null) throw new IllegalArgumentException("knowledge type is required");
        title = title == null ? "" : title.trim();
        semanticDescription = semanticDescription == null ? "" : semanticDescription.trim();
        rules = rules == null ? List.of() : List.copyOf(rules);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        applicableIntents = applicableIntents == null ? List.of() : List.copyOf(applicableIntents);
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        compactPromptRepresentation = compactPromptRepresentation == null ? "" : compactPromptRepresentation.trim();
        relevance = Math.max(0D, Math.min(relevance, 1D));
    }
}
