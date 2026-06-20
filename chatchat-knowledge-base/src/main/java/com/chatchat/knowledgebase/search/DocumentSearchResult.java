package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentSearchResult(
    String contractVersion,
    String query,
    String intent,
    int total,
    List<DocumentEvidenceChunk> results,
    String context,
    List<DocumentEvidenceCitation> citations,
    RetrievalExecutionState retrievalState,
    RetrievalEvidenceQuality evidenceQuality,
    List<RetrievalEvent> retrievalEvents,
    DocumentSearchMatchType matchType,
    DocumentRetrievalSemantics retrievalSemantics,
    List<DocumentSearchHit> documents,
    List<DocumentOutlineItem> outline,
    DocumentOutlineSource outlineSource,
    DocumentExpansionPolicy expansionPolicy,
    EvidenceGovernancePolicy evidenceGovernancePolicy,
    EvidenceReasoningResult reasoning,
    EvidenceDecisionResult decision
) {

    public DocumentSearchResult {
        results = results == null ? List.of() : List.copyOf(results);
        citations = citations == null ? List.of() : List.copyOf(citations);
        retrievalEvents = retrievalEvents == null ? List.of() : List.copyOf(retrievalEvents);
        documents = documents == null ? List.of() : List.copyOf(documents);
        outline = outline == null ? List.of() : List.copyOf(outline);
        matchType = matchType == null ? defaultMatchType(results, documents) : matchType;
        retrievalSemantics = retrievalSemantics == null ? defaultSemantics(matchType) : retrievalSemantics;
        outlineSource = outlineSource == null && !outline.isEmpty() ? outline.get(0).source() : outlineSource;
        expansionPolicy = expansionPolicy == null ? defaultExpansionPolicy(matchType) : expansionPolicy;
        evidenceGovernancePolicy = evidenceGovernancePolicy == null ? defaultGovernancePolicy(matchType, expansionPolicy) : evidenceGovernancePolicy;
        reasoning = reasoning == null ? EvidenceReasoningResult.empty(query) : reasoning;
        decision = decision == null ? EvidenceDecisionResult.refuse("Decision has not been evaluated") : decision;
    }

    public DocumentSearchResult(String contractVersion,
                                String query,
                                String intent,
                                int total,
                                List<DocumentEvidenceChunk> results,
                                String context,
                                List<DocumentEvidenceCitation> citations,
                                RetrievalExecutionState retrievalState,
                                RetrievalEvidenceQuality evidenceQuality,
                                List<RetrievalEvent> retrievalEvents,
                                DocumentSearchMatchType matchType,
                                DocumentRetrievalSemantics retrievalSemantics,
                                List<DocumentSearchHit> documents,
                                List<DocumentOutlineItem> outline,
                                DocumentOutlineSource outlineSource,
                                DocumentExpansionPolicy expansionPolicy,
                                EvidenceGovernancePolicy evidenceGovernancePolicy,
                                EvidenceReasoningResult reasoning) {
        this(
            contractVersion,
            query,
            intent,
            total,
            results,
            context,
            citations,
            retrievalState,
            evidenceQuality,
            retrievalEvents,
            matchType,
            retrievalSemantics,
            documents,
            outline,
            outlineSource,
            expansionPolicy,
            evidenceGovernancePolicy,
            reasoning,
            null
        );
    }

    public DocumentSearchResult(String contractVersion,
                                String query,
                                String intent,
                                int total,
                                List<DocumentEvidenceChunk> results,
                                String context,
                                List<DocumentEvidenceCitation> citations,
                                RetrievalExecutionState retrievalState,
                                RetrievalEvidenceQuality evidenceQuality,
                                List<RetrievalEvent> retrievalEvents,
                                DocumentSearchMatchType matchType,
                                DocumentRetrievalSemantics retrievalSemantics,
                                List<DocumentSearchHit> documents,
                                List<DocumentOutlineItem> outline,
                                DocumentOutlineSource outlineSource,
                                DocumentExpansionPolicy expansionPolicy,
                                EvidenceGovernancePolicy evidenceGovernancePolicy) {
        this(
            contractVersion,
            query,
            intent,
            total,
            results,
            context,
            citations,
            retrievalState,
            evidenceQuality,
            retrievalEvents,
            matchType,
            retrievalSemantics,
            documents,
            outline,
            outlineSource,
            expansionPolicy,
            evidenceGovernancePolicy,
            null,
            null
        );
    }

    public DocumentSearchResult(String contractVersion,
                                String query,
                                String intent,
                                int total,
                                List<DocumentEvidenceChunk> results,
                                String context,
                                List<DocumentEvidenceCitation> citations) {
        this(
            contractVersion,
            query,
            intent,
            total,
            results,
            context,
            citations,
            null,
            null,
            List.of(),
            null,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null
        );
    }

    private static DocumentSearchMatchType defaultMatchType(List<DocumentEvidenceChunk> results, List<DocumentSearchHit> documents) {
        if (results != null && !results.isEmpty() && documents != null && !documents.isEmpty()) {
            return DocumentSearchMatchType.MIXED_HIT;
        }
        if (results != null && !results.isEmpty()) {
            return DocumentSearchMatchType.CONTENT_HIT;
        }
        if (documents != null && !documents.isEmpty()) {
            return DocumentSearchMatchType.TITLE_ONLY_HIT;
        }
        return DocumentSearchMatchType.NO_HIT;
    }

    private static DocumentRetrievalSemantics defaultSemantics(DocumentSearchMatchType matchType) {
        return switch (matchType) {
            case CONTENT_HIT -> DocumentRetrievalSemantics.evidenceBody();
            case MIXED_HIT -> DocumentRetrievalSemantics.partialEvidence();
            case TITLE_ONLY_HIT -> DocumentRetrievalSemantics.titleOnly();
            case NO_HIT -> DocumentRetrievalSemantics.noHit();
        };
    }

    private static DocumentExpansionPolicy defaultExpansionPolicy(DocumentSearchMatchType matchType) {
        return switch (matchType) {
            case TITLE_ONLY_HIT -> DocumentExpansionPolicy.titleOnly();
            case MIXED_HIT -> DocumentExpansionPolicy.mixed();
            case CONTENT_HIT, NO_HIT -> DocumentExpansionPolicy.none();
        };
    }

    private static EvidenceGovernancePolicy defaultGovernancePolicy(DocumentSearchMatchType matchType, DocumentExpansionPolicy expansionPolicy) {
        return switch (matchType) {
            case CONTENT_HIT, MIXED_HIT -> EvidenceGovernancePolicy.contentReady();
            case TITLE_ONLY_HIT -> EvidenceGovernancePolicy.needsExpansion(
                expansionPolicy == null ? 3 : expansionPolicy.maxSections(),
                expansionPolicy == null ? 6 : expansionPolicy.maxChunks()
            );
            case NO_HIT -> EvidenceGovernancePolicy.noEvidence();
        };
    }
}
