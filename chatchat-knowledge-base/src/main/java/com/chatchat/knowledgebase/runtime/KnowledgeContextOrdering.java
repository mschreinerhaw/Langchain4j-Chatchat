package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.model.KnowledgeIR;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Orders already authorized Skill context; performs no retrieval, fusion or model call. */
final class KnowledgeContextOrdering {
    List<KnowledgeIR> order(List<KnowledgeIR> units) {
        if (units == null || units.isEmpty()) return List.of();
        var unique = new LinkedHashMap<String, KnowledgeIR>();
        units.stream().filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparingDouble(this::priority).reversed())
            .forEach(unit -> unique.putIfAbsent(key(unit), unit));
        return List.copyOf(unique.values());
    }

    private double priority(KnowledgeIR unit) {
        return unit.relevance() * switch (unit.type()) {
            case RULE, CONSTRAINT, METRIC -> 1.0D;
            case POLICY, PROCEDURE -> 0.9D;
            case CONCEPT, METHOD -> 0.8D;
            case FAQ -> 0.7D;
            case EXAMPLE, INTERPRETATION -> 0.5D;
        };
    }

    private String key(KnowledgeIR unit) {
        String content = unit.compactPromptRepresentation().isBlank()
            ? unit.semanticDescription() : unit.compactPromptRepresentation();
        if (content.isBlank()) content = unit.knowledgeId();
        return content.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
