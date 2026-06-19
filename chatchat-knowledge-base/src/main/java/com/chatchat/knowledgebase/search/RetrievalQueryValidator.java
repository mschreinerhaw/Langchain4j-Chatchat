package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RetrievalQueryValidator {

    private static final Set<String> GENERIC_TERMS = Set.of(
        "analysis", "data", "document", "documents", "file", "files", "info", "information",
        "material", "materials", "note", "notes", "report", "reports", "search", "summary",
        "\u8d44\u6599", "\u4fe1\u606f", "\u5185\u5bb9", "\u6587\u6863", "\u6587\u4ef6",
        "\u62a5\u544a", "\u6570\u636e", "\u5206\u6790", "\u603b\u7ed3"
    );

    private final SearchTokenizer tokenizer;
    private final SearchProperties properties;

    public RetrievalValidationResult validate(String query, boolean scoped, DocumentSearchFilters filters) {
        String normalizedQuery = query == null ? "" : query.trim();
        SearchProperties.RetrievalControl control = properties.getRetrievalControl();
        if (control == null || !control.isEnabled() || !control.isQueryValidationEnabled()) {
            return allow(normalizedQuery, "validation_disabled");
        }
        if (normalizedQuery.isBlank()) {
            return reject(normalizedQuery, "blank_query", 1.0D);
        }
        List<String> tokens = tokenizer.searchTokens(normalizedQuery);
        if (tokens.isEmpty()) {
            return reject(normalizedQuery, "stopwords_only", 0.95D);
        }
        if (hasExplicitScope(scoped, filters)) {
            return allow(normalizedQuery, "scoped_query");
        }
        int specificTokens = specificTokenCount(tokens);
        if (tokens.size() == 1 && isGeneric(tokens.get(0))) {
            return rewrite(normalizedQuery, "broad_single_generic_term", 0.9D);
        }
        if (specificTokens < Math.max(1, control.getMinSpecificTokens()) && lacksConcreteSignal(normalizedQuery, tokens)) {
            return rewrite(normalizedQuery, "insufficient_query_scope", 0.75D);
        }
        return allow(normalizedQuery, "query_specific_enough");
    }

    private boolean hasExplicitScope(boolean scoped, DocumentSearchFilters filters) {
        if (scoped) {
            return true;
        }
        return filters != null
            && (hasText(filters.tag())
            || hasText(filters.company())
            || hasText(filters.industry())
            || hasText(filters.fileType())
            || hasText(filters.chunkType()));
    }

    private int specificTokenCount(List<String> tokens) {
        int count = 0;
        for (String token : tokens == null ? List.<String>of() : tokens) {
            if (hasText(token) && !isGeneric(token)) {
                count++;
            }
        }
        return count;
    }

    private boolean lacksConcreteSignal(String query, List<String> tokens) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*\\d{4}.*")) {
            return false;
        }
        if (normalized.matches(".*[a-zA-Z]+[-_]?\\d+.*")) {
            return false;
        }
        for (String token : tokens == null ? List.<String>of() : tokens) {
            if (hasText(token) && token.length() >= 4 && !isGeneric(token)) {
                return false;
            }
        }
        return true;
    }

    private boolean isGeneric(String token) {
        return token != null && GENERIC_TERMS.contains(token.trim().toLowerCase(Locale.ROOT));
    }

    private RetrievalValidationResult allow(String query, String reason) {
        return new RetrievalValidationResult(RetrievalControlAction.ALLOW, query, reason, 1.0D);
    }

    private RetrievalValidationResult rewrite(String query, String reason, double confidence) {
        return new RetrievalValidationResult(RetrievalControlAction.REWRITE, query, reason, confidence);
    }

    private RetrievalValidationResult reject(String query, String reason, double confidence) {
        return new RetrievalValidationResult(RetrievalControlAction.REJECT, query, reason, confidence);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
