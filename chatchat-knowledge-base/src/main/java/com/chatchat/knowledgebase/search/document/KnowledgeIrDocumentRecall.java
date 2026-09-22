package com.chatchat.knowledgebase.search.document;

import com.chatchat.knowledgebase.runtime.index.KnowledgeIREntity;
import com.chatchat.knowledgebase.runtime.index.KnowledgeIRRepository;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds source documents through persisted chapter and paragraph facts. */
@Service
@RequiredArgsConstructor
public class KnowledgeIrDocumentRecall {
    private static final int MAX_TERMS = 12;
    private static final int MAX_UNITS_PER_TERM = 200;

    private final KnowledgeIRRepository repository;
    private final SearchTokenizer tokenizer;

    @Transactional(readOnly = true)
    public Recall recall(DocumentSearchPlan plan, int limit) {
        List<String> terms = tokenizer.searchTokens(plan.query()).stream()
            .distinct().limit(MAX_TERMS).toList();
        if (terms.isEmpty()) return new Recall(List.of(), "");

        Map<String, KnowledgeIREntity> units = new LinkedHashMap<>();
        Map<String, Set<String>> documentTerms = new HashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        Map<String, Integer> termFieldStrength = new HashMap<>();
        Set<String> allowed = new HashSet<>(plan.effectiveScopedFileIds());
        if (allowed.isEmpty() && plan.visibilityContext().active()) {
            allowed.addAll(plan.visibilityScopeIds());
        }
        for (String term : terms) {
            Set<String> documentsForTerm = new HashSet<>();
            List<KnowledgeIREntity> matches = repository.findMatchingUnits(
                plan.permissionContext().tenantId(), "%" + term + "%", PageRequest.of(0, MAX_UNITS_PER_TERM));
            for (KnowledgeIREntity unit : matches) {
                String documentId = unit.getDocumentId();
                if (documentId == null || documentId.isBlank() || (!allowed.isEmpty() && !allowed.contains(documentId))) {
                    continue;
                }
                units.putIfAbsent(documentId + ":" + unit.getKnowledgeId(), unit);
                documentTerms.computeIfAbsent(documentId, ignored -> new HashSet<>()).add(term);
                documentsForTerm.add(documentId);
                int strength = contains(unit.getTitle(), term) || contains(unit.getSourceDocumentName(), term) ? 3
                    : contains(unit.getSourceSection(), term) ? 2 : 1;
                termFieldStrength.merge(term, strength, Math::max);
            }
            documentFrequency.put(term, documentsForTerm.size());
        }
        if (documentTerms.isEmpty()) return new Recall(List.of(), "");

        String focusedTerm = terms.stream()
            .filter(term -> documentFrequency.getOrDefault(term, 0) > 0)
            .min(Comparator.comparingInt((String term) -> documentFrequency.get(term))
                .thenComparing(Comparator.comparingInt((String term) -> termFieldStrength.getOrDefault(term, 0)).reversed())
                .thenComparing(Comparator.comparingInt(String::length).reversed()))
            .orElse(terms.get(0));

        Map<String, Double> scores = new HashMap<>();
        for (KnowledgeIREntity unit : units.values()) {
            String documentId = unit.getDocumentId();
            double bestUnitScore = 0;
            for (String term : documentTerms.getOrDefault(documentId, Set.of())) {
                double rarity = Math.log1p((double) documentTerms.size() / (1 + documentFrequency.getOrDefault(term, 0)));
                if (contains(unit.getTitle(), term) || contains(unit.getSourceSection(), term)) {
                    bestUnitScore += rarity * 3;
                } else if (contains(unit.getSourceDocumentName(), term)) {
                    bestUnitScore += rarity * 2;
                } else if (contains(unit.getSearchText(), term)) {
                    bestUnitScore += rarity;
                }
            }
            scores.merge(documentId, bestUnitScore, Math::max);
        }
        List<String> documentIds = new ArrayList<>(documentTerms.keySet()).stream()
            .filter(id -> documentTerms.get(id).contains(focusedTerm))
            .sorted(Comparator.<String>comparingDouble(id -> scores.getOrDefault(id, 0D))
                .reversed().thenComparing(id -> id))
            .limit(Math.max(1, limit))
            .toList();
        return new Recall(documentIds, focusedTerm);
    }

    private boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    public record Recall(List<String> documentIds, String focusedQuery) {
    }
}
