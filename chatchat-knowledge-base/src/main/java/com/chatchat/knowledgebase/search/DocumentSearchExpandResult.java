package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentSearchExpandResult(
    String contractVersion,
    String query,
    String docId,
    List<DocumentExpandedEvidenceChunk> evidenceChunks,
    String context,
    List<DocumentEvidenceCitation> citations,
    DocumentRetrievalSemantics retrievalSemantics,
    DocumentExpansionPolicy expansionPolicy,
    EvidenceGovernancePolicy evidenceGovernancePolicy,
    EvidenceReasoningResult reasoning,
    EvidenceDecisionResult decision
) {
    public DocumentSearchExpandResult {
        evidenceChunks = evidenceChunks == null ? List.of() : List.copyOf(evidenceChunks);
        citations = citations == null ? List.of() : List.copyOf(citations);
        retrievalSemantics = retrievalSemantics == null ? DocumentRetrievalSemantics.evidenceBody() : retrievalSemantics;
        expansionPolicy = expansionPolicy == null ? DocumentExpansionPolicy.none() : expansionPolicy;
        evidenceGovernancePolicy = evidenceGovernancePolicy == null ? EvidenceGovernancePolicy.contentReady() : evidenceGovernancePolicy;
        reasoning = reasoning == null ? EvidenceReasoningResult.empty(query) : reasoning;
        decision = decision == null ? EvidenceDecisionResult.refuse("Decision has not been evaluated") : decision;
    }

    public DocumentSearchExpandResult(String contractVersion,
                                      String query,
                                      String docId,
                                      List<DocumentExpandedEvidenceChunk> evidenceChunks,
                                      String context,
                                      List<DocumentEvidenceCitation> citations,
                                      DocumentRetrievalSemantics retrievalSemantics,
                                      DocumentExpansionPolicy expansionPolicy,
                                      EvidenceGovernancePolicy evidenceGovernancePolicy) {
        this(
            contractVersion,
            query,
            docId,
            evidenceChunks,
            context,
            citations,
            retrievalSemantics,
            expansionPolicy,
            evidenceGovernancePolicy,
            null,
            null
        );
    }

    public DocumentSearchExpandResult(String contractVersion,
                                      String query,
                                      String docId,
                                      List<DocumentExpandedEvidenceChunk> evidenceChunks,
                                      String context,
                                      List<DocumentEvidenceCitation> citations,
                                      DocumentRetrievalSemantics retrievalSemantics,
                                      DocumentExpansionPolicy expansionPolicy,
                                      EvidenceGovernancePolicy evidenceGovernancePolicy,
                                      EvidenceReasoningResult reasoning) {
        this(
            contractVersion,
            query,
            docId,
            evidenceChunks,
            context,
            citations,
            retrievalSemantics,
            expansionPolicy,
            evidenceGovernancePolicy,
            reasoning,
            null
        );
    }
}
