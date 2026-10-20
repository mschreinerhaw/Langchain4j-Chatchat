package com.chatchat.knowledgebase.search.workflow.ranking;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.workflow.core.DocumentRetrievalStage;
import com.chatchat.knowledgebase.search.workflow.core.DocumentRetrievalWorkflowContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bounds authorized document search results without a remote model or reranker. */
@Component
@RequiredArgsConstructor
public class CandidateLimitStage implements DocumentRetrievalStage {
    private final SearchProperties properties;
    @Override public String id() { return "candidate-limit"; }
    @Override public int order() { return 500; }
    @Override public void execute(DocumentRetrievalWorkflowContext context) {
        context.candidates(context.candidates().stream()
            .limit(Math.max(1, properties.getProblemAnalysis().getFinalEvidenceLimit())).toList());
    }
}
