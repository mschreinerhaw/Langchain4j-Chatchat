package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SearchFeedbackService {

    private final SearchFeedbackRepository repository;
    private final SearchTokenizer tokenizer;
    private final SearchProperties properties;

    @Transactional
    public SearchFeedbackEntity record(SearchFeedbackRequest request) {
        if (request == null || isBlank(request.queryText())) {
            throw new IllegalArgumentException("queryText is required");
        }
        SearchFeedbackEntity entity = new SearchFeedbackEntity();
        entity.setQueryText(request.queryText().trim());
        entity.setFeedbackType(blankToDefault(request.feedbackType(), "useful"));
        entity.setUserId(blankToNull(request.userId()));
        entity.setDocId(blankToNull(request.docId()));
        entity.setChunkId(blankToNull(request.chunkId()));
        entity.setChunkText(blankToNull(request.chunkText()));
        entity.setPositive(resolvePositive(request));
        entity.setCreatedAt(Instant.now().toEpochMilli());
        return repository.save(entity);
    }

    public FeedbackExpansion expansion(String queryText, List<String> originalTerms) {
        if (isBlank(queryText)) {
            return FeedbackExpansion.empty();
        }
        Set<String> original = new HashSet<>(originalTerms == null ? List.of() : originalTerms);
        Map<String, Float> positive = new HashMap<>();
        Map<String, Float> negative = new HashMap<>();
        int limit = Math.max(1, properties.getLuceneRocchioFeedbackLimit());
        List<SearchFeedbackEntity> feedback = repository.findTop100ByQueryTextIgnoreCaseOrderByCreatedAtDesc(queryText.trim())
            .stream()
            .limit(limit)
            .toList();
        for (SearchFeedbackEntity item : feedback) {
            Map<String, Float> target = Boolean.FALSE.equals(item.getPositive()) ? negative : positive;
            float weight = Boolean.FALSE.equals(item.getPositive()) ? 0.8F : 1.0F;
            for (String token : tokenizer.searchTokens(item.getChunkText())) {
                if (!token.isBlank() && !original.contains(token)) {
                    target.merge(token, weight, Float::sum);
                }
            }
        }
        Set<String> negativeTerms = topTerms(negative, properties.getLuceneRocchioMaxTerms());
        List<String> positiveTerms = topTerms(positive, properties.getLuceneRocchioMaxTerms())
            .stream()
            .filter(term -> !negativeTerms.contains(term))
            .toList();
        return new FeedbackExpansion(positiveTerms, new ArrayList<>(negativeTerms));
    }

    private Set<String> topTerms(Map<String, Float> weights, int limit) {
        Set<String> terms = new LinkedHashSet<>();
        weights.entrySet().stream()
            .sorted(Map.Entry.<String, Float>comparingByValue().reversed()
                .thenComparing(Map.Entry::getKey))
            .limit(Math.max(0, limit))
            .map(Map.Entry::getKey)
            .forEach(terms::add);
        return terms;
    }

    private boolean resolvePositive(SearchFeedbackRequest request) {
        if (request.positive() != null) {
            return request.positive();
        }
        String type = blankToDefault(request.feedbackType(), "useful").toLowerCase();
        return !Set.of("bad", "negative", "rejected", "not_useful", "irrelevant").contains(type);
    }

    private String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SearchFeedbackRequest(
        String queryText,
        String feedbackType,
        Boolean positive,
        String userId,
        String docId,
        String chunkId,
        String chunkText
    ) {
    }

    public record FeedbackExpansion(
        List<String> positiveTerms,
        List<String> negativeTerms
    ) {
        static FeedbackExpansion empty() {
            return new FeedbackExpansion(List.of(), List.of());
        }
    }
}
