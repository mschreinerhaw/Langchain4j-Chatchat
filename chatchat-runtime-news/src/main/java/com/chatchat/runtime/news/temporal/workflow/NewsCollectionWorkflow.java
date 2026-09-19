package com.chatchat.runtime.news.temporal.workflow;

import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowCommand;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface NewsCollectionWorkflow {
    @WorkflowMethod(name = "NewsCollectionWorkflow")
    NewsCollectionWorkflowResult collect(NewsCollectionWorkflowCommand command);
}
