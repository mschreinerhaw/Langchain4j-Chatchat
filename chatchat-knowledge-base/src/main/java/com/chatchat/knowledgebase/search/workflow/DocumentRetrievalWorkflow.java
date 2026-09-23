package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.document.DocumentRecallResult;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.query.QueryExpander;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Executes retrieval as an ordered, extensible workflow. */
@Service
@Slf4j
public class DocumentRetrievalWorkflow {
    private final List<DocumentRetrievalStage> stages;

    public DocumentRetrievalWorkflow(List<DocumentRetrievalStage> stages) {
        this.stages = stages.stream().sorted(Comparator.comparingInt(DocumentRetrievalStage::order)).toList();
        Set<String> ids = new HashSet<>();
        for (DocumentRetrievalStage stage : this.stages) {
            if (!ids.add(stage.id())) throw new IllegalStateException("Duplicate document retrieval stage: " + stage.id());
        }
    }

    public DocumentRecallResult execute(DocumentSearchPlan plan, int documentLimit) {
        DocumentRetrievalWorkflowContext context = new DocumentRetrievalWorkflowContext(plan, documentLimit);
        QueryExpander.withoutExpansion(() -> {
            for (DocumentRetrievalStage stage : stages) {
                if (context.stopped()) break;
                stage.execute(context);
                context.completed(stage.id());
            }
            return null;
        });
        log.debug("document_retrieval_workflow query='{}' stages={} stopped={} candidates={}",
            plan.query(), context.completedStages(), context.stopped(), context.candidates().size());
        return new DocumentRecallResult(context.documentPage(), context.chunkPage(), context.candidates(),
            context.authorizedDocumentIds(), context.focusedQuery(), context.verifiedSources(),
            context.parentSections());
    }
}
