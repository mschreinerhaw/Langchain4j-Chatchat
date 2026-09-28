package com.chatchat.knowledgebase.search.document.application;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.api.search.DocumentRecallResult;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.index.application.GlobalChunkIndexService;
import com.chatchat.knowledgebase.search.index.application.GlobalDocumentIndexService;
import com.chatchat.knowledgebase.search.index.application.IndexVersionManager;
import com.chatchat.knowledgebase.search.workflow.AuthorizedDocumentScopeStage;
import com.chatchat.knowledgebase.search.workflow.DocumentRetrievalWorkflow;
import com.chatchat.knowledgebase.search.workflow.HybridIndexRecallStage;
import com.chatchat.knowledgebase.search.workflow.OriginalQueryStage;
import com.chatchat.knowledgebase.search.workflow.ReciprocalRankFusionStage;
import com.chatchat.knowledgebase.search.workflow.CandidateRerankStage;
import com.chatchat.knowledgebase.search.workflow.SkillRoleContextStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** Compatibility facade for the extensible document retrieval workflow. */
@Service
public class DocumentSearchOrchestrator {
    private final DocumentRetrievalWorkflow workflow;

    @Autowired
    public DocumentSearchOrchestrator(DocumentRetrievalWorkflow workflow) {
        this.workflow = workflow;
    }

    /** Kept for isolated callers and tests that construct the former orchestrator directly. */
    public DocumentSearchOrchestrator(GlobalDocumentIndexService documents,
                                      GlobalChunkIndexService chunks,
                                      SearchProperties properties,
                                      IndexVersionManager versions,
                                      KnowledgeIrDocumentRecall irRecall) {
        this(new DocumentRetrievalWorkflow(List.of(
            new OriginalQueryStage(),
            new SkillRoleContextStage(),
            new AuthorizedDocumentScopeStage(irRecall, properties),
            new HybridIndexRecallStage(documents, chunks, properties),
            new ReciprocalRankFusionStage(properties, versions),
            new CandidateRerankStage(List.of())
        )));
    }

    public DocumentRecallResult recall(DocumentSearchPlan plan, int documentLimit) {
        return workflow.execute(plan, documentLimit);
    }
}
