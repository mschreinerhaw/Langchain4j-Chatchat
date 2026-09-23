package com.chatchat.knowledgebase.search.workflow;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Applies every registered fine-ranker after RRF. */
@Component
public class CandidateRerankStage implements DocumentRetrievalStage {
    private final List<DocumentCandidateReranker> rerankers;

    public CandidateRerankStage(List<DocumentCandidateReranker> rerankers) {
        this.rerankers = rerankers.stream()
            .sorted(Comparator.comparingInt(DocumentCandidateReranker::order)).toList();
    }

    @Override public String id() { return "candidate-rerank"; }
    @Override public int order() { return 500; }

    @Override
    public void execute(DocumentRetrievalWorkflowContext context) {
        for (DocumentCandidateReranker reranker : rerankers) {
            context.candidates(reranker.rerank(context.searchPlan(), context.candidates()));
        }
    }
}
