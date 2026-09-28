package com.chatchat.knowledgebase.search.workflow.ranking;

import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchPlan;

import java.util.List;

/** SPI for cross-encoder, business-signal, or other candidate rerankers. */
public interface DocumentCandidateReranker {
    int order();
    List<DocumentSearchCandidate> rerank(DocumentSearchPlan plan, List<DocumentSearchCandidate> candidates);
}
