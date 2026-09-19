package com.chatchat.runtime.news.temporal.activity;

import com.chatchat.runtime.news.application.collection.NewsCollectionTasks;
import com.chatchat.runtime.news.collector.schedule.NewsCollectionSchedulePolicy;
import com.chatchat.runtime.news.source.persistence.NewsSourceRepository;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowResult;
import io.temporal.activity.Activity;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class NewsCollectionActivityImpl implements NewsCollectionActivity {
    private final NewsSourceRepository sources;
    private final NewsCollectionTasks tasks;
    private final NewsCollectionSchedulePolicy schedulePolicy;

    public NewsCollectionActivityImpl(NewsSourceRepository sources, NewsCollectionTasks tasks,
                                      NewsCollectionSchedulePolicy schedulePolicy) {
        this.sources = sources;
        this.tasks = tasks;
        this.schedulePolicy = schedulePolicy;
    }

    @Override
    public NewsCollectionWorkflowResult collect(Long sourceId) {
        var source = sources.findById(sourceId).orElse(null);
        if (source == null || !source.isEnabled()) {
            return NewsCollectionWorkflowResult.skipped(sourceId, "News source is missing or disabled");
        }
        if (!schedulePolicy.allowsAutomaticCollection(source.getConfigurationJson(), Instant.now())) {
            return NewsCollectionWorkflowResult.skipped(sourceId, "Outside configured collection window");
        }
        var info = Activity.getExecutionContext().getInfo();
        String executionId = "temporal:" + info.getWorkflowId() + ":" + info.getRunId();
        NewsCollectionTasks.Task task = tasks.executeScheduled(sourceId, executionId);
        if (task.result() == null) {
            return NewsCollectionWorkflowResult.skipped(sourceId,
                "Another collection task for this source is already active");
        }
        var result = task.result();
        return new NewsCollectionWorkflowResult(task.executionId(), sourceId, task.status(),
            result.discoveredCount(), result.acceptedCount(), result.duplicateCount(),
            result.rejectedCount(), result.failedCount(), task.errorMessage());
    }
}
