package com.chatchat.knowledgebase.search;

public record DocumentExpansionPolicy(
    boolean needsExpansion,
    int maxSections,
    int maxChunks,
    int maxTotalChars,
    String trigger,
    boolean queryRequired,
    String originalQuery,
    String intent
) {
    public DocumentExpansionPolicy(boolean needsExpansion,
                                   int maxSections,
                                   int maxChunks,
                                   int maxTotalChars,
                                   String trigger) {
        this(needsExpansion, maxSections, maxChunks, maxTotalChars, trigger, false, null, null);
    }

    public DocumentExpansionPolicy withQueryContext(String originalQuery, String intent) {
        if (!needsExpansion && (originalQuery == null || originalQuery.isBlank()) && (intent == null || intent.isBlank())) {
            return this;
        }
        return new DocumentExpansionPolicy(
            needsExpansion,
            maxSections,
            maxChunks,
            maxTotalChars,
            trigger,
            needsExpansion,
            originalQuery,
            intent
        );
    }

    public static DocumentExpansionPolicy titleOnly() {
        return new DocumentExpansionPolicy(true, 3, 6, 6000, "TITLE_ONLY_MATCH", true, null, null);
    }

    public static DocumentExpansionPolicy mixed() {
        return new DocumentExpansionPolicy(true, 2, 4, 4000, "MIXED_MATCH", true, null, null);
    }

    public static DocumentExpansionPolicy none() {
        return new DocumentExpansionPolicy(false, 0, 0, 0, "CONTENT_EVIDENCE_READY", false, null, null);
    }
}
