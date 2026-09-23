package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.GlobalChunkIndexService;
import com.chatchat.knowledgebase.search.index.GlobalDocumentIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Runs BM25/vector passage recall inside the resolved authorization scope. */
@Component
@RequiredArgsConstructor
public class HybridIndexRecallStage implements DocumentRetrievalStage {
    private final GlobalDocumentIndexService globalDocumentIndexService;
    private final GlobalChunkIndexService globalChunkIndexService;
    private final SearchProperties properties;

    @Override public String id() { return "bm25-vector-recall"; }
    @Override public int order() { return 300; }

    @Override
    public void execute(DocumentRetrievalWorkflowContext context) {
        boolean scopedIndex = properties.isDocumentFirstEnabled()
            || (!context.authorizedDocumentIds().isEmpty()
                && properties.getHybridRetrieval() != null
                && properties.getHybridRetrieval().isEnabled());
        context.documentPage(scopedIndex ? null
            : globalDocumentIndexService.recall(context.searchPlan(), context.documentLimit()));
        context.chunkPage(globalChunkIndexService.recall(context.searchPlan()));
    }
}
