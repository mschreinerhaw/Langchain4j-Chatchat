package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;

import java.util.List;

/** SPI for cross-encoder, business-signal, or other candidate rerankers. */
public interface DocumentCandidateReranker {
    int order();
    List<DocumentSearchCandidate> rerank(DocumentSearchPlan plan, List<DocumentSearchCandidate> candidates);
}
