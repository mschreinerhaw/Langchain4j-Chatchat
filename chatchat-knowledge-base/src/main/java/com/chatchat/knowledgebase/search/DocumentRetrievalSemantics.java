package com.chatchat.knowledgebase.search;

public record DocumentRetrievalSemantics(
    DocumentDataSafetyLevel dataSafetyLevel,
    boolean canAnswerDirectly,
    boolean requiresExpansion,
    String reason
) {
    public static DocumentRetrievalSemantics evidenceBody() {
        return new DocumentRetrievalSemantics(
            DocumentDataSafetyLevel.EVIDENCE_BODY,
            true,
            false,
            "content_evidence_ready"
        );
    }

    public static DocumentRetrievalSemantics partialEvidence() {
        return new DocumentRetrievalSemantics(
            DocumentDataSafetyLevel.PARTIAL_EVIDENCE_BODY,
            true,
            true,
            "metadata_and_partial_content_evidence"
        );
    }

    public static DocumentRetrievalSemantics titleOnly() {
        return new DocumentRetrievalSemantics(
            DocumentDataSafetyLevel.NO_EVIDENCE_BODY,
            false,
            true,
            "matched_title_or_filename_only"
        );
    }

    public static DocumentRetrievalSemantics noHit() {
        return new DocumentRetrievalSemantics(
            DocumentDataSafetyLevel.NO_EVIDENCE_BODY,
            false,
            false,
            "no_document_evidence"
        );
    }
}
