package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeType;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapts normalized Knowledge IR to LangChain4j Advanced RAG content aggregation.
 * Runtime-owned scope, permissions and source references remain on the original IR objects.
 */
final class LangChain4jKnowledgeFusion {

    private final DefaultContentAggregator aggregator = new DefaultContentAggregator();

    List<KnowledgeIR> fuse(KnowledgeRequest request, List<KnowledgeIR> units) {
        if (units == null || units.size() < 2) {
            return units == null ? List.of() : List.copyOf(units);
        }

        Map<KnowledgeType, List<KnowledgeIR>> byKnowledgeType = new EnumMap<>(KnowledgeType.class);
        for (KnowledgeIR unit : units) {
            if (unit != null) byKnowledgeType.computeIfAbsent(unit.type(), ignored -> new ArrayList<>()).add(unit);
        }

        Map<String, KnowledgeIR> representativeByKey = new LinkedHashMap<>();
        Map<String, Integer> sourceOccurrences = new LinkedHashMap<>();
        Collection<List<Content>> rankedSources = new ArrayList<>();
        for (List<KnowledgeIR> source : byKnowledgeType.values()) {
            Map<String, KnowledgeIR> uniqueSource = new LinkedHashMap<>();
            source.stream()
                .sorted(Comparator.comparingDouble(KnowledgeIR::relevance).reversed())
                .forEach(unit -> uniqueSource.merge(canonicalKey(unit), unit,
                    (left, right) -> left.relevance() >= right.relevance() ? left : right));
            List<Content> contents = new ArrayList<>();
            for (Map.Entry<String, KnowledgeIR> entry : uniqueSource.entrySet()) {
                sourceOccurrences.merge(entry.getKey(), 1, Integer::sum);
                representativeByKey.merge(entry.getKey(), entry.getValue(),
                    (left, right) -> left.relevance() >= right.relevance() ? left : right);
                contents.add(Content.from(entry.getKey()));
            }
            if (!contents.isEmpty()) rankedSources.add(List.copyOf(contents));
        }

        if (rankedSources.isEmpty()) return List.of();
        List<Content> fused = aggregator.aggregate(Map.of(Query.from(request.query()), rankedSources));
        List<KnowledgeIR> ranked = new ArrayList<>();
        Map<String, Integer> fusedPosition = new LinkedHashMap<>();
        for (int index = 0; index < fused.size(); index++) {
            fusedPosition.put(fused.get(index).textSegment().text(), index);
        }
        for (Content content : fused) {
            KnowledgeIR unit = representativeByKey.remove(content.textSegment().text());
            if (unit != null) ranked.add(unit);
        }
        ranked.addAll(representativeByKey.values());
        ranked.sort((left, right) -> {
            String leftKey = canonicalKey(left);
            String rightKey = canonicalKey(right);
            int comparison = Integer.compare(sourceOccurrences.getOrDefault(rightKey, 0),
                sourceOccurrences.getOrDefault(leftKey, 0));
            if (comparison != 0) return comparison;
            comparison = Double.compare(weightedRelevance(right), weightedRelevance(left));
            if (comparison != 0) return comparison;
            return Integer.compare(fusedPosition.getOrDefault(leftKey, Integer.MAX_VALUE),
                fusedPosition.getOrDefault(rightKey, Integer.MAX_VALUE));
        });
        return List.copyOf(ranked);
    }

    private String canonicalKey(KnowledgeIR unit) {
        String content = unit.compactPromptRepresentation().isBlank()
            ? unit.semanticDescription() : unit.compactPromptRepresentation();
        if (content.isBlank()) content = unit.knowledgeId();
        return content.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private double weightedRelevance(KnowledgeIR unit) {
        double typeWeight = switch (unit.type()) {
            case RULE, CONSTRAINT, METRIC -> 1.0D;
            case POLICY, PROCEDURE -> 0.9D;
            case CONCEPT, METHOD -> 0.8D;
            case FAQ -> 0.7D;
            case EXAMPLE, INTERPRETATION -> 0.5D;
        };
        return typeWeight * unit.relevance();
    }
}
