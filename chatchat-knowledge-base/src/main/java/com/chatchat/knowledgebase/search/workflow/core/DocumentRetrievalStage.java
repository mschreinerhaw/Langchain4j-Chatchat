package com.chatchat.knowledgebase.search.workflow.core;

/** One replaceable step in the document retrieval workflow. */
public interface DocumentRetrievalStage {

    String id();

    int order();

    void execute(DocumentRetrievalWorkflowContext context);
}
