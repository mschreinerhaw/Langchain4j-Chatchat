package com.chatchat.runtime.news.temporal.contract;

public record NewsCollectionWorkflowCommand(
    Long sourceId,
    long activityStartToCloseSeconds,
    int activityMaximumAttempts
) { }
