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
    SectionGraph sectionGraph,
    KnowledgeRuntimeGraph knowledgeGraph,
    KnowledgeTraversalResult traversal,
    KnowledgeReasoningResult knowledgeReasoning,
    EvidenceReasoningResult reasoning,
    EvidenceDecisionResult decision
) {
    public DocumentSearchExpandResult {
        evidenceChunks = evidenceChunks == null ? List.of() : List.copyOf(evidenceChunks);
        citations = citations == null ? List.of() : List.copyOf(citations);
        retrievalSemantics = retrievalSemantics == null ? DocumentRetrievalSemantics.evidenceBody() : retrievalSemantics;
        expansionPolicy = expansionPolicy == null ? DocumentExpansionPolicy.none() : expansionPolicy;
        evidenceGovernancePolicy = evidenceGovernancePolicy == null ? EvidenceGovernancePolicy.contentReady() : evidenceGovernancePolicy;
        sectionGraph = sectionGraph == null ? SectionGraph.empty(docId, query) : sectionGraph;
        knowledgeGraph = knowledgeGraph == null ? KnowledgeRuntimeGraph.empty(docId, query) : knowledgeGraph;
        traversal = traversal == null ? KnowledgeTraversalResult.empty(query) : traversal;
        knowledgeReasoning = knowledgeReasoning == null ? KnowledgeReasoningResult.empty(query) : knowledgeReasoning;
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
            null,
            null,
            null,
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
            null,
            null,
            null,
            null,
            reasoning,
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
                                      SectionGraph sectionGraph,
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
            sectionGraph,
            null,
            null,
            null,
            reasoning,
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
                                      SectionGraph sectionGraph,
                                      KnowledgeRuntimeGraph knowledgeGraph,
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
            sectionGraph,
            knowledgeGraph,
            null,
            null,
            reasoning,
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
                                      SectionGraph sectionGraph,
                                      KnowledgeRuntimeGraph knowledgeGraph,
                                      KnowledgeTraversalResult traversal,
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
            sectionGraph,
            knowledgeGraph,
            traversal,
            null,
            reasoning,
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
                                      SectionGraph sectionGraph,
                                      KnowledgeRuntimeGraph knowledgeGraph,
                                      KnowledgeTraversalResult traversal,
                                      KnowledgeReasoningResult knowledgeReasoning,
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
            sectionGraph,
            knowledgeGraph,
            traversal,
            knowledgeReasoning,
            reasoning,
            null
        );
    }
}
