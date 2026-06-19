package com.chatchat.knowledgebase.search;

public record RetrievalEvidenceQuality(
    boolean usable,
    double confidence,
    String reason
) {

    public static RetrievalEvidenceQuality empty(String reason) {
        return new RetrievalEvidenceQuality(false, 0.0D, reason == null ? "empty_result" : reason);
    }
}
