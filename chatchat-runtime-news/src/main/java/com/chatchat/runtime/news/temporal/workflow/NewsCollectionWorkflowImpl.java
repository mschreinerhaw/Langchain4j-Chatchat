package com.chatchat.runtime.news.temporal.workflow;

import com.chatchat.runtime.news.temporal.activity.NewsCollectionActivity;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowCommand;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class NewsCollectionWorkflowImpl implements NewsCollectionWorkflow {
    @Override
    public NewsCollectionWorkflowResult collect(NewsCollectionWorkflowCommand command) {
        if (command == null || command.sourceId() == null) {
            throw new IllegalArgumentException("News collection workflow command and sourceId are required");
        }
        NewsCollectionActivity activity = Workflow.newActivityStub(NewsCollectionActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(
                    Math.max(60L, command.activityStartToCloseSeconds())))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(10))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(5))
                    .setMaximumAttempts(Math.max(1, command.activityMaximumAttempts()))
                    .build())
                .build());
        return activity.collect(command.sourceId());
    }
}
